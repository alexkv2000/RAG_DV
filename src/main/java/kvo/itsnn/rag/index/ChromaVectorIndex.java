package kvo.itsnn.rag.index;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import kvo.itsnn.rag.config.RagProperties;
import kvo.itsnn.rag.ingest.Embedder;
import kvo.itsnn.rag.ingest.EmbeddingServiceException;
import kvo.itsnn.rag.util.VecMath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/**
 * Векторный индекс на ChromaDB (режим сервера, REST API v2).
 * MSSQL (rag_embedding) — резервная копия векторов; reload() пересинхронизирует
 * Chroma из MSSQL со сносом коллекции.
 *
 * Метаданные чанка: document_guid, classifier, org_le (юридическое лицо документа).
 * При поиске orgLe != null → where-фильтр Chroma:
 *   org_le IN (доступные ЮЛ) OR org_le = "" (общие документы).
 *
 * Потокобезопасность: ReentrantReadWriteLock. reload() — write-lock;
 * пользовательские операции — read-lock с таймаутом 5 с (далее 503).
 */
@Component
public class ChromaVectorIndex implements VectorIndex {

    private static final Logger log = LoggerFactory.getLogger(ChromaVectorIndex.class);
    private static final String API = "/api/v2/tenants/default_tenant/databases/default_database/collections";

    private static final long OP_WAIT_MS = 5_000;

    private static final String PREFIX = "ChromaDB: ";
    private static final String RELOADING = "идёт пересборка индекса (reload), повторите через минуту";

    private static final String ACT_UPSERT = "upsert";
    private static final String ACT_DELETE = "delete";
    private static final String ACT_QUERY = "query";
    private static final String ACT_COUNT = "count";

    private final JdbcTemplate jdbc;
    private final Embedder embedder;
    private final RagProperties.Chroma cfg;
    private final String collectionName;
    private final RestClient http;
    private volatile String collectionId;

    private final ReentrantReadWriteLock rw = new ReentrantReadWriteLock(true);

    public ChromaVectorIndex(JdbcTemplate jdbc, Embedder embedder, RagProperties props) {
        this.jdbc = jdbc;
        this.embedder = embedder;
        this.cfg = props.chroma();
        this.collectionName = cfg.collection() + "_" + embedder.model().replaceAll("[^A-Za-z0-9._-]", "_");
        RestClient.Builder b = RestClient.builder()
                .baseUrl(cfg.baseUrl().replaceAll("/+$", ""))
                .requestFactory(requestFactory());
        if (cfg.token() != null && !cfg.token().isBlank())
            b.defaultHeader("Authorization", "Bearer " + cfg.token());
        this.http = b.build();
        log.info("{}url='{}', коллекция='{}'", PREFIX, cfg.baseUrl(), collectionName);
    }

    private static SimpleClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(60_000);
        return factory;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStart() {
        try {
            ensureCollection();
        } catch (Exception e) {
            log.warn("{}недоступна при старте ({}): {}", PREFIX, cfg.baseUrl(), e.getMessage());
        }
    }

    /* ---------- блокировка ---------- */

    private <T> T readOp(String what, Supplier<T> op) {
        Lock r = rw.readLock();
        try {
            if (!r.tryLock(OP_WAIT_MS, TimeUnit.MILLISECONDS)) {
                throw new EmbeddingServiceException(PREFIX + what + " — " + RELOADING, null);
            }
            try {
                return op.get();
            } finally {
                r.unlock();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EmbeddingServiceException(PREFIX + what + " — прервано ожидание индекса", e);
        }
    }

    private void readOp(String what, Runnable op) {
        readOp(what, () -> {
            op.run();
            return null;
        });
    }

    /* ---------- коллекция ---------- */

    private synchronized void ensureCollection() {
        if (collectionId != null) return;
        CollectionDto dto = http.post().uri(API)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", collectionName,
                        "get_or_create", true,
                        "metadata", Map.of("hnsw:space", "cosine")))
                .retrieve().body(CollectionDto.class);
        if (dto == null || dto.id() == null)
            throw new IllegalStateException(PREFIX + "не получена коллекция '" + collectionName + "'");
        collectionId = dto.id();
        log.info("{}коллекция '{}' (id={})", PREFIX, collectionName, collectionId);
    }

    private String uri(String action) {
        return API + "/" + collectionId + "/" + action;
    }

    private void recreateIfMissing(RestClientResponseException e) {
        if (e.getStatusCode().value() != 404 || collectionId == null) {
            throw e;
        }
        log.warn("{}коллекция {} исчезла — пересоздаём", PREFIX, collectionId);
        collectionId = null;
        ensureCollection();
    }

    private <T> T withRecreate(Supplier<T> op) {
        try {
            return op.get();
        } catch (RestClientResponseException e) {
            recreateIfMissing(e);
            return op.get();
        }
    }

    private void withRecreate(Runnable op) {
        try {
            op.run();
        } catch (RestClientResponseException e) {
            recreateIfMissing(e);
            op.run();
        }
    }

    /* ---------- VectorIndex: запись ---------- */

    @Override
    public void addAll(List<Long> chunkIds, List<float[]> vectors, List<Map<String, String>> metadatas) {
        if (chunkIds.isEmpty()) return;
        readOp(ACT_UPSERT, () -> doAddAll(chunkIds, vectors, metadatas));
    }

    private void doAddAll(List<Long> chunkIds, List<float[]> vectors, List<Map<String, String>> metadatas) {
        withRecreate(() -> {
            ensureCollection();
            inBatches(chunkIds.size(), (from, to) -> upsertBatch(
                    chunkIds.subList(from, to), vectors.subList(from, to),
                    metadatas == null ? null : metadatas.subList(from, to)));
        });
    }

    private void upsertBatch(List<Long> ids, List<float[]> vectors, List<Map<String, String>> metadatas) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ids", idsAsStrings(ids));
        body.put("embeddings", vectors);
        if (metadatas != null && !metadatas.isEmpty()) {
            body.put("metadatas", metadatas);
        }
        post(uri(ACT_UPSERT), body);
    }

    @Override
    public void removeChunks(Collection<Long> ids) {
        if (ids.isEmpty()) return;
        readOp(ACT_DELETE, () -> withRecreate(() -> doRemoveChunks(ids)));
    }

    private void doRemoveChunks(Collection<Long> ids) {
        ensureCollection();
        List<String> str = idsAsStrings(ids);
        inBatches(str.size(), (from, to) ->
                post(uri(ACT_DELETE), Map.of("ids", str.subList(from, to))));
    }

    /* ---------- VectorIndex: поиск ---------- */

    @Override
    public List<Hit> search(float[] q, int topK, String classifier, Set<String> orgLe) {
        return readOp(ACT_QUERY, () -> withRecreate(() -> doSearch(q, topK, classifier, orgLe)));
    }

    private List<Hit> doSearch(float[] q, int topK, String classifier, Set<String> orgLe) {
        ensureCollection();
        QueryResp resp = http.post().uri(uri(ACT_QUERY))
                .contentType(MediaType.APPLICATION_JSON)
                .body(queryBody(q, topK, classifier, orgLe))
                .retrieve().body(QueryResp.class);
        return hitsOf(resp);
    }

    /**
     * Тело запроса /query с фильтрами.
     * Where-логика:
     *   classifier != null AND orgLe != null → $and [classifier $eq, $or [org_le $in, org_le $eq ""]]
     *   только classifier                      → {classifier: {$eq: ...}}
     *   только orgLe                           → $or [{org_le: $in}, {org_le: $eq ""}]
     *   orgLe пустое множество                 → {org_le: {$eq: ""}} — только общие документы
     */
    private static Map<String, Object> queryBody(float[] q, int topK,
                                                 String classifier, Set<String> orgLe) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query_embeddings", List.of(q));
        body.put("n_results", topK);
        body.put("include", List.of("distances"));

        Map<String, Object> where = whereOf(classifier, orgLe);
        if (where != null) {
            body.put("where", where);
        }
        return body;
    }

    /**
     * Where-фильтр ChromaDB:
     *   classifier → {classifier: {$eq: X}}
     *   orgLe != null (не пусто) → {$or: [{org_le: {$in: [...ЮЛ, ""]}}, ...]}
     *   orgLe пусто → {org_le: {$eq: ""}}
     *   оба → $and [...]
     */
    static Map<String, Object> whereOf(String classifier, Set<String> orgLe) {
        Map<String, Object> clsCond = null;
        if (classifier != null && !classifier.isBlank()) {
            clsCond = Map.of("classifier", Map.of("$eq", classifier));
        }

        Map<String, Object> orgCond = null;
        if (orgLe != null) {
            if (orgLe.isEmpty()) {
                // нет доступных ЮЛ — только общие документы
                orgCond = Map.of("org_le", Map.of("$eq", ""));
            } else {
                // свои ЮЛ + общие (пустая строка)
                List<String> values = new ArrayList<>(orgLe);
                values.add("");    // общие документы всегда доступны
                orgCond = Map.of("org_le", Map.of("$in", values));
            }
        }

        if (clsCond != null && orgCond != null) {
            return Map.of("$and", List.of(clsCond, orgCond));
        }
        return clsCond != null ? clsCond : orgCond;
    }

    private static List<Hit> hitsOf(QueryResp resp) {
        if (resp == null || resp.ids() == null || resp.ids().isEmpty()) {
            return List.of();
        }
        List<String> ids = resp.ids().get(0);
        List<Double> dist = firstOrNull(resp.distances());
        List<Hit> out = new ArrayList<>(ids.size());
        for (int i = 0; i < ids.size(); i++) {
            out.add(new Hit(Long.parseLong(ids.get(i)), scoreAt(dist, i)));
        }
        return out;
    }

    private static List<Double> firstOrNull(List<List<Double>> lists) {
        return lists == null || lists.isEmpty() ? null : lists.get(0);
    }

    private static double scoreAt(List<Double> dist, int i) {
        return dist == null || i >= dist.size() ? Double.NaN : 1.0 - dist.get(i);
    }

    /* ---------- VectorIndex: размер ---------- */

    @Override
    public int size() {
        try {
            Integer n = readOp(ACT_COUNT, () -> withRecreate(() -> {
                ensureCollection();
                return http.get().uri(uri(ACT_COUNT)).retrieve().body(Integer.class);
            }));
            return n == null ? -1 : n;
        } catch (Exception e) {
            log.warn("{}count: {}", PREFIX, e.getMessage());
            return -1;
        }
    }

    /* ---------- reload ---------- */

    private synchronized void resetCollection() {
        ensureCollection();
        dropCollectionByName();
        collectionId = null;
        ensureCollection();
        deleteAllRecords();
    }

    private void dropCollectionByName() {
        try {
            http.delete().uri(API + "/name/" + collectionName).retrieve().toBodilessEntity();
        } catch (Exception e) {
            log.warn("{}снос по имени не удался ({})", PREFIX, e.getMessage());
        }
    }

    private void deleteAllRecords() {
        try {
            post(uri(ACT_DELETE), Map.of());
            log.info("{}записи коллекции '{}' вычищены", PREFIX, collectionName);
        } catch (Exception e) {
            log.warn("{}очистка записей '{}' не удалась ({})", PREFIX, collectionName, e.getMessage());
        }
    }

    @Override
    public void reload() {
        Lock w = rw.writeLock();
        w.lock();
        try {
            resetCollection();
            upsertFromMssql();
        } finally {
            w.unlock();
        }
    }

    /** Заливка всех векторов из MSSQL — теперь читает и org_le из rag_document_ref. */
    private void upsertFromMssql() {
        final int FLUSH = 10_000;
        List<Long> ids = new ArrayList<>(FLUSH);
        List<float[]> vecs = new ArrayList<>(FLUSH);
        List<Map<String, String>> metas = new ArrayList<>(FLUSH);
        long[] total = {0};
        jdbc.query("""
                        SELECT e.chunk_id, e.embedding, c.document_guid, r.classifier, r.org_le
                        FROM dbo.rag_embedding e
                        JOIN dbo.rag_chunk c        ON c.chunk_id = e.chunk_id
                        JOIN dbo.rag_document_ref r ON r.document_guid = c.document_guid AND r.row_id = c.file_id
                        WHERE e.model = ? AND r.status = 'I'
                        """,
                rs -> {
                    ids.add(rs.getLong(1));
                    vecs.add(VecMath.fromLittleEndian(rs.getBytes(2)));
                    metas.add(metadataOf(rs.getString(3), rs.getString(4), rs.getString(5)));
                    if (ids.size() >= FLUSH) {
                        total[0] += ids.size();
                        doAddAll(ids, vecs, metas);
                        ids.clear();
                        vecs.clear();
                        metas.clear();
                    }
                }, embedder.model());
        if (!ids.isEmpty()) {
            total[0] += ids.size();
            doAddAll(ids, vecs, metas);
        }
        log.info("{}пересинхронизировано {} векторов из MSSQL", PREFIX, total[0]);
    }

    /** Метаданные чанка: document_guid, classifier, org_le. Chroma не принимает null. */
    static Map<String, String> metadataOf(String documentGuid, String classifier, String orgLe) {
        Map<String, String> m = new HashMap<>();
        m.put("document_guid", nz(documentGuid));
        m.put("classifier", nz(classifier));
        m.put("org_le", nz(orgLe));
        return m;
    }

    /* ---------- мелкие помощники ---------- */

    private void post(String uri, Map<String, Object> body) {
        http.post().uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve().toBodilessEntity();
    }

    private void inBatches(int size, Batch op) {
        for (int from = 0; from < size; from += cfg.batch()) {
            op.run(from, Math.min(size, from + cfg.batch()));
        }
    }

    @FunctionalInterface
    private interface Batch {
        void run(int from, int to);
    }

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