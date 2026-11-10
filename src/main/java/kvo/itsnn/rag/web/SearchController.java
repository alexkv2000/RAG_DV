package kvo.itsnn.rag.web;

import kvo.itsnn.rag.config.RagProperties;
import kvo.itsnn.rag.index.VectorIndex;
import kvo.itsnn.rag.ingest.Embedder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/rag")
public class SearchController {

    private final VectorIndex index;
    private final Embedder embedder;
    private final JdbcTemplate jdbc;
    private final RagProperties props;

    public SearchController(VectorIndex index, Embedder embedder,
                            JdbcTemplate jdbc, RagProperties props) {
        this.index = index; this.embedder = embedder; this.jdbc = jdbc; this.props = props;
    }

    public record SearchRequest(String query, int topK) {}

    @PostMapping("/search")
    public ResponseEntity<?> search(@RequestBody SearchRequest req) {
        int topK = Math.clamp(req.topK() <= 0 ? 5 : req.topK(), 1, 50);
        float[] q;
        try {
            q = embedder.embedAll(List.of(props.embedding().queryPrefix() + req.query())).get(0);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "embedding недоступен: " + e.getMessage()));
        }

        List<VectorIndex.Hit> hits;
        try {
            hits = index.search(q, topK);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "ChromaDB недоступна: " + e.getMessage()));
        }

        List<Map<String, Object>> out = new ArrayList<>(hits.size());
        for (VectorIndex.Hit h : hits) {
            Map<String, Object> row = jdbc.queryForObject("""
                    SELECT c.document_guid, c.file_name, c.chunk_no, c.content
                    FROM dbo.rag_chunk c WHERE c.chunk_id = ?""",
                    (rs, rn) -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("chunkId", h.chunkId());
                        m.put("documentGuid", rs.getString(1));
                        m.put("fileName", rs.getString(2));
                        m.put("chunkNo", rs.getInt(3));
                        m.put("text", rs.getString(4));
                        m.put("score", h.score());
                        return m;
                    }, h.chunkId());
            if (row != null) out.add(row);
        }
        return ResponseEntity.ok(out);
    }
}