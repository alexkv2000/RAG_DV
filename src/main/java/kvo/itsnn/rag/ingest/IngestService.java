package kvo.itsnn.rag.ingest;

import kvo.itsnn.rag.config.RagProperties;
import kvo.itsnn.rag.index.VectorIndex;
import kvo.itsnn.rag.refsync.*;
import kvo.itsnn.rag.util.Sha256;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    private final ContentLoader contentLoader;
    private final TextExtractor extractor;
    private final Chunker chunker;
    private final Embedder embedder;
    private final RefRepository refs;
    private final ChunkRepository chunks;
    private final VectorIndex index;                   // теперь ChromaDB
    private final TransactionTemplate tx;
    private final RagProperties props;
    private static final byte[] NO_BYTES = {};

    /**
     * Машиночитаемые конверты: семантики в них нет, шум в выдаче — пропускаем целиком.
     */
    private static final List<Pattern> SKIP_FILES = List.of(
            Pattern.compile("^ON_[A-Z]+_.*\\.xml$", Pattern.CASE_INSENSITIVE),       // форматы ФНС (ЭДО)
            Pattern.compile("^MessageSourceXml\\.xml$", Pattern.CASE_INSENSITIVE));  // конверт обмена Directum

    public IngestService(ContentLoader contentLoader, TextExtractor extractor, Chunker chunker,
                         Embedder embedder, RefRepository refs, ChunkRepository chunks,
                         VectorIndex index, TransactionTemplate tx, RagProperties props) {
        this.contentLoader = contentLoader;
        this.extractor = extractor;
        this.chunker = chunker;
        this.embedder = embedder;
        this.refs = refs;
        this.chunks = chunks;
        this.index = index;
        this.tx = tx;
        this.props = props;
    }

    public void processOne(DocumentRef ref) {
        long t0 = System.currentTimeMillis();
        try {
            process(ref, t0);
        } catch (EmbeddingServiceException e) {
            refs.releaseClaim(ref.guid());             // назад в очередь, без штрафа
            throw e;                                   // не логируем здесь: залогирует планировщик
        } catch (Exception e) {
            String msg = messageOf(e);
            refs.markResult(ref.guid(), msg, props.maxRetries());
            log.error("Ошибка индексации {}: {}", ref.guid(), msg);
            log.debug("Ошибка индексации {}: {}", ref.guid(), msg, e);
        }
    }

    /**
     * Основной сценарий; вынесен из processOne, чтобы try-catch не вкладывались
     * и когнитивная сложность метода оставалась низкой.
     */
    private void process(DocumentRef ref, long t0) {
        List<ContentLoader.FileMeta> metas = contentLoader.loadMeta(ref.guid());
        List<Long> oldIds = refs.chunkIdsOf(ref.guid());
        if (metas.isEmpty()) { markEmpty(ref, oldIds); return; }
        Extracted ex = extractParts(ref, metas);            // байты — только одного файла в моменте
        if (ex.parts().isEmpty()) {
            requireText(ex.sawContentFile());
            markEmpty(ref, oldIds);
            return;
        }
        byte[] hash = ex.digest().digest();                 // вместо joined-копии всего текста
        if (refs.sameHash(ref.guid(), hash)) {              // контент не менялся — эмбеддинг пропускаем
            refs.markIndexed(ref.guid(), hash);
            return;
        }
        List<float[]> vectors = vectorsOf(ex.parts());      // батчами, без копий всех строк сразу
        List<Long> ids = tx.execute(st -> {
            List<Long> newIds = chunks.replaceChunks(ref.guid(), ex.parts());
            chunks.insertEmbeddings(newIds, vectors, embedder.model());
            return newIds;
        });
        publishVectors(oldIds, ref.guid(), ref.classifier(), ids, vectors);
        refs.markIndexed(ref.guid(), hash);
        log.info("Проиндексирован {} [{}] — {} чанков из {} файлов за {} мс",
                ref.guid(), ref.classifier(), ids.size(), metas.size(),
                System.currentTimeMillis() - t0);
    }

    private record Extracted(List<ChunkPart> parts, MessageDigest digest, boolean sawContentFile) {}
    private Extracted extractParts(DocumentRef ref, List<ContentLoader.FileMeta> metas) {
        List<ChunkPart> parts = new ArrayList<>();
        MessageDigest digest = Sha256.newDigest();
        boolean sawContentFile = false;
        boolean first = true;
        for (ContentLoader.FileMeta m : metas) {
            byte[] bytes = contentBytes(ref, m);
            if (bytes.length == 0) continue;              // единственный continue (Sonar S135)
            sawContentFile = true;
            for (ChunkPart p : safeExtract(ref, m, bytes)) {
                // байт-в-байт то же, что Sha256.of(String.join("\u0001", texts)) — хэши совместимы
                if (!first) digest.update((byte) 1);
                first = false;
                digest.update(p.text().getBytes(StandardCharsets.UTF_8));
                parts.add(p);
            }
            // bytes недостижимы дальше — GC заберёт сразу после итерации
        }
        return new Extracted(parts, digest, sawContentFile);
    }
    /** Байты файла, пригодного к извлечению; пустой массив — машинный конверт
     *  или бинарника нет (причина пустоты — в логе ContentLoader). */
    private byte[] contentBytes(DocumentRef ref, ContentLoader.FileMeta m) {
        if (isMachineEnvelope(m.fileName())) {
            return NO_BYTES;                              // семантики нет, шум в выдаче — пропускаем
        }
        return contentLoader.loadBytes(ref.guid(), m);
    }
    /** Извлечение и чанкование ОДНОГО файла; байты живут только в этом кадре стека. */
    private List<ChunkPart> safeExtract(DocumentRef ref, ContentLoader.FileMeta m, byte[] bytes) {
        try {
            List<ChunkPart> parts = new ArrayList<>();
            var f = new ContentLoader.LoadedFile(m.fileId(), m.fileName(), m.ext(), bytes);
            for (String piece : chunker.split(extractor.extract(f))) {
                parts.add(new ChunkPart(piece, m.fileId(), m.fileName()));
            }
            return parts;
        } catch (Exception e) {
            log.warn("{}: файл '{}' пропущен — {}", ref.guid(), m.fileName(), e.getMessage());
            return List.of();
        }
    }
    /** Эмбеддинг батчами: строки с префиксом живут только в рамках батча. */
    private List<float[]> vectorsOf(List<ChunkPart> parts) {
        String prefix = props.embedding().passagePrefix();
        int batch = Math.max(1, props.embedding().batch());
        List<float[]> vectors = new ArrayList<>(parts.size());
        for (int from = 0; from < parts.size(); from += batch) {
            List<ChunkPart> slice = parts.subList(from, Math.min(parts.size(), from + batch));
            List<String> input = slice.stream()
                    .map(p -> prefix + embedInput(p.fileName(), p.text()))
                    .toList();
            vectors.addAll(embedder.embedAll(input));   // 384 float ≈ 1,5 КБ на чанк — копим смело
        }
        return vectors;
    }
    /** Пустой набор чанков допустим, только если контентных файлов не было вовсе (только конверты). */
    private static void requireText(boolean sawContentFile) {
        if (sawContentFile) throw new IllegalStateException("ни один файл не дал текст (сканы без OCR?)");
    }

    /**
     * Контента нет (или только машинные конверты) — очищаем чанки и фиксируем «пусто».
     */
    private void markEmpty(DocumentRef ref, List<Long> oldIds) {
        refs.deleteChunks(ref.guid());
        try {
            index.removeChunks(oldIds);
        } catch (Exception e) {
            log.warn("Chroma: векторы {} не удалены ({}) — вычистит ночная сверка", ref.guid(), e.getMessage());
        }
        refs.markIndexed(ref.guid(), Sha256.of(""));
    }

    private static boolean isMachineEnvelope(String fname) {
        return fname != null && SKIP_FILES.stream().anyMatch(p -> p.matcher(fname).find());
    }

    /**
     * Запись векторов в Chroma с метаданными документа; ошибка = внешняя система.
     */
    private void publishVectors(List<Long> oldIds, UUID guid, String classifier,
                                List<Long> ids, List<float[]> vectors) {
        try {
            index.removeChunks(oldIds);
            List<Map<String, String>> metas = new ArrayList<>(ids.size());
            for (int i = 0; i < ids.size(); i++) {
                metas.add(Map.of("document_guid", guid.toString(),
                        "classifier", classifier == null ? "" : classifier));
            }
            index.addAll(ids, vectors, metas);
        } catch (Exception e) {
            // MSSQL уже переключился на новые chunk_id; старые удалены из MSSQL,
            // их векторы в Chroma — сироты. Убираем best-effort; не вышло — вычистит ночная сверка.
            try {
                index.removeChunks(oldIds);
            } catch (Exception sup) {
                log.warn("{}: сиротные векторы не вычищены ({}) — ждём ночную сверку", guid, sup.getMessage());
            }
            throw new EmbeddingServiceException("ChromaDB: " + e.getMessage(), e);
        }
    }

    private static String messageOf(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    /**
     * Текст для вектора: имя файла несёт реквизиты (номера, даты, «Список VIN...») —
     * добавляем его к каждому чанку. rag_chunk.content и content_hash — исходный текст.
     */
    private static String embedInput(String fileName, String text) {
        String name = fileName == null ? "" : fileName.strip();
        if (name.length() > 100) {
            name = name.substring(0, 100);
        }
        return name.isEmpty() ? text : name + "\n" + text;
    }
}