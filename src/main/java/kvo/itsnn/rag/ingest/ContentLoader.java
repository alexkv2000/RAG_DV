package kvo.itsnn.rag.ingest;

import jakarta.annotation.PreDestroy;
import kvo.itsnn.rag.config.RagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

@Component
public class ContentLoader {

    private static final Logger log = LoggerFactory.getLogger(ContentLoader.class);
    private static final long MAX_FILE_BYTES = 64L * 1024 * 1024;
    /** «Нет данных» — вместо null (Sonar: пустой массив вместо null). */
    private static final byte[] NO_BYTES = {};

    public record LoadedFile(UUID fileId, String fileName, String ext, byte[] bytes) {
        @Override
        public boolean equals(Object o) {
            return o instanceof LoadedFile(UUID id, String name, String extension, byte[] b)
                    && Objects.equals(fileId, id)
                    && Objects.equals(fileName, name)
                    && Objects.equals(ext, extension)
                    && Arrays.equals(bytes, b);
        }
        @Override
        public int hashCode() {
            return 31 * Objects.hash(fileId, fileName, ext) + Arrays.hashCode(bytes);
        }
        @Override
        public String toString() {
            return "LoadedFile[fileId=%s, name=%s, ext=%s, %d байт]"
                    .formatted(fileId, fileName, ext, bytes.length);
        }
    }

    private final JdbcTemplate jdbc;

    /** NTFS-сторы: имя → корень шары (\\gaz\docsvision\...). */
    private final Map<String, String> diskRoots;
    /** Архивные БД: имя → JDBC-URL (значения rag.content.roots, начинающиеся с jdbc:). */
    private final Map<String, String> archives;
    /** Ленивые соединения — по одному на архив. */
    private final Map<String, Connection> conns = new HashMap<>();

    public ContentLoader(JdbcTemplate jdbc, RagProperties props) {
        this.jdbc = jdbc;
        Map<String, String> disk = new LinkedHashMap<>();          // было две переменных в одной строке
        Map<String, String> arc = new LinkedHashMap<>();
        if (props.content() != null && props.content().roots() != null) {   // тело в фигурных скобках
            props.content().roots().forEach((k, v) -> {
                if (v == null || v.isBlank()) {
                    return;
                }
                if (v.startsWith("jdbc:")) {
                    arc.put(k, v);
                } else {
                    disk.put(k, v);
                }
            });
        }
        this.diskRoots = Map.copyOf(disk);
        this.archives = Map.copyOf(arc);
        log.info("Контент: диск {}, архивы {}", diskRoots.keySet(), archives.keySet());
    }

    public List<LoadedFile> load(UUID guid) {
        return jdbc.query("""
            SELECT file_id, file_name, content, storage_name, file_path, file_size
            FROM dbo.rag_source_content_view
            WHERE document_guid = ?
            ORDER BY file_name
            """, rs -> {
            List<LoadedFile> out = new ArrayList<>();
            while (rs.next()) {
                mapRow(rs, guid).ifPresent(out::add);      // без continue/break в цикле
            }
            return out;
        }, guid);
    }

    /** Одна строка вьюхи → файл; Optional.empty() = пропустить. Вынесено из load(),
     *  чтобы не превышать лимит когнитивной сложности. */
    private Optional<LoadedFile> mapRow(ResultSet rs, UUID guid) throws SQLException {
        String name = rs.getString("file_name");
        UUID fileId = UUID.fromString(rs.getString("file_id"));
        long declared = rs.getLong("file_size");
        if (declared > MAX_FILE_BYTES) {
            log.warn("{}: '{}' пропущен ({} байт)", guid, name, declared);
            return Optional.empty();
        }
        byte[] data = rs.getBytes("content");              // стор Database — байты в основной БД
        if (isEmpty(data)) {                               // внешний стор
            data = resolveExternal(rs, guid, name, fileId);
        }
        if (isEmpty(data)) {
            log.warn("{}: '{}' без бинарника", guid, name);
            return Optional.empty();
        }
        if (data.length > MAX_FILE_BYTES) {
            log.warn("{}: '{}' пропущен (большой)", guid, name);
            return Optional.empty();
        }
        return Optional.of(new LoadedFile(fileId, name, extOf(name), data));
    }

    /** Байтов в основной БД нет — ищем на шаре или в архивной БД. */
    private byte[] resolveExternal(ResultSet rs, UUID guid, String name, UUID fileId) throws SQLException {
        String storage = rs.getString("storage_name");
        if (storage == null) {
            log.warn("{}: '{}' — стор не определён и байтов в БД нет", guid, name);
            return NO_BYTES;
        }
        if (diskRoots.containsKey(storage)) {
            return fromDisk(guid, storage, rs.getString("file_path"));
        }
        if (archives.containsKey(storage)) {
            String binId = binaryIdOf(fileId);             // dvsys_files.BinaryID
            if (binId != null) {
                return fromArchive(guid, storage, binId);
            }
            return NO_BYTES;
        }
        log.warn("{}: '{}' — хранилище '{}' не настроено (rag.content.roots)", guid, name, storage);
        return NO_BYTES;
    }

    /** NTFS-стор Docsvision: Path = Primary\дата\<guid>_<guid>\файл, но на шаре партиции
     *  (первой папки) может не быть — пробуем полный путь и без первого сегмента. */
    private byte[] fromDisk(UUID guid, String storage, String path) {
        if (path == null || path.isBlank()) {
            return NO_BYTES;
        }
        String root = diskRoots.get(storage);
        List<Path> candidates = new ArrayList<>(2);
        candidates.add(Path.of(root, path));
        int sep = path.indexOf('\\');
        if (sep > 0) {
            candidates.add(Path.of(root, path.substring(sep + 1)));
        }
        for (Path p : candidates) {
            try {
                byte[] data = Files.readAllBytes(p);
                log.debug("{}: '{}' — {} байт с {}", guid, storage, data.length, p);
                return data;
            } catch (IOException | InvalidPathException e) {      // не пустой блок:
                log.debug("{}: путь '{}' не подошёл: {}", guid, p, e.getMessage());  // пробуем следующий
            }
        }
        log.warn("{}: хранилище '{}': файл не найден — пробовали {}", guid, storage, candidates);
        return NO_BYTES;
    }

    /** ID блоба по файлу — lookup по PK dvsys_files, вьюху не трогаем. */
    private String binaryIdOf(UUID fileId) {
        List<String> ids = jdbc.queryForList(
                "SELECT BinaryID FROM dbo.dvsys_files WHERE FileID = ?", String.class, fileId);
        if (ids.isEmpty()) {
            log.warn("dvsys_files: BinaryID для FileID={} не найден", fileId);
            return null;
        }
        return ids.get(0);
    }

    /** Архивный стор: байты в GAZ_Archive_YYYY.dvsys_binaries, ключ — dvsys_binaries.ID. */
    private byte[] fromArchive(UUID guid, String storage, String binaryId) {
        try {
            Connection c = connection(storage);
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT CASE WHEN DATALENGTH(Data) > 0 THEN Data ELSE StreamData END
                    FROM dbo.dvsys_binaries WHERE ID = ?""")) {
                ps.setString(1, binaryId);
                try (ResultSet r = ps.executeQuery()) {
                    if (!r.next()) {
                        log.warn("{}: бинарник {} не найден в '{}'", guid, binaryId, storage);
                        return NO_BYTES;
                    }
                    byte[] data = r.getBytes(1);
                    if (isEmpty(data)) {
                        log.warn("{}: бинарник {} в '{}' пуст", guid, binaryId, storage);
                        return NO_BYTES;
                    }
                    log.debug("{}: {} из '{}' — {} байт", guid, binaryId, storage, data.length);
                    return data;
                }
            }
        } catch (SQLException e) {
            synchronized (conns) {                         // битый коннект — пересоздадим
                Connection dead = conns.remove(storage);
                if (dead != null) {
                    closeQuietly(dead);
                }
            }
            log.warn("{}: архив '{}': {} — {}", guid, storage, binaryId, e.getMessage());
            return NO_BYTES;
        }
    }

    private Connection connection(String storage) throws SQLException {
        synchronized (conns) {
            Connection c = conns.get(storage);
            if (c != null) {
                try {
                    if (c.isValid(3)) {
                        return c;
                    }
                    c.close();
                } catch (SQLException e) {
                    log.debug("Соединение с '{}' переоткроем: {}", storage, e.getMessage());
                }
                conns.remove(storage);
            }
            c = DriverManager.getConnection(archives.get(storage));
            conns.put(storage, c);
            return c;
        }
    }

    @PreDestroy
    public void close() {
        synchronized (conns) {
            conns.values().forEach(ContentLoader::closeQuietly);
            conns.clear();
        }
    }

    private static void closeQuietly(Connection c) {
        try {
            c.close();
        } catch (SQLException e) {
            log.debug("Закрытие соединения: {}", e.getMessage());
        }
    }

    private static boolean isEmpty(byte[] data) {
        return data == null || data.length == 0;
    }

    private static String extOf(String name) {
        if (name == null) {
            return "txt";
        }
        int d = name.lastIndexOf('.');
        return d < 0 ? "txt" : name.substring(d + 1).toLowerCase();
    }
}