package kvo.itsnn.rag.ask;

import kvo.itsnn.rag.config.LlmProperties;
import kvo.itsnn.rag.config.RagProperties;
import kvo.itsnn.rag.index.VectorIndex;
import kvo.itsnn.rag.ingest.Embedder;
import kvo.itsnn.rag.llm.LlmClient;
import kvo.itsnn.rag.rerank.Reranker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * RAG: вопрос → векторный поиск чанков → реранк → промпт с контекстом → ответ LLM + источники.
 */
@Service
public class AskService {

    private static final Logger log = LoggerFactory.getLogger(AskService.class);
    private static final int MAX_CHUNK_CHARS = 1200;
    private static final int MAX_CONTEXT_CHARS = 15_000;
    /** До стольких чанков на один документ: сравнительные вопросы («чем в.6 отличается от в.4»)
     *  и вопросы по конкретному документу требуют несколько фрагментов из одного файла. */
    private static final int MAX_CHUNKS_PER_DOC = 3;
    /** Резервный порог «мусора», когда rag.rerank.min-score не задан или 0 (фильтр выключен):
     *  вторая волна реранка и WARN работают и без фильтра. */
    private static final double DEFAULT_GARBAGE = 0.05;

    private final LlmProperties cfg;
    private final RagProperties props;      // queryPrefix для эмбеддинга запроса
    private final Embedder embedder;        // интерфейс, как в SearchController
    private final VectorIndex index;
    private final JdbcTemplate jdbc;
    private final LlmClient llm;
    private final SystemPromptProvider prompts;   // system-промпт из rag_control
    private final CompareService compare;         // D: сценарий «сравнение версий»
    private final DocSummaryService summary;      // E: сценарий «краткое содержание документа»
    private final Reranker reranker;              // стадия-2 поиска: кросс-энкодер после грубого отбора Chroma

    public AskService(LlmProperties cfg, RagProperties props, Embedder embedder,
                      VectorIndex index, JdbcTemplate jdbc, LlmClient llm,
                      SystemPromptProvider prompts, CompareService compare,
                      DocSummaryService summary, Reranker reranker) {
        this.cfg = cfg;
        this.props = props;
        this.embedder = embedder;
        this.index = index;
        this.jdbc = jdbc;
        this.llm = llm;
        this.prompts = prompts;
        this.compare = compare;
        this.summary = summary;
        this.reranker = reranker;
        log.info("AskService: candidateChunks={}, contextChunks={}, queryPrefix='{}', rerank={}",
                cfg.candidateChunks(), cfg.contextChunks(), props.embedding().queryPrefix(),
                reranker.enabled() ? reranker.model() : "off");
    }

    /** url — ссылка на карточку файла: rag.ui.doc-card-url + file_id (= rag_document_ref.row_id). */
    public record Source(String documentGuid, String fileName, int chunkNo, double score, String url) {}

    public record AskResult(String answer, List<Source> sources) {
    }
    /** Строка выдачи retrieve для eval/диагностики. text — исходный чанк (для expect_must). */
    public record Retrieved(String documentGuid, String fileName, int chunkNo, double score, String text) {}
    /** Полный путь поиска без вызова LLM: тот же retrieve(), что и в ask(). */
    public List<Retrieved> retrieveTop(String query, String classifier) {
        return retrieve(query, classifier).stream()
                .map(h -> new Retrieved(h.documentGuid(), h.fileName(), h.chunkNo(), h.score(), h.text()))
                .toList();
    }
    /**
     * Аналог строки выдачи /api/rag/search, но записью.
     */
    private record ChunkHit(String documentGuid, String fileName, int chunkNo, double score,
                            String text, String rowId) {}

    public AskResult ask(String query, List<LlmClient.Message> history) {
        return ask(query, null, history);
    }

