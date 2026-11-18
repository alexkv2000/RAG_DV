package kvo.itsnn.rag.eval;

import kvo.itsnn.rag.ask.AskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Eval-прогон качества поиска по golden-вопросам (dbo.rag_eval_question).
 * Каждый вопрос идёт ТЕМ ЖЕ путём, что у пользователя (AskService.retrieveTop):
 * embed → Chroma(candidateChunks) → тексты из MSSQL → реранк → дедуп → contextChunks.
 * LLM не вызывается — дёшево и повторяемо.
 *
 * Метрики: recall@contextChunks (попал ли ожидаемый файл в итоговую выдачу,
 * с фиксацией ранга), expect_must — слова, обязанные быть в собранном контексте
 * (прокси «ответу было из чего взяться»), full_pass — вопрос засчитан целиком.
 * Каждый прогон пишется в dbo.rag_eval_run: история «до/после» изменений.
 *
 * Сопоставление имён мягкое: нормализация (нижний регистр, ё→е, схлопнутые
 * пробелы) и «содержится» — ожидание можно указать частью имени файла.
 */
@Service
public class EvalService {

    private static final Logger log = LoggerFactory.getLogger(EvalService.class);
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final JdbcTemplate jdbc;
    private final AskService ask;
    /** Один прогон на инстанс: retrieveTop гоняет реранкер на CPU — параллелить незачем. */
    private final AtomicBoolean busy = new AtomicBoolean();

    public EvalService(JdbcTemplate jdbc, AskService ask) {
        this.jdbc = jdbc;
        this.ask = ask;
    }

    /** Вопрос из таблицы. */
    public record Question(int questionId, String question, String classifier,
                           String expectFiles, String expectMust, String note) {}

