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

    /** Запас кандидатов сверх topK: несколько файлов-копий одной карточки
     *  не должны съедать слоты выдачи. */
    private static final int CANDIDATE_FACTOR = 4;
    private static final int CANDIDATE_LIMIT  = 300;

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

        List<VectorIndex.Hit> candidates;
        try {
            candidates = index.search(q, Math.min(CANDIDATE_LIMIT, topK * CANDIDATE_FACTOR));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "ChromaDB недоступна: " + e.getMessage()));
        }

        // дедуп: один документ = один лучший чанк
        Map<Long, String> ownerOf = ownerMap(candidates);
        candidates.sort(Comparator.comparingDouble(VectorIndex.Hit::score).reversed());
        List<VectorIndex.Hit> hits = new ArrayList<>(topK);
        Set<String> seenDocs = new HashSet<>();
        for (VectorIndex.Hit h : candidates) {
            String doc = ownerOf.get(h.chunkId());
            if (doc == null || !seenDocs.add(doc)) continue;   // чанк исчез из MSSQL или документ уже в выдаче
            hits.add(h);
            if (hits.size() == topK) break;
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

    /** chunk_id -> document_guid одним запросом для всех кандидатов. */
    private Map<Long, String> ownerMap(List<VectorIndex.Hit> hits) {
        if (hits.isEmpty()) return Map.of();
        String in = String.join(",", Collections.nCopies(hits.size(), "?"));
        Map<Long, String> m = new HashMap<>();
        jdbc.query("SELECT chunk_id, document_guid FROM dbo.rag_chunk WHERE chunk_id IN (" + in + ")",
                rs -> { while (rs.next()) m.put(rs.getLong(1), rs.getString(2)); },
                hits.stream().map(VectorIndex.Hit::chunkId).toArray());
        return m;
    }
}