package kvo.itsnn.rag.web;

import kvo.itsnn.rag.ask.ComparePromptProvider;
import kvo.itsnn.rag.ask.DocSummaryService;
import kvo.itsnn.rag.ask.SystemPromptProvider;
import kvo.itsnn.rag.config.RagProperties;
import kvo.itsnn.rag.ingest.Embedder;
import kvo.itsnn.rag.llm.LlmClient;
import kvo.itsnn.rag.refsync.RagScheduler;
import kvo.itsnn.rag.refsync.RefRepository;
import kvo.itsnn.rag.rerank.Reranker;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import kvo.itsnn.rag.index.VectorIndex;
import kvo.itsnn.rag.ask.DirectChatPromptProvider;

import kvo.itsnn.rag.extract.ExtractPromptProvider;

import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.*;

@RestController
@RequestMapping("/api/rag/admin")
public class AdminController {

    private static final String RESULT = "result";
    private static final String SAVED = "saved";
    private static final String KEY = "key";
    private static final String NIGHT_START = "nightStart";
    private static final String NIGHT_END = "nightEnd";
    private static final String OK = "ok";
    private static final String ERROR = "error";
    private static final String MODEL = "model";
    private static final String ENDPOINT = "endpoint";
    private static final String MS = "ms";
    private static final tools.jackson.databind.ObjectMapper JSON =
            tools.jackson.databind.json.JsonMapper.builder().build();
    private final Reranker reranker;

    /** Вкладка «Промты»: редактируемые system-промпты (ключ rag_control → подпись). */
    private record PromptDef(String key, String title) {}
    private static final List<PromptDef> PROMPTS = List.of(
            new PromptDef(SystemPromptProvider.CTRL_KEY, "Системный — обычные вопросы"),
            new PromptDef(ComparePromptProvider.CTRL_KEY, "Сравнение — отличия версий"),
            new PromptDef(DocSummaryService.CTRL_KEY, "Суммарный — краткое содержание"),
            new PromptDef(DirectChatPromptProvider.CTRL_KEY, "Свободный чат — без базы знаний"),
            new PromptDef(ExtractPromptProvider.CTRL_KEY_PROMPT, "Извлечение — промпт (файл → JSON)"),
            new PromptDef(ExtractPromptProvider.CTRL_KEY_SCHEMA, "Извлечение — JSON-структура"));

    private final RefRepository refs;
    private final VectorIndex index;
    private final JdbcTemplate jdbc;
    private final Embedder embedder;
    private final LlmClient llm;
    private final RagProperties props;
    private final RagScheduler scheduler;

    public AdminController(Reranker reranker, RefRepository refs, VectorIndex index,
                           JdbcTemplate jdbc, Embedder embedder,
                           LlmClient llm, RagProperties props, RagScheduler scheduler) {
        this.reranker = reranker;
        this.refs = refs;
        this.index = index;
        this.jdbc = jdbc;
        this.embedder = embedder;
        this.llm = llm;
        this.props = props;
        this.scheduler = scheduler;
    }

    /** Самопроверка реранкера: скоринг релевантной и нерелевантной пары. */
    @GetMapping("/rerank-selftest")
    public Map<String, Object> rerankSelftest() {
        long t0 = System.currentTimeMillis();
        if (!reranker.enabled()) {
            return Map.of(OK, false, "enabled", false, MODEL, "-",
                    ERROR, "реранкер выключен (rag.rerank.enabled=false)", MS, 0);
        }
        try {
            List<Double> s = reranker.score("Чем отличается версия 6.0 от 4.0?", List.of(
                    "В версии 6.0 добавлен раздел «Требования охраны труда», в версии 4.0 его нет.",
                    "Погода в мае была тёплой, дожди прошли в конце месяца."));
            return Map.of(OK, true, "enabled", true, MODEL, reranker.model(),
                    "answer", String.format(Locale.ROOT,
                            "скор: %.3f / %.3f (релевантный фрагмент должен быть заметно выше)",
                            s.get(0), s.get(1)),
                    MS, System.currentTimeMillis() - t0);
        } catch (Exception e) {
            return Map.of(OK, false, "enabled", true, MODEL, String.valueOf(reranker.model()),
                    ERROR, String.valueOf(e.getMessage()), MS, System.currentTimeMillis() - t0);
        }
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>(refs.statusCounts());
        out.put("indexSize", index.size());
        out.put("paused", refs.ingestPaused());
        out.put("nightly", refs.loadNightlyCheck());   // null → в UI «ещё не выполнялась»
        return out;
    }

    /** Текущее ночное окно (rag_control; резерв — yaml) и признак «сейчас ночь». */
    @GetMapping("/night-window")
    public Map<String, Object> nightWindow() {
        LocalTime start = refs.ctrlTime(RefRepository.NIGHT_START_KEY, props.nightStart());
        LocalTime end = refs.ctrlTime(RefRepository.NIGHT_END_KEY, props.nightEnd());
        LocalTime now = LocalTime.now();
        boolean night = !now.isBefore(start) || now.isBefore(end);
        return Map.of(NIGHT_START, start.toString(), NIGHT_END, end.toString(), "night", night);
    }

    public record NightWindowRequest(String nightStart, String nightEnd) {}

    /** Сохранение окна из админ-панели; применяется ближайшим тиком планировщика. */
    @PostMapping("/night-window")
    public Map<String, Object> setNightWindow(@RequestBody NightWindowRequest req) {
        LocalTime start = parseTime(req.nightStart());
        LocalTime end = parseTime(req.nightEnd());
        if (start == null || end == null)
            throw new IllegalArgumentException("время должно быть в формате HH:mm, например 23:00");
        if (start.equals(end))
            throw new IllegalArgumentException("начало и конец окна не должны совпадать");
        refs.setNightWindow(start, end);
        return Map.of(RESULT, SAVED, NIGHT_START, start.toString(), NIGHT_END, end.toString());
    }

    private static LocalTime parseTime(String s) {
        if (s == null) return null;
        try {
            return LocalTime.parse(s.strip());
        } catch (DateTimeParseException e) {
            return null;
        }
    }
    /** Текущие настройки скорости (rag_control; резерв — дефолты кода). */
    @GetMapping("/settings")
    public Map<String, Object> schedulerSettings() {
        return Map.of(
                "pollIntervalSec", refs.ctrlInt(RefRepository.POLL_INTERVAL_KEY, (int) props.pollInterval().toSeconds()),
                "ingestBatchDay", refs.ctrlInt(RefRepository.INGEST_BATCH_DAY_KEY, props.ingestBatchDay()),
                "ingestBatchNight", refs.ctrlInt(RefRepository.INGEST_BATCH_NIGHT_KEY, props.ingestBatchNight()),
                "dayHourLimit", refs.ctrlInt(RefRepository.DAY_HOUR_LIMIT_KEY, props.dayHourLimit()));
    }
    public record SettingsRequest(Integer pollIntervalSec, Integer ingestBatchDay,
                                  Integer ingestBatchNight, Integer dayHourLimit) {}
    @PostMapping("/settings")
    public Map<String, Object> setSchedulerSettings(@RequestBody SettingsRequest req) {
        int poll = intInRange(req.pollIntervalSec(), "Опрос (сек)", 1, 600);
        int day = intInRange(req.ingestBatchDay(), "Batch день", 1, 100);
        int night = intInRange(req.ingestBatchNight(), "Batch ночь", 1, 100);
        int limit = intInRange(req.dayHourLimit(), "Лимит днём (карт/час)", 1, 10_000);
        refs.setCtrlInt(RefRepository.POLL_INTERVAL_KEY, poll);
        refs.setCtrlInt(RefRepository.INGEST_BATCH_DAY_KEY, day);
        refs.setCtrlInt(RefRepository.INGEST_BATCH_NIGHT_KEY, night);
        refs.setCtrlInt(RefRepository.DAY_HOUR_LIMIT_KEY, limit);
        return Map.of(RESULT, SAVED, "pollIntervalSec", poll, "ingestBatchDay", day,
                "ingestBatchNight", night, "dayHourLimit", limit);
    }
    private static int intInRange(Integer v, String name, int min, int max) {
        if (v == null) throw new IllegalArgumentException("не задано: " + name);
        if (v < min || v > max) throw new IllegalArgumentException(name + " — допустимо от " + min + " до " + max);
        return v;
    }
    @PostMapping("/reindex/{guid}")
    public Map<String, String> reindex(@PathVariable UUID guid) {
        refs.forceReindex(guid);
        return Map.of(RESULT, "queued");
    }

    @PostMapping("/rebuild")
    public Map<String, String> rebuild() {
        // пауза на время пересборки: переиндексация документа во время reload
        // может вернуть в Chroma только что удалённые id (снова diff)
        boolean wasPaused = refs.ingestPaused();
        if (!wasPaused) refs.setPaused(true);
        try {
            index.reload();            // пересинхронизация Chroma из резервной копии в MSSQL
        } finally {
            if (!wasPaused) refs.setPaused(false);
        }
        return Map.of(RESULT, "resynced", "size", String.valueOf(index.size()));
    }
    /** Ручная сверка Chroma↔MSSQL — то же, что ночная в 03:30.
     *  Возвращает свежий nightly_embedding; при расхождении гоняет reload (может занять минуты). */
    @PostMapping("/nightly-check")
    public Map<String, Object> nightlyCheck() {
        boolean ran = scheduler.nightlyConsistency();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(RESULT, ran ? "done" : "busy");
        out.put("nightly", refs.loadNightlyCheck());
        return out;
    }
    @GetMapping("/errors")
    public List<Map<String, Object>> errors() {
        return jdbc.query("""
                SELECT TOP (20) document_guid, retry_count,
                       LEFT(error_msg, 300) AS error, updated_at
                FROM dbo.rag_document_ref
                WHERE status = 'E' OR error_msg IS NOT NULL
                ORDER BY updated_at DESC
                """, (rs, i) -> Map.of(
                "guid", rs.getString(1),
                "retries", rs.getInt(2),
                ERROR, rs.getString(3) == null ? "" : rs.getString(3),
                "at", rs.getTimestamp(4).toString()));
    }

    /** Список промптов с текущими значениями из БД (пусто — работает дефолт из кода). */
    @GetMapping("/prompts")
    public List<Map<String, Object>> prompts() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PromptDef p : PROMPTS) {
            List<String> v = jdbc.queryForList(
                    "SELECT ctrl_value FROM dbo.rag_control WHERE ctrl_key = ?", String.class, p.key());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put(KEY, p.key());          // ← было m.put("key", p.key()) — просто литерал заменён константой
            m.put("title", p.title());
            m.put("value", v.isEmpty() || v.get(0) == null ? "" : v.get(0));
            out.add(m);
        }
        return out;
    }

    public record PromptRequest(String key, String value) {}

    /** Сохранение промпта; применяется к следующему вопросу без рестарта. */
    @PostMapping("/prompts")
    public Map<String, Object> savePrompt(@RequestBody PromptRequest req) {
        titleOf(req.key());
        String value = req.value() == null ? "" : req.value();
        if (value.isBlank()) throw new IllegalArgumentException("промпт пустой — используйте «Дефолт из кода»");
        if (ExtractPromptProvider.CTRL_KEY_SCHEMA.equals(req.key())) {   // схема обязана быть валидным JSON-объектом
            try {
                JSON.readValue(value, Map.class);
            } catch (Exception e) {
                throw new IllegalArgumentException(
                        "JSON-структура — не валидный JSON-объект: " + e.getMessage());
            }
        }
        jdbc.update("""
                MERGE dbo.rag_control WITH (HOLDLOCK) AS t
                USING (SELECT ? AS k) AS s ON t.ctrl_key = s.k
                WHEN MATCHED THEN UPDATE SET t.ctrl_value = ?
                WHEN NOT MATCHED THEN INSERT (ctrl_key, ctrl_value) VALUES (s.k, ?);
                """, req.key(), value, value);
        return Map.of(RESULT, SAVED, KEY, req.key(), "chars", value.length());
    }

    /** Сброс: строка удаляется — провайдер тут же берёт DEFAULT из кода,
     *  а следующий рестарт просеет дефолт в таблицу заново. */
    @PostMapping("/prompts/reset")
    public Map<String, String> resetPrompt(@RequestBody PromptRequest req) {
        titleOf(req.key());
        jdbc.update("DELETE FROM dbo.rag_control WHERE ctrl_key = ?", req.key());
        return Map.of(RESULT, "default", KEY, req.key());
    }

    /** Подпись по ключу; IllegalArgumentException — ключ не из списка. */
    private static String titleOf(String key) {
        if (key != null) {
            for (PromptDef p : PROMPTS) {
                if (p.key().equals(key)) return p.title();
            }
        }
        throw new IllegalArgumentException("неизвестный промпт: " + key);
    }

    @GetMapping("/selftest")
    public Map<String, Object> selftest() {
        long t0 = System.currentTimeMillis();
        try {
            float[] v = embedder.embedAll(List.of("проверка связи")).get(0);
            return Map.of(OK, true, ENDPOINT, embedder.endpoint(),
                    MODEL, embedder.model(), "dims", v.length,
                    MS, System.currentTimeMillis() - t0);
        } catch (Exception e) {
            return Map.of(OK, false, ENDPOINT, embedder.endpoint(),
                    MODEL, embedder.model(),
                    ERROR, String.valueOf(e.getMessage()),
                    MS, System.currentTimeMillis() - t0);
        }
    }

    @GetMapping("/llm-selftest")
    public Map<String, Object> llmSelftest() {
        long t0 = System.currentTimeMillis();
        try {
            String answer = llm.chat(List.of(LlmClient.Message.user("Ответь одним словом: связь.")));
            return Map.of(OK, true, "answer", answer,
                    MS, System.currentTimeMillis() - t0);
        } catch (Exception e) {
            return Map.of(OK, false, ERROR, String.valueOf(e.getMessage()),
                    MS, System.currentTimeMillis() - t0);
        }
    }

    @PostMapping("/pause")
    public Map<String, String> pause() {
        refs.setPaused(true);
        return Map.of(RESULT, "paused");
    }

    @PostMapping("/resume")
    public Map<String, String> resume() {
        refs.setPaused(false);
        return Map.of(RESULT, "resumed");
    }
}