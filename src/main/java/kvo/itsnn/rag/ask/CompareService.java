package kvo.itsnn.rag.ask;

import kvo.itsnn.rag.config.LlmProperties;
import kvo.itsnn.rag.config.RagProperties;
import kvo.itsnn.rag.ingest.Embedder;
import kvo.itsnn.rag.llm.LlmClient;
import kvo.itsnn.rag.util.VecMath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Сценарий «сравнение версий одного документа»: «чем СТО 19-ПЛ07.01 в.6 отличается от в.4».
 * Почему без Chroma: в её метаданных только document_guid и classifier, а фильтровать нужно
 * по ИМЕНИ файла (код + версия живут в file_name). Векторы продублированы в dbo.rag_embedding —
 * чанки обеих версий берём из rag_chunk, скорим в Java (dot по нормализованным векторам = косинус).
 * Условия срабатывания: в вопросе есть код документа (вида 19-ПЛ07.01) и ДВЕ разные версии
 * («версия 6.0», «в.4.0», «версии 4.0 и 6.0»). Не сработал детектор / нет кода — обычный RAG-путь.
 * Промпт сравнения — в rag_control (ключ compare_system_prompt), см. ComparePromptProvider.
 */
@Service
public class CompareService {

    private static final Logger log = LoggerFactory.getLogger(CompareService.class);

    private static final int MAX_CHUNK_CHARS = 1200;     // как в AskService
    private static final int MAX_CONTEXT_CHARS = 15_000;

    /* CANON_EQ: «№» и кириллица чувствительны к нормализации Юникода; флаг приравнивает
       разложенные формы («й» = и+U+0306 из копипасты PDF) к обычным при сопоставлении. */
    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
            | Pattern.UNICODE_CHARACTER_CLASS | Pattern.CANON_EQ;

    /** «версия 6.0», «версии 6,0», «в.6.0», «v.6.0», «редакция 3». Повтор дробной части
     *  ограничен {0,3} (до 4 компонентов номера) — ограниченный квантификатор компилируется
     *  без рекурсии, переполнение стека невозможно. */
    private static final Pattern VERSION = Pattern.compile(
            "(?:верси\\w*|редакци\\w*|\\b[вv]\\.)\\s*№?\\s*(\\d+(?:[.,]\\d+){0,3})", FLAGS);

    /** «отличия версии 6.0 от 4.0» — второе число без слова «версия»; только с дробной частью
     *  («от 3 дней» не матчится) и только если в вопросе есть слова сравнения. */
    private static final Pattern BARE_VERSION = Pattern.compile(
            "\\b(?:от|против|и)\\s*(\\d+[.,]\\d+)", FLAGS);

    private static final Pattern COMPARE_WORDS = Pattern.compile(
            "отлич|разниц|сравн|изменени|что измен|нов(ая|ой|ую)\\s+верси", FLAGS);

    /** Код документа вида 19-ПЛ07.01 / 12-ИТ3.2 (кириллица или латиница, любой дефис). */
    private static final Pattern DOC_CODE = Pattern.compile(
            "\\d{1,3}\\s*[-–]\\s*[А-ЯA-Z]{1,6}\\s*\\d{1,3}\\.\\d{1,2}", FLAGS);

    private final LlmProperties cfg;
    private final RagProperties props;
    private final Embedder embedder;
    private final JdbcTemplate jdbc;
    private final LlmClient llm;
    private final ComparePromptProvider prompts;   // промпт сравнения из rag_control

    public CompareService(LlmProperties cfg, RagProperties props, Embedder embedder,
                          JdbcTemplate jdbc, LlmClient llm, ComparePromptProvider prompts) {
        this.cfg = cfg;
        this.props = props;
        this.embedder = embedder;
        this.jdbc = jdbc;
        this.llm = llm;
        this.prompts = prompts;
        log.info("CompareService: enabled={}, фрагментов на версию={}",
                cfg.compareEnabled(), cfg.compareChunksPerSide());
    }

    /** Строка rag_chunk, попавшая в сравнение. */
    private record ChunkRow(String documentGuid, String fileName, int chunkNo, double score,
                            String content, String fileId) {}

    /** Что нашёл детектор в вопросе: сам вопрос, код документа и две версии. */
    private record Detected(String query, String code, List<String> versions) {}

    /** Собранный контекст сравнения: текст для LLM + источники для карточки ответа. */
    private record Context(String text, List<AskService.Source> sources) {}

    /** Пустой Optional — вопрос не сравнительный или данных нет: идём обычным RAG-путём. */
    public Optional<AskService.AskResult> tryCompare(String query, String classifier) {
        if (!cfg.compareEnabled()) return Optional.empty();

        Detected d = detect(query);
        if (d == null) return Optional.empty();

        Optional<float[]> q = embedQuery(query);
        if (q.isEmpty()) return Optional.empty();

        Map<String, List<ChunkRow>> byVer = loadSides(d, classifier, q.get());
        if (byVer.isEmpty()) return Optional.empty();            // код есть, но ни одной версии в базе
        if (byVer.size() == 1) return Optional.of(onlyOneVersion(d, byVer));   // одна версия есть, второй нет

        return Optional.of(compareTwo(d, byVer));
    }

    /** Детектор: код документа + две версии; null — вопрос не сравнительный. */
    private Detected detect(String query) {
        String code = docCode(query);
        List<String> vs = versions(query);
        if (code == null || vs.size() < 2) return null;
        if (vs.size() > 2) vs = vs.subList(0, 2);
        log.debug("compare: детектор сработал: код='{}', версии={}", code, vs);
        return new Detected(query, code, vs);
    }

    /** Вектор запроса (с e5-префиксом); пустой Optional — эмбеддер лёг, идём обычным путём. */
    private Optional<float[]> embedQuery(String query) {
        try {
            return Optional.of(embedder.embedAll(
                    List.of(props.embedding().queryPrefix() + query)).getFirst());
        } catch (Exception e) {
            log.warn("compare: эмбеддинг запроса не удался ({}), идём обычным путём", e.getMessage());
            return Optional.empty();
        }
    }

    /** Лучшие чанки каждой версии; пустые стороны отброшены. */
    private Map<String, List<ChunkRow>> loadSides(Detected d, String classifier, float[] q) {
        Map<String, List<ChunkRow>> byVer = new LinkedHashMap<>();
        for (String v : d.versions()) {
            byVer.put(v, topChunks(d.code(), v, classifier, q));
        }
        byVer.values().removeIf(List::isEmpty);
        return byVer;
    }

    /** В базе только одна из двух названных версий — честно отказываем, дав ссылки на найденную. */
    private AskService.AskResult onlyOneVersion(Detected d, Map<String, List<ChunkRow>> byVer) {
        String found = byVer.keySet().iterator().next();
        String missing = d.versions().get(0).equals(found) ? d.versions().get(1) : d.versions().get(0);
        log.info("COMPARE: {} — в базе только версия {}", d.code(), found);
        return new AskService.AskResult(
                "В базе знаний по документу " + d.code() + " найдена только версия " + found
                        + "; версия " + missing + " отсутствует — сравнить версии не могу. "
                        + "Фрагменты найденной версии — в источниках ниже.",
                sourcesOf(byVer.get(found)));
    }

    /** Основная ветка: обе версии есть — собрать контекст и спросить LLM. */
    private AskService.AskResult compareTwo(Detected d, Map<String, List<ChunkRow>> byVer) {
        List<String> order = new ArrayList<>(byVer.keySet());         // какая версия новее
        order.sort(Comparator.comparingDouble(CompareService::versionKey).reversed());
        String newer = order.get(0);
        String older = order.get(1);
        Context ctx = buildContext(newer, older, byVer);
        return askLlm(d, newer, older, byVer, ctx);
    }

    /** Контекст: секция новой версии, затем предыдущей, сквозная нумерация [1..n],
     *  общий бюджет MAX_CONTEXT_CHARS. */
    private Context buildContext(String newer, String older, Map<String, List<ChunkRow>> byVer) {
        StringBuilder ctx = new StringBuilder();
        List<AskService.Source> sources = new ArrayList<>();
        int[] n = {0};                                   // сквозной счётчик фрагментов
        int total = appendSide(ctx, sources, byVer.get(newer),
                "=== ВЕРСИЯ " + newer + " (новая) ===", 0, n);
        appendSide(ctx, sources, byVer.get(older),
                "=== ВЕРСИЯ " + older + " (предыдущая) ===", total, n);
        return new Context(ctx.toString(), sources);
    }

    /** Одна секция версии: заголовок + чанки, пока влезает в бюджет. Возвращает новый бюджет. */
    private int appendSide(StringBuilder ctx, List<AskService.Source> sources, List<ChunkRow> rows,
                           String header, int total, int[] n) {
        String head = header + "\n";
        if (total + head.length() > MAX_CONTEXT_CHARS) return total;
        ctx.append(head);
        total += head.length();
        for (ChunkRow r : rows) {
            String block = "[" + (++n[0]) + "] (" + r.fileName() + ", фрагмент " + r.chunkNo() + ")\n"
                    + r.content() + "\n\n";
            if (total + block.length() > MAX_CONTEXT_CHARS) return total;
            ctx.append(block);
            total += block.length();
            sources.add(new AskService.Source(r.documentGuid(), r.fileName(), r.chunkNo(),
                    r.score(), props.docCardUrl(r.fileId())));
        }
        return total;
    }

    /** Промпт сравнения → LLM → AskResult с источниками обеих версий. */
    private AskService.AskResult askLlm(Detected d, String newer, String older,
                                        Map<String, List<ChunkRow>> byVer, Context ctx) {
        String user = "Документ: " + d.code() + ". Новая версия: " + newer
                + ", предыдущая: " + older + ".\n\n" + ctx.text() + "\nВопрос: " + d.query().strip();
        long t0 = System.currentTimeMillis();
        String answer = llm.chat(List.of(LlmClient.Message.system(prompts.prompt()),
                LlmClient.Message.user(user)));
        log.info("COMPARE: {} {} vs {}: {}+{} фрагментов, LLM {} мс",
                d.code(), newer, older, byVer.get(newer).size(), byVer.get(older).size(),
                System.currentTimeMillis() - t0);
        return new AskService.AskResult(answer, ctx.sources());
    }

    /**
     * Лучшие чанки одной версии: файлы, в чьём имени есть и код, и версия
     * («6.0» не матчит «16.0»/«6.01» — граница по не-цифре). Копии одного файла
     * (одно имя под разными file_id) не дублируем.
     */
    private List<ChunkRow> topChunks(String code, String version, String classifier, float[] q) {
        Pattern ver = Pattern.compile("(?<!\\d)" + Pattern.quote(version) + "(?!\\d)");
        boolean byClassifier = classifier != null && !classifier.isBlank();
        String sql = """
                SELECT c.document_guid, c.file_name, c.chunk_no, LEFT(c.content, ?) AS content,
                       c.file_id, e.embedding
                FROM dbo.rag_chunk c
                JOIN dbo.rag_document_ref r ON r.document_guid = c.document_guid AND r.row_id = c.file_id
                JOIN dbo.rag_embedding e ON e.chunk_id = c.chunk_id AND e.model = ?
                WHERE r.status = 'I' AND c.file_name LIKE ?
                """ + (byClassifier ? " AND r.classifier = ?" : "");

        List<Object> args = new ArrayList<>(List.of(MAX_CHUNK_CHARS, embedder.model(), "%" + code + "%"));
        if (byClassifier) args.add(classifier);

        List<ChunkRow> all = new ArrayList<>();
        Map<String, String> fileOf = new HashMap<>();     // имя файла (lower) → file_id первого встреченного
        jdbc.query(sql, rs -> {
            String name = rs.getString("file_name");
            if (name == null || !ver.matcher(name).find()) return;   // версия — в имени файла
            String fileId = rs.getString("file_id");
            String owner = fileOf.putIfAbsent(name.toLowerCase(Locale.ROOT), fileId);
            if (owner != null && !owner.equals(fileId)) return;      // копия того же файла — мимо
            float[] v = VecMath.fromLittleEndian(rs.getBytes("embedding"));
            all.add(new ChunkRow(rs.getString("document_guid"), name, rs.getInt("chunk_no"),
                    VecMath.dot(q, v), rs.getString("content"), fileId));
        }, args.toArray());

        all.sort(Comparator.comparingDouble(ChunkRow::score).reversed());
        return all.size() > cfg.compareChunksPerSide()
                ? new ArrayList<>(all.subList(0, cfg.compareChunksPerSide())) : all;
    }

    private List<AskService.Source> sourcesOf(List<ChunkRow> rows) {
        return rows.stream()
                .map(r -> new AskService.Source(r.documentGuid(), r.fileName(), r.chunkNo(),
                        r.score(), props.docCardUrl(r.fileId())))
                .toList();
    }

    private static String docCode(String query) {
        Matcher m = DOC_CODE.matcher(query);
        return m.find() ? m.group().replace("–", "-").replaceAll("\\s+", "") : null;
    }

    /** Номера версий из вопроса: оба с маркером («версия 6.0 … версия 4.0») или,
     *  если маркер один и есть слова сравнения, вторым — «от 4.0» / «и 4.0» с дробной частью. */
    private static List<String> versions(String query) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = VERSION.matcher(query);
        while (m.find()) out.add(m.group(1).replace(',', '.'));
        if (out.size() == 1 && COMPARE_WORDS.matcher(query).find()) {
            Matcher bare = BARE_VERSION.matcher(query);
            while (bare.find()) out.add(bare.group(1).replace(',', '.'));
        }
        return new ArrayList<>(out);
    }

    /** Ключ сортировки «новее»: 6.0 → 6000, 4.0 → 4000 (первые два компонента номера). */
    private static double versionKey(String v) {
        String[] p = v.split("\\.");
        double d = p.length > 0 ? Integer.parseInt(p[0]) * 1000.0 : 0;
        if (p.length > 1) d += Integer.parseInt(p[1]);
        return d;
    }
}