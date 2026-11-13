package kvo.itsnn.rag.ask;

import kvo.itsnn.rag.config.LlmProperties;
import kvo.itsnn.rag.config.RagProperties;
import kvo.itsnn.rag.index.VectorIndex;
import kvo.itsnn.rag.ingest.Embedder;
import kvo.itsnn.rag.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * RAG: вопрос → векторный поиск чанков → промпт с контекстом → ответ LLM + источники.
 */
@Service
public class AskService {

    private static final Logger log = LoggerFactory.getLogger(AskService.class);
    private static final int MAX_CHUNK_CHARS = 1200;
    private static final int MAX_CONTEXT_CHARS = 15_000;

    private final LlmProperties cfg;
    private final RagProperties props;      // queryPrefix для эмбеддинга запроса
    private final Embedder embedder;        // интерфейс, как в SearchController
    private final VectorIndex index;
    private final JdbcTemplate jdbc;
    private final LlmClient llm;
    private final SystemPromptProvider prompts;   // system-промпт из rag_control

    public AskService(LlmProperties cfg, RagProperties props, Embedder embedder,
                      VectorIndex index, JdbcTemplate jdbc, LlmClient llm,
                      SystemPromptProvider prompts) {
        this.cfg = cfg;
        this.props = props;
        this.embedder = embedder;
        this.index = index;
        this.jdbc = jdbc;
        this.llm = llm;
        this.prompts = prompts;
        log.info("AskService: candidateChunks={}, contextChunks={}, queryPrefix='{}'",
                cfg.candidateChunks(), cfg.contextChunks(), props.embedding().queryPrefix());
    }

    /** url — ссылка на карточку файла: rag.ui.doc-card-url + file_id (= rag_document_ref.row_id). */
    public record Source(String documentGuid, String fileName, int chunkNo, double score, String url) {}

    public record AskResult(String answer, List<Source> sources) {
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
     * тексты чанков из MSSQL → дедуп по документу → первые contextChunks.
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
        List<ChunkHit> ordered = new ArrayList<>(candidates.size());
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
        List<ChunkHit> out = new ArrayList<>(cfg.contextChunks());
        Set<String> seenDocs = new HashSet<>();
        for (ChunkHit h : ordered) {
            if (seenDocs.add(h.documentGuid())) out.add(h);   // один документ = один лучший чанк
            if (out.size() == cfg.contextChunks()) break;
        }
        log.info("retrieve: после дедупа={}", out.size());
        return out;
    }
}