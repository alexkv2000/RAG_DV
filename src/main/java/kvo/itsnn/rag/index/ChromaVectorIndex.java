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
import java.util.stream.Collectors;

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
        try { ensureCollection(); }
        catch (Exception e) { log.warn("ChromaDB недоступна при старте ({}): {}", cfg.baseUrl(), e.getMessage()); }
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

    private String uri(String action) { return API + "/" + collectionId + "/" + action; }

    @Override
    public void addAll(List<Long> chunkIds, List<float[]> vectors) {
        if (chunkIds.isEmpty()) return;
        ensureCollection();
        for (int from = 0; from < chunkIds.size(); from += cfg.batch()) {
            int to = Math.min(chunkIds.size(), from + cfg.batch());
            http.post().uri(uri("upsert"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "ids",       idsAsStrings(chunkIds.subList(from, to)),
                            "embeddings", vectors.subList(from, to)))
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
    public List<Hit> search(float[] q, int topK) {
        ensureCollection();
        QueryResp resp = http.post().uri(uri("query"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "query_embeddings", List.of(q),
                        "n_results",        topK,
                        "include",          List.of("distances")))
                .retrieve().body(QueryResp.class);
        if (resp == null || resp.ids() == null || resp.ids().isEmpty()) return List.of();
        List<String> ids = resp.ids().get(0);
        List<Double> dist = (resp.distances() == null || resp.distances().isEmpty())
                ? List.of() : resp.distances().get(0);
        List<Hit> out = new ArrayList<>(ids.size());
        for (int i = 0; i < ids.size(); i++) {
            double d = i < dist.size() ? dist.get(i) : Double.NaN;
            // cosine-расстояние = 1 - cos(сходство); векторы нормированы,
            // поэтому 1 - distance эквивалентен прежнему скалярному произведению
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
        } catch (Exception e) { log.warn("ChromaDB count: {}", e.getMessage()); return -1; }
    }

    /** Пересинхронизация Chroma из MSSQL (аварийное восстановление / ночной rebuild). */
    @Override
    public synchronized void reload() {
        ensureCollection();
        List<Long> ids = new ArrayList<>();
        List<float[]> vecs = new ArrayList<>();
        jdbc.query("""
            SELECT e.chunk_id, e.embedding
            FROM dbo.rag_embedding e
            JOIN dbo.rag_chunk c        ON c.chunk_id = e.chunk_id
            JOIN dbo.rag_document_ref r ON r.document_guid = c.document_guid
            WHERE e.model = ? AND r.status = 'I'
            """,
                rs -> {
                    ids.add(rs.getLong(1));
                    vecs.add(VecMath.fromLittleEndian(rs.getBytes(2)));
                }, embedder.model());
        addAll(ids, vecs);
        log.info("ChromaDB: пересинхронизировано {} векторов из MSSQL", ids.size());
    }

    private static List<String> idsAsStrings(Collection<Long> ids) {
        return ids.stream().map(String::valueOf).collect(Collectors.toList());
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CollectionDto(String id, String name) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record QueryResp(List<List<String>> ids, List<List<Double>> distances) {}
}