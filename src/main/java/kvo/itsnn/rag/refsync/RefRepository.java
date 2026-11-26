package kvo.itsnn.rag.refsync;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;
import java.time.LocalTime;
import java.util.*;

@Repository
public class RefRepository {

    private final JdbcTemplate jdbc;

    public RefRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }
    public static final String NIGHT_START_KEY = "night_start";
    public static final String NIGHT_END_KEY = "night_end";

    public static final String INGEST_BATCH_DAY_KEY = "ingest_batch_day";
    public static final String INGEST_BATCH_NIGHT_KEY = "ingest_batch_night";
    public static final String DAY_HOUR_LIMIT_KEY = "day_hour_limit";
    public static final String POLL_INTERVAL_KEY = "poll_interval_sec";

    /** Общий для всех инстансов выключатель индексации. */
    public static final String INGEST_PAUSED_KEY = "ingest_paused";

    /** Ключ rag_control: результат последней ночной сверки embedding (JSON). */
    public static final String NIGHTLY_KEY = "nightly_embedding";

    /** Значение из rag_control по ключу; null — строки нет / значение пустое / БД недоступна. */
    private String ctrlRaw(String key) {
        try {
            List<String> v = jdbc.queryForList(
                    "SELECT ctrl_value FROM dbo.rag_control WHERE ctrl_key = ?", String.class, key);
            if (v.isEmpty()) return null;
            String raw = v.getFirst();
            return raw == null || raw.isBlank() ? null : raw.strip();
        } catch (Exception e) {
            return null;
        }
    }

    /** Целочисленная настройка из rag_control; пусто/битое/БД недоступна → fallback. */
    public int ctrlInt(String key, int fallback) {
        String raw = ctrlRaw(key);
        if (raw == null) return fallback;
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public void setCtrlInt(String key, int value) {
        upsertCtrl(key, Integer.toString(value));
    }

    /** Засев из дефолтов кода при первом старте; существующее не трогает. */
    public void seedCtrlInt(String key, int value) {
        seedCtrl(key, Integer.toString(value));
    }

    /** Эффективное значение из rag_control; пусто/битое → fallback (значение из yaml). */
    public LocalTime ctrlTime(String key, LocalTime fallback) {
        String raw = ctrlRaw(key);
        if (raw == null) return fallback;
        try {
            return LocalTime.parse(raw);
        } catch (Exception e) {
            return fallback;
        }
    }

    public void setNightWindow(LocalTime start, LocalTime end) {
        upsertCtrl(NIGHT_START_KEY, start.toString());
        upsertCtrl(NIGHT_END_KEY, end.toString());
    }

    /** Засев из yaml при первом старте; существующее значение не трогает. */
    public void seedCtrlTime(String key, LocalTime value) {
        seedCtrl(key, value.toString());
    }

    private void seedCtrl(String key, String value) {
        jdbc.update("""
                INSERT INTO dbo.rag_control (ctrl_key, ctrl_value)
                SELECT ?, ?
                WHERE NOT EXISTS (SELECT 1 FROM dbo.rag_control WHERE ctrl_key = ?)
                """, key, value, key);
    }

    private void upsertCtrl(String key, String value) {
        jdbc.update("""
                MERGE dbo.rag_control WITH (HOLDLOCK) AS t
                USING (SELECT ? AS k) AS s ON t.ctrl_key = s.k
                WHEN MATCHED THEN UPDATE SET t.ctrl_value = ?
                WHEN NOT MATCHED THEN INSERT (ctrl_key, ctrl_value) VALUES (s.k, ?);
                """, key, value, value);
    }

    /**
     * Атомарный claim: N -> P. Теперь с org_le (для записи в метаданные Chroma).
     */
    public List<DocumentRef> claimForIndexing(int limit) {
        String sql = """
            WITH picked AS (
                SELECT DISTINCT TOP (?) document_guid, src_modified_at
                FROM dbo.rag_document_ref WITH (READPAST, UPDLOCK)
                WHERE status = 'N'
                ORDER BY src_modified_at, document_guid
            )
            UPDATE r
               SET r.status = 'P', r.claimed_at = SYSUTCDATETIME(),
                   r.retry_count = r.retry_count + 1, r.updated_at = SYSUTCDATETIME()
            OUTPUT inserted.document_guid, inserted.classifier, inserted.org_le, inserted.retry_count
            FROM dbo.rag_document_ref r
            JOIN picked p ON p.document_guid = r.document_guid;
            """;
        return jdbc.query(sql, (rs, i) -> new DocumentRef(
                        UUID.fromString(rs.getString("document_guid")),
                        rs.getString("classifier"),
                        rs.getString("org_le"),
                        rs.getInt("retry_count")), limit)
                .stream().distinct().toList();
    }

    /**
     * ← ПАТЧ. row_id файлов, зарегистрированных заявкой документа. FK
     * rag_chunk(document_guid, file_id) ссылается на пару (document_guid, row_id),
     * а Ingest берёт список файлов из rag_source_content_view — этим методом он
     * сверяет вьюху с заявкой и не вставляет чанки для незарегистрированных файлов.
     */
    public Set<UUID> registeredFileIds(UUID guid) {
        return jdbc.query("SELECT row_id FROM dbo.rag_document_ref WHERE document_guid = ?",
                rs -> {
                    Set<UUID> out = new HashSet<>();
                    while (rs.next()) {
                        out.add(UUID.fromString(rs.getString(1)));
                    }
                    return out;
                }, guid);
    }

    public boolean sameHash(UUID guid, byte[] hash) {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM dbo.rag_document_ref
                WHERE document_guid=? AND status IN ('P','I') AND content_hash=?
                """, Integer.class, guid, hash);
        return n != null && n > 0;
    }

    public void markIndexed(UUID guid, byte[] contentHash) {
        jdbc.update("""
                UPDATE dbo.rag_document_ref
                   SET status='I', indexed_at=SYSUTCDATETIME(), claimed_at=NULL,
                       content_hash=?, error_msg=NULL, retry_count=0, updated_at=SYSUTCDATETIME()
                 WHERE document_guid=? AND status='P'
                """, contentHash, guid);
    }

    public void markResult(UUID guid, String error, int maxRetries) {
        jdbc.update("""
                UPDATE dbo.rag_document_ref
                   SET status = CASE WHEN retry_count >= ? THEN 'E' ELSE 'N' END,
                       error_msg = ?, claimed_at=NULL, updated_at=SYSUTCDATETIME()
                 WHERE document_guid=? AND status='P'
                """, maxRetries, error, guid);
    }

    /**
     * ← ПАТЧ. Ручная переиндексация: строки заявки в N СО СБРОСОМ ретраев — без него
     * E-карточка после повторной постановки в очередь получала одну попытку
     * (retry_count уже ≥ maxRetries, и markResult сразу вернул бы E).
     * false — заявки нет: карточка удалена (🗑) или ещё не создана синхронизацией;
     * контроллер сообщит об этом вместо молчаливого no-op.
     */
    public boolean forceReindex(UUID guid) {
        return jdbc.update("""
                UPDATE dbo.rag_document_ref
                   SET status='N', error_msg=NULL, retry_count=0, version=version+1,
                       updated_at=SYSUTCDATETIME()
                 WHERE document_guid=? AND status IN ('I','E','X')
                """, guid) > 0;
    }

    public List<UUID> fetchDeleted(int limit) {
        return jdbc.queryForList("""
                SELECT TOP (?) document_guid FROM dbo.rag_document_ref
                WHERE status='D' ORDER BY sync_at
                """, UUID.class, limit);
    }

    public boolean markPurged(UUID guid) {
        return jdbc.update("""
                UPDATE dbo.rag_document_ref
                   SET status='X', claimed_at=NULL, updated_at=SYSUTCDATETIME()
                 WHERE document_guid=? AND status='D'
                """, guid) > 0;     // удалённые документы из карточки более одного никогда не вычищались бы
    }

    public List<Long> chunkIdsOf(UUID guid) {
        return jdbc.queryForList("SELECT chunk_id FROM dbo.rag_chunk WHERE document_guid=?", Long.class, guid);
    }

    public void deleteChunks(UUID guid) {
        jdbc.update("DELETE FROM dbo.rag_chunk WHERE document_guid=?", guid);
    }

    public Map<String, Integer> statusCounts() {
        Map<String, Integer> m = new LinkedHashMap<>();
        RowCallbackHandler collect = rs -> m.put(rs.getString(1), rs.getInt(2));
        jdbc.query("SELECT status, COUNT(*) FROM dbo.rag_document_ref GROUP BY status", collect);
        return m;
    }

    /**
     * Возврат заявки в очередь без начисления ретрая (системный сбой эмбеддера).
     */
    public void releaseClaim(UUID guid) {
        jdbc.update("""
                UPDATE dbo.rag_document_ref
                   SET status='N', claimed_at=NULL,
                       retry_count = CASE WHEN retry_count > 0 THEN retry_count - 1 ELSE 0 END,
                       updated_at=SYSUTCDATETIME()
                 WHERE document_guid=? AND status='P'
                """, guid);
    }

    /**
     * Общий для всех инстансов выключатель индексации (dbo.rag_control).
     */
    public boolean ingestPaused() {
        return "1".equals(ctrlRaw(INGEST_PAUSED_KEY));
    }

    public void setPaused(boolean paused) {
        jdbc.update("UPDATE dbo.rag_control SET ctrl_value=? WHERE ctrl_key=?",
                paused ? "1" : "0", INGEST_PAUSED_KEY);
    }

    /**
     * Контрольное число векторов для ночной сверки.
     */
    public int countIndexedVectors(String model) {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM dbo.rag_embedding e
                JOIN dbo.rag_chunk c        ON c.chunk_id = e.chunk_id
                JOIN dbo.rag_document_ref r ON r.document_guid = c.document_guid AND r.row_id = c.file_id
                WHERE e.model = ? AND r.status = 'I'
                """, Integer.class, model);
        return n == null ? 0 : n;
    }

    /** Upsert результата сверки; MERGE+HOLDLOCK — атомарно даже при двух инстансах. */
    public void saveNightlyCheck(String json) {
        upsertCtrl(NIGHTLY_KEY, json);
    }

    /** Последний записанный результат; null — сверка ещё не выполнялась. */
    public String loadNightlyCheck() {
        return ctrlRaw(NIGHTLY_KEY);
    }
}