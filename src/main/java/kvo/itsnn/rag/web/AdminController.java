package kvo.itsnn.rag.web;

import kvo.itsnn.rag.ingest.Embedder;
import kvo.itsnn.rag.refsync.RefRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import kvo.itsnn.rag.index.VectorIndex;

import java.util.*;

@RestController
@RequestMapping("/api/rag/admin")
public class AdminController {

    private final RefRepository refs;
    private final VectorIndex index;
    private final JdbcTemplate jdbc;
    private final Embedder embedder;

    public AdminController(RefRepository refs, VectorIndex index,
                           JdbcTemplate jdbc, Embedder embedder) {
        this.refs = refs; this.index = index; this.jdbc = jdbc; this.embedder = embedder;
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
        return Map.of("result", "queued");
    }

    @PostMapping("/rebuild")
    public Map<String, String> rebuild() {
        index.reload();                // пересинхронизация Chroma из резервной копии в MSSQL
        return Map.of("result", "resynced", "size", String.valueOf(index.size()));
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
                "error", rs.getString(3) == null ? "" : rs.getString(3),
                "at", rs.getTimestamp(4).toString()));
    }

    /** Проверка эмбеддера без запуска индексации. */
    @GetMapping("/selftest")
    public Map<String, Object> selftest() {
        long t0 = System.currentTimeMillis();
        try {
            float[] v = embedder.embedAll(List.of("проверка связи")).get(0);
            return Map.of("ok", true, "endpoint", embedder.endpoint(),
                    "model", embedder.model(), "dims", v.length,
                    "ms", System.currentTimeMillis() - t0);
        } catch (Exception e) {
            return Map.of("ok", false, "endpoint", embedder.endpoint(),
                    "model", embedder.model(),
                    "error", String.valueOf(e.getMessage()),
                    "ms", System.currentTimeMillis() - t0);
        }
    }
}