    /** Полный прогон; результат — сводка + details по каждому вопросу. */
    public Map<String, Object> run(String label) {
        if (!busy.compareAndSet(false, true))
            throw new IllegalStateException("eval-прогон уже выполняется — дождитесь завершения");
        try {
            List<Question> questions = loadQuestions();
            if (questions.isEmpty())
                throw new IllegalArgumentException(
                        "в dbo.rag_eval_question нет включённых вопросов — добавьте хотя бы один");

            long t0 = System.currentTimeMillis();
            int expectedTotal = 0, foundTotal = 0, fullPass = 0, mustFail = 0;
            List<Map<String, Object>> rows = new ArrayList<>(questions.size());

            for (Question q : questions) {
                long q0 = System.currentTimeMillis();
                List<AskService.Retrieved> top = ask.retrieveTop(q.question(), q.classifier());

                List<String> wants = split(q.expectFiles());
                int expected = wants.size(), found = 0;
                List<Map<String, Object>> ranks = new ArrayList<>(expected);
                for (String w : wants) {
                    int rank = rankOf(top, w);
                    if (rank > 0) found++;
                    ranks.add(Map.of("file", w, "rank", rank));
                }
                String context = top.stream().map(AskService.Retrieved::text)
                        .collect(Collectors.joining("\n"));
                boolean mustOk = mustIn(context, q.expectMust());
                if (!mustOk) mustFail++;
                boolean ok = expected > 0 && found == expected && mustOk;
                if (ok) fullPass++;
                expectedTotal += expected;
                foundTotal += found;

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("questionId", q.questionId());
                row.put("question", q.question());
                row.put("classifier", q.classifier() == null ? "" : q.classifier());
                row.put("expected", expected);
                row.put("found", found);
                row.put("mustOk", mustOk);
                row.put("ok", ok);
                row.put("ranks", ranks);
                row.put("ms", System.currentTimeMillis() - q0);
                rows.add(row);
            }

            long ms = System.currentTimeMillis() - t0;
            double recall = expectedTotal == 0 ? 0.0 : (double) foundTotal / expectedTotal;
            jdbc.update("""
                    INSERT INTO dbo.rag_eval_run (label, questions, expected, found, recall, full_pass, ms, details)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, nz(label), questions.size(), expectedTotal, foundTotal, recall, fullPass, ms,
                    JSON.writeValueAsString(rows));
            log.info("EVAL: {} вопросов, recall={}, full_pass={}, {} мс",
                    questions.size(), String.format(Locale.ROOT, "%.3f", recall), fullPass, ms);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("result", "done");
            out.put("label", nz(label));
            out.put("questions", questions.size());
            out.put("expected", expectedTotal);
            out.put("found", foundTotal);
            out.put("recall", recall);
            out.put("fullPass", fullPass);
            out.put("mustFail", mustFail);
            out.put("ms", ms);
            out.put("details", rows);
            return out;
        } finally {
            busy.set(false);
        }
    }

    /* ---------- CRUD вопросов и история прогонов ---------- */

    public List<Question> questions() {
        return jdbc.query("""
                SELECT question_id, question, classifier, expect_files, expect_must, note
                FROM dbo.rag_eval_question ORDER BY question_id
                """, (rs, i) -> new Question(rs.getInt(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6)));
    }

    public int addQuestion(String question, String classifier, String expectFiles,
                           String expectMust, String note) {
        return jdbc.queryForObject("""
                INSERT INTO dbo.rag_eval_question (question, classifier, expect_files, expect_must, note)
                OUTPUT INSERTED.question_id
                VALUES (?, ?, ?, ?, ?)
                """, Integer.class, question, classifier, expectFiles, expectMust, note);
    }

    public void deleteQuestion(int id) {
        jdbc.update("DELETE FROM dbo.rag_eval_question WHERE question_id = ?", id);
    }

    public List<Map<String, Object>> runs(int limit) {
        return jdbc.query("""
                SELECT TOP (?) run_id, at, label, questions, expected, found, recall, full_pass, ms
                FROM dbo.rag_eval_run ORDER BY run_id DESC
                """, (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("runId", rs.getInt(1));
            m.put("at", rs.getTimestamp(2).toString());
            m.put("label", nz(rs.getString(3)));
            m.put("questions", rs.getInt(4));
            m.put("expected", rs.getInt(5));
            m.put("found", rs.getInt(6));
            m.put("recall", rs.getDouble(7));
            m.put("fullPass", rs.getInt(8));
            m.put("ms", rs.getInt(9));
            return m;
        }, limit);
    }

    /** Детали старого прогона (разбор details-JSON из БД). */
    public Map<String, Object> runDetails(int runId) {
        List<String> raw = jdbc.queryForList(
                "SELECT details FROM dbo.rag_eval_run WHERE run_id = ?", String.class, runId);
        if (raw.isEmpty() || raw.getFirst() == null)
            throw new IllegalArgumentException("прогона с run_id=" + runId + " нет");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", runId);
        out.put("details", JSON.readValue(raw.getFirst(), List.class));
        return out;
    }

    /* ---------- внутреннее ---------- */

    private List<Question> loadQuestions() {
        return jdbc.query("""
                SELECT question_id, question, classifier, expect_files, expect_must, note
                FROM dbo.rag_eval_question WHERE enabled = 1 ORDER BY question_id
                """, (rs, i) -> new Question(rs.getInt(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6)));
    }

    /** Ранг первого файла выдачи, содержащего ожидаемое имя; 0 — не найден. */
    private static int rankOf(List<AskService.Retrieved> top, String expect) {
        String e = norm(expect);
        for (int i = 0; i < top.size(); i++) {
            if (norm(top.get(i).fileName()).contains(e)) return i + 1;
        }
        return 0;
    }

    /** Все must-слова присутствуют в контексте (регистр/ё/пробелы не важны). */
    private static boolean mustIn(String context, String expectMust) {
        if (expectMust == null || expectMust.isBlank()) return true;
        String c = norm(context);
        return split(expectMust).stream().allMatch(w -> c.contains(norm(w)));
    }

    private static List<String> split(String semicolon) {
        if (semicolon == null || semicolon.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String s : semicolon.split(";")) {
            if (!s.isBlank()) out.add(s.strip());
        }
        return out;
    }

    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replace('ё', 'е')
                .replaceAll("\\s+", " ").strip();
    }

    private static String nz(String s) {
        return s == null ? "" : s.strip();
    }
}