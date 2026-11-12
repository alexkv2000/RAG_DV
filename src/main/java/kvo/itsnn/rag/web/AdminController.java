package kvo.itsnn.rag.web;

import kvo.itsnn.rag.ingest.Embedder;
import kvo.itsnn.rag.llm.LlmClient;
import kvo.itsnn.rag.refsync.RefRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import kvo.itsnn.rag.index.VectorIndex;

import java.util.*;

@RestController
@RequestMapping("/api/rag/admin")
public class AdminController {

    private static final String RESULT = "result";
    private static final String OK = "ok";
    private static final String ERROR = "error";
    private static final String MODEL = "model";
    private static final String ENDPOINT = "endpoint";
    private static final String MS = "ms";

    private final RefRepository refs;
    private final VectorIndex index;
    private final JdbcTemplate jdbc;
    private final Embedder embedder;
    private final LlmClient llm;

    public AdminController(RefRepository refs, VectorIndex index,
                           JdbcTemplate jdbc, Embedder embedder,
                           LlmClient llm) {
        this.refs = refs;
        this.index = index;
        this.jdbc = jdbc;
        this.embedder = embedder;
        this.llm = llm;
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>(refs.statusCounts());
        out.put("indexSize", index.size());
        return out;
    }

    @PostMapping("/reindex/{guid}")
    public Map<String, String> reindex(@PathVariable UUID guid) {
        refs.forceReindex(guid);
        return Map.of(RESULT, "queued");
    }

    @PostMapping("/rebuild")
    public Map<String, String> rebuild() {
        index.reload();                // пересинхронизация Chroma из резервной копии в MSSQL
        return Map.of(RESULT, "resynced", "size", String.valueOf(index.size()));
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

    /**
     * Проверка эмбеддера без запуска индексации.
     */
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

    /**
     * Проверка LLM-канала в отрыве от поиска: связь, время, сам ответ.
     */
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

    /**
     * пауза на весь кластер
     **/
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