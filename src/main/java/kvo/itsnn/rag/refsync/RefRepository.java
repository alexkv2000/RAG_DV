package kvo.itsnn.rag.refsync;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

import java.util.*;

@Repository
public class RefRepository {

    private final JdbcTemplate jdbc;

    public RefRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Атомарный claim: N -> P.
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
            OUTPUT inserted.document_guid, inserted.classifier, inserted.retry_count
            FROM dbo.rag_document_ref r
            JOIN picked p ON p.document_guid = r.document_guid;
            """;
        return jdbc.query(sql, (rs, i) -> new DocumentRef(
                        UUID.fromString(rs.getString("document_guid")),
                        rs.getString("classifier"), rs.getInt("retry_count")), limit)
                .stream().distinct().toList();     // OUTPUT всё ещё по строкам — дедупим
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

    public void forceReindex(UUID guid) {
        jdbc.update("""
                UPDATE dbo.rag_document_ref
                   SET status='N', error_msg=NULL, version=version+1, updated_at=SYSUTCDATETIME()
                 WHERE document_guid=? AND status IN ('I','E','X')
                """, guid);
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
        List<String> v = jdbc.queryForList(
                "SELECT ctrl_value FROM dbo.rag_control WHERE ctrl_key='ingest_paused'", String.class);
        return !v.isEmpty() && "1".equals(v.get(0));
    }

    public void setPaused(boolean paused) {
        jdbc.update("UPDATE dbo.rag_control SET ctrl_value=? WHERE ctrl_key='ingest_paused'",
                paused ? "1" : "0");
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
}