    public AskResult ask(String query, String classifier, List<LlmClient.Message> history) {
        // D: вопрос-сравнение версий идёт отдельным сценарием; не сработал детектор — обычный путь
        Optional<AskResult> cmp = compare.tryCompare(query, classifier);
        if (cmp.isPresent()) return cmp.get();

        // E: «краткое содержание <код> [версия N]» — конспект по началу файла, не по векторам
        Optional<AskResult> sum = summary.trySummary(query, classifier);
        if (sum.isPresent()) return sum.get();

        List<ChunkHit> best = retrieve(query, classifier);
        if (best.isEmpty()) {
            log.warn("RAG: retrieve пустой для '{}'", query);
            String scope = classifier == null || classifier.isBlank()
                    ? "" : " по признаку «" + classifier + "»";
            return new AskResult("По вашему вопросу" + scope + " не нашлось документов в базе знаний. "
                    + "Попробуйте переформулировать запрос"
                    + (scope.isEmpty() ? "" : " или выбрать «Все документы»") + ".", List.of());
        }

        StringBuilder ctx = new StringBuilder();
        int total = 0;
        for (int i = 0; i < best.size(); i++) {
            ChunkHit h = best.get(i);
            String text = h.text().length() > MAX_CHUNK_CHARS
                    ? h.text().substring(0, MAX_CHUNK_CHARS) + "…" : h.text();
            String block = "[" + (i + 1) + "] (" + h.fileName()
                    + ", фрагмент " + h.chunkNo() + ")\n" + text + "\n\n";
            if (total + block.length() > MAX_CONTEXT_CHARS) break;
            ctx.append(block);
            total += block.length();
        }

        String user = "Контекст из базы знаний:\n" + ctx + "\nВопрос: " + query.strip();
        List<LlmClient.Message> messages = new ArrayList<>();
        messages.add(LlmClient.Message.system(prompts.prompt()));
        messages.addAll(history);
        messages.add(LlmClient.Message.user(user));

        long t0 = System.currentTimeMillis();
        String answer = llm.chat(messages);
        log.info("RAG: вопрос='{}', чанков={}, LLM {} мс", query, best.size(), System.currentTimeMillis() - t0);

        List<Source> sources = best.stream()
                .map(h -> new Source(h.documentGuid(), h.fileName(), h.chunkNo(), h.score(),
                        props.docCardUrl(h.rowId())))
                .toList();
        return new AskResult(answer, sources);
    }

    /**
     * Как в SearchController: вектор запроса (с query-префиксом e5) → Chroma →
     * тексты чанков из MSSQL → дедуп по документу (до 3 чанков) → первые contextChunks.
     * row_id в rag_embedding нет (там только chunk_id/model/dim/embedding) —
     * GUID для ссылки DocsVision берём прямо из rag_chunk.document_guid.
     */
    private List<ChunkHit> retrieve(String query, String classifier) {
        float[] q = embedder.embedAll(List.of(props.embedding().queryPrefix() + query)).get(0);
        List<VectorIndex.Hit> candidates = index.search(q, cfg.candidateChunks(), classifier);
        if (candidates.isEmpty()) return List.of();
        candidates.sort(Comparator.comparingDouble(VectorIndex.Hit::score).reversed());

        Map<Long, Double> scoreOf = new HashMap<>();
        candidates.forEach(h -> scoreOf.put(h.chunkId(), h.score()));

        String in = String.join(",", Collections.nCopies(candidates.size(), "?"));
        List<ChunkHit> ordered = new ArrayList<>(candidates.size());   // effectively final: захватывается лямбдой ниже
        jdbc.query("SELECT chunk_id, document_guid, file_name, chunk_no, content, file_id " +
                        "FROM dbo.rag_chunk WHERE chunk_id IN (" + in + ")",
                rs -> {
                    while (rs.next()) {
                        long id = rs.getLong("chunk_id");
                        ordered.add(new ChunkHit(rs.getString("document_guid"), rs.getString("file_name"),
                                rs.getInt("chunk_no"), scoreOf.getOrDefault(id, 0.0), rs.getString("content"),
                                rs.getString("file_id")));
                    }
                },
                candidates.stream().map(VectorIndex.Hit::chunkId).toArray());
        log.info("retrieve: строк из MSSQL={}", ordered.size());
        ordered.sort(Comparator.comparingDouble(ChunkHit::score).reversed());   // порядок Chroma, а не SQL
        // стадия-2: реранк возвращает НОВЫЙ список, ссылку ordered не трогаем — лямбда выше остаётся валидной
        List<ChunkHit> reranked = rerank(query, ordered);
        List<ChunkHit> out = new ArrayList<>(cfg.contextChunks());
        Map<String, Integer> perDoc = new HashMap<>();          // B: до MAX_CHUNKS_PER_DOC чанков на документ
        for (ChunkHit h : reranked) {
            int n = perDoc.merge(h.documentGuid(), 1, Integer::sum);
            if (n <= MAX_CHUNKS_PER_DOC) out.add(h);
            if (out.size() == cfg.contextChunks()) break;
        }
        log.info("retrieve: после дедупа={}", out.size());
        return out;
    }

    /**
     * Стадия-2 поиска: кросс-энкодер оценивает пары «вопрос, фрагмент» и переупорядочивает
     * кандидатов — слабый косинус e5-small уступает место точному скору.
     * Адаптивная глубина: сначала верх по косинусу (passages пар); если лучший скор мусорный,
     * а ниже по косинусу кандидаты ещё есть — вторая волна (следующие passages пар).
     * Порог «мусора» один на все механизмы — rag.rerank.min-score (см. garbageThreshold()).
     * Реранкер выключен или упал — остаёмся на порядке Chroma: опциональное качество
     * не должно ломать ответы.
     */
    private List<ChunkHit> rerank(String query, List<ChunkHit> hits) {
        if (!reranker.enabled() || hits.size() < 2) return hits;
        int limit = Math.min(hits.size(), maxRerankPassages());
        long t0 = System.currentTimeMillis();
        int pairs = limit;
        double garbage = garbageThreshold();
        List<ChunkHit> out;
        try {
            out = scored(query, hits.subList(0, limit));               // верх по косинусу
            if (hits.size() > limit && (out.isEmpty() || out.get(0).score() < garbage)) {
                int to = Math.min(hits.size(), limit * 2);             // кандидаты 17..32 по косинусу
                log.info("retrieve: топ-1 ниже {} — вторая волна реранка (кандидаты {}..{})",
                        garbage, limit, to);
                out.addAll(scored(query, hits.subList(limit, to)));
                out.sort(Comparator.comparingDouble(ChunkHit::score).reversed());
                pairs = to;
            }
        } catch (Exception e) {
            log.warn("retrieve: реранк не удался ({}) — оставляем порядок Chroma", e.getMessage());
            return hits;
        }
        List<String> top3 = new ArrayList<>(3);
        for (int i = 0; i < Math.min(3, out.size()); i++) {
            top3.add(String.format(Locale.ROOT, "%.3f", out.get(i).score()));
        }
        log.info("retrieve: реранк {} пар за {} мс ({}), топ-3 скора: {}",
                pairs, System.currentTimeMillis() - t0, reranker.model(), String.join(", ", top3));
        if (!out.isEmpty() && out.get(0).score() < garbage) {
            log.warn("retrieve: лучший скор реранка {} — релевантных фрагментов в кандидатах нет "
                    + "(проверьте /api/rag/search этим же вопросом)", top3.get(0));
        }
        double min = props.rerank() == null ? 0.0 : props.rerank().minScore();
        if (min > 0) {
            int before = out.size();
            out.removeIf(h -> h.score() < min);
            if (out.size() < before) {
                log.info("retrieve: порог реранка {} отсёк {} слабых фрагментов (осталось {})",
                        min, before - out.size(), out.size());
            }
        }
        return out;
    }

    /** Пары «вопрос, фрагмент» → скоры кросс-энкодера → новый список по убыванию скора. */
    private List<ChunkHit> scored(String query, List<ChunkHit> list) {
        List<Double> scores = reranker.score(query, list.stream().map(ChunkHit::text).toList());
        List<ChunkHit> out = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            ChunkHit h = list.get(i);
            out.add(new ChunkHit(h.documentGuid(), h.fileName(), h.chunkNo(),
                    scores.get(i), h.text(), h.rowId()));    // скор реранкера идёт и в источники
        }
        out.sort(Comparator.comparingDouble(ChunkHit::score).reversed());
        return out;
    }

    /** Порог «мусора»: единый источник — rag.rerank.min-score. Значение 0 (фильтр выключен)
     *  или секция rerank не задана → встроенный дефолт: вторая волна и WARN работают всегда. */
    private double garbageThreshold() {
        RagProperties.Rerank r = props.rerank();
        return r == null || r.minScore() <= 0 ? DEFAULT_GARBAGE : r.minScore();
    }

    private int maxRerankPassages() {
        return props.rerank() == null ? 32 : Math.max(2, props.rerank().passages());
    }
}