package kvo.itsnn.rag.ingest;

import kvo.itsnn.rag.util.VecMath;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.*;

@Repository
public class ChunkRepository {

    private final JdbcTemplate jdbc;
    public ChunkRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Long> replaceChunks(UUID guid, List<ChunkPart> parts) {
        jdbc.update("DELETE FROM dbo.rag_chunk WHERE document_guid=?", guid);
        List<Long> ids = new ArrayList<>(parts.size());
        for (int i = 0; i < parts.size(); i++) {
            ChunkPart p = parts.get(i);
            ids.add(jdbc.queryForObject("""
                INSERT INTO dbo.rag_chunk(document_guid, chunk_no, file_id, file_name, content, content_hash)
                OUTPUT INSERTED.chunk_id
                VALUES (?, ?, ?, ?, ?, HASHBYTES('SHA2_256', ?))
                """, Long.class, guid, i, p.fileId(), p.fileName(), p.text(), p.text()));
        }
        return ids;
    }

    public void insertEmbeddings(List<Long> chunkIds, List<float[]> vectors, String model) {
        jdbc.batchUpdate("INSERT INTO dbo.rag_embedding(chunk_id, model, dim, embedding) VALUES (?,?,?,?)",
                new BatchPreparedStatementSetter() {
                    @Override public void setValues(PreparedStatement ps, int i) throws SQLException {
                        ps.setLong(1, chunkIds.get(i));
                        ps.setString(2, model);
                        ps.setShort(3, (short) vectors.get(i).length);
                        ps.setBytes(4, VecMath.toLittleEndian(vectors.get(i)));
                    }
                    @Override public int getBatchSize() { return chunkIds.size(); }
                });
    }
}