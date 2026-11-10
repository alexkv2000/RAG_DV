package kvo.itsnn.rag.ingest;

import org.slf4j.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class ContentLoader {

    private static final Logger log = LoggerFactory.getLogger(ContentLoader.class);
    private static final long MAX_FILE_BYTES = 64L * 1024 * 1024;

    public record LoadedFile(UUID fileId, String fileName, String ext, byte[] bytes) {}

    private final JdbcTemplate jdbc;
    public ContentLoader(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<LoadedFile> load(UUID guid) {
        return jdbc.query("""
            SELECT file_id, file_name, content
            FROM dbo.rag_source_content_view
            WHERE document_guid = ?
            ORDER BY file_name
            """, rs -> {
            List<LoadedFile> out = new ArrayList<>();
            while (rs.next()) {
                String name = rs.getString("file_name");
                byte[] data = rs.getBytes("content");
                if (data == null || data.length == 0) { log.warn("{}: '{}' без бинарника", guid, name); continue; }
                if (data.length > MAX_FILE_BYTES) { log.warn("{}: '{}' пропущен (большой)", guid, name); continue; }
                out.add(new LoadedFile(UUID.fromString(rs.getString("file_id")), name, extOf(name), data));
            }
            return out;
        }, guid);
    }

    private static String extOf(String name) {
        if (name == null) return "txt";
        int d = name.lastIndexOf('.');
        return d < 0 ? "txt" : name.substring(d + 1).toLowerCase();
    }
}