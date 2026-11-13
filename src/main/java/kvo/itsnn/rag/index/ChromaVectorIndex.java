package kvo.itsnn.rag.index;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import kvo.itsnn.rag.config.RagProperties;
import kvo.itsnn.rag.ingest.Embedder;
import kvo.itsnn.rag.util.VecMath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.*;

/**
 * Векторный индекс на ChromaDB (режим сервера, REST API v2).
 * MSSQL (rag_embedding) остаётся резервной копией векторов —
 * reload() пересинхронизирует Chroma из MSSQL.
 */
@Component
public class ChromaVectorIndex implements VectorIndex {

    private static final Logger log = LoggerFactory.getLogger(ChromaVectorIndex.class);
    private static final String API = "/api/v2/tenants/default_tenant/databases/default_database/collections";

    private final JdbcTemplate jdbc;
    private final Embedder embedder;
    private final RagProperties.Chroma cfg;
    private final String collectionName;
    private final RestClient http;
    private volatile String collectionId;

    public ChromaVectorIndex(JdbcTemplate jdbc, Embedder embedder, RagProperties props) {
        this.jdbc = jdbc;
        this.embedder = embedder;
        this.cfg = props.chroma();
        // отдельная коллекция на модель эмбеддинга — аналог "WHERE e.model = ?" старого индекса
        this.collectionName = cfg.collection() + "_" + embedder.model().replaceAll("[^A-Za-z0-9._-]", "_");
        RestClient.Builder b = RestClient.builder()
                .baseUrl(cfg.baseUrl().replaceAll("/+$", ""));
        if (cfg.token() != null && !cfg.token().isBlank())
            b.defaultHeader("Authorization", "Bearer " + cfg.token());
        this.http = b.build();
        log.info("ChromaDB: url='{}', коллекция='{}'", cfg.baseUrl(), collectionName);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStart() {                       // старт приложения не валится, если Chroma ещё не поднята
        try {
            ensureCollection();
        } catch (Exception e) {
            log.warn("ChromaDB недоступна при старте ({}): {}", cfg.baseUrl(), e.getMessage());
        }
    }

    private synchronized void ensureCollection() {
        if (collectionId != null) return;
        CollectionDto dto = http.post().uri(API)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", collectionName,
                        "get_or_create", true,
                        "metadata", Map.of("hnsw:space", "cosine")))
                .retrieve().body(CollectionDto.class);
        if (dto == null || dto.id() == null)
            throw new IllegalStateException("ChromaDB: не получена коллекция '" + collectionName + "'");
        collectionId = dto.id();
        log.info("ChromaDB: коллекция '{}' (id={})", collectionName, collectionId);
    }

    private String uri(String action) {
        return API + "/" + collectionId + "/" + action;
    }

    @Override
    public void addAll(List<Long> chunkIds, List<float[]> vectors, List<Map<String, String>> metadatas) {
        if (chunkIds.isEmpty()) return;
        ensureCollection();
        for (int from = 0; from < chunkIds.size(); from += cfg.batch()) {
            int to = Math.min(chunkIds.size(), from + cfg.batch());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ids", idsAsStrings(chunkIds.subList(from, to)));
            body.put("embeddings", vectors.subList(from, to));
            if (metadatas != null && !metadatas.isEmpty()) {
                body.put("metadatas", metadatas.subList(from, to));
            }
            http.post().uri(uri("upsert"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve().toBodilessEntity();
        }
    }

    @Override
    public void removeChunks(Collection<Long> ids) {
        if (ids.isEmpty()) return;
        ensureCollection();
        List<String> str = idsAsStrings(ids);
        for (int from = 0; from < str.size(); from += cfg.batch()) {
            http.post().uri(uri("delete"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("ids", str.subList(from, Math.min(str.size(), from + cfg.batch()))))
                    .retrieve().toBodilessEntity();
        }
    }

    @Override
    public List<Hit> search(float[] q, int topK, String classifier) {
        ensureCollection();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query_embeddings", List.of(q));
        body.put("n_results", topK);
        body.put("include", List.of("distances"));
        if (classifier != null && !classifier.isBlank()) {
            body.put("where", Map.of("classifier", Map.of("$eq", classifier)));
        }
        QueryResp resp = http.post().uri(uri("query"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve().body(QueryResp.class);
        if (resp == null || resp.ids() == null || resp.ids().isEmpty()) return List.of();
        List<String> ids = resp.ids().get(0);
        List<Double> dist = (resp.distances() == null || resp.distances().isEmpty())
                ? List.of() : resp.distances().get(0);
        List<Hit> out = new ArrayList<>(ids.size());
        for (int i = 0; i < ids.size(); i++) {
            double d = i < dist.size() ? dist.get(i) : Double.NaN;
            out.add(new Hit(Long.parseLong(ids.get(i)), 1.0 - d));
        }
        return out;
    }

    @Override
    public int size() {
        try {
            ensureCollection();
            Integer n = http.get().uri(uri("count")).retrieve().body(Integer.class);
            return n == null ? -1 : n;
        } catch (Exception e) {
            log.warn("ChromaDB count: {}", e.getMessage());
            return -1;
        }
    }

    //** Пересинхронизация Chroma из MSSQL — теперь и с метаданными. */
    @Override
    public synchronized void reload() {
        ensureCollection();
        final int FLUSH = 50_000;
        List<Long> ids = new ArrayList<>(FLUSH);
        List<float[]> vecs = new ArrayList<>(FLUSH);
        List<Map<String, String>> metas = new ArrayList<>(FLUSH);
        long[] total = {0};
        jdbc.query("""
                        SELECT e.chunk_id, e.embedding, c.document_guid, r.classifier
                        FROM dbo.rag_embedding e
                        JOIN dbo.rag_chunk c        ON c.chunk_id = e.chunk_id
                        JOIN dbo.rag_document_ref r ON r.document_guid = c.document_guid AND r.row_id = c.file_id
                        WHERE e.model = ? AND r.status = 'I'
                        """,
                rs -> {
                    ids.add(rs.getLong(1));
                    vecs.add(VecMath.fromLittleEndian(rs.getBytes(2)));
                    metas.add(Map.of("document_guid", rs.getString(3),
                            "classifier", nz(rs.getString(4))));
                    if (ids.size() >= FLUSH) {
                        addAll(ids, vecs, metas);
                        total[0] += ids.size();
                        ids.clear();
                        vecs.clear();
                        metas.clear();
                    }
                }, embedder.model());
        if (!ids.isEmpty()) {
            addAll(ids, vecs, metas);
            total[0] += ids.size();
        }
        log.info("ChromaDB: пересинхронизировано {} векторов из MSSQL", total[0]);
    }

    //    важно: Chroma не принимает null в значениях метаданных
    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static List<String> idsAsStrings(Collection<Long> ids) {
        return ids.stream().map(String::valueOf).toList();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CollectionDto(String id, String name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record QueryResp(List<List<String>> ids, List<List<Double>> distances) {
    }
}