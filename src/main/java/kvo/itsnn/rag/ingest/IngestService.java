package kvo.itsnn.rag.ingest;

import kvo.itsnn.rag.config.RagProperties;
import kvo.itsnn.rag.index.VectorIndex;
import kvo.itsnn.rag.refsync.*;
import kvo.itsnn.rag.util.Sha256;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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

    /** Машиночитаемые конверты: семантики в них нет, шум в выдаче — пропускаем целиком. */
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

    /** Основной сценарий; вынесен из processOne, чтобы try-catch не вкладывались
     *  и когнитивная сложность метода оставалась низкой. */
    private void process(DocumentRef ref, long t0) {
        var files = contentLoader.load(ref.guid());
        List<Long> oldIds = refs.chunkIdsOf(ref.guid());   // старые чанки ДО замены

        if (files.isEmpty()) {                             // карточка без файлов
            markEmpty(ref, oldIds);
            return;
        }

        List<ChunkPart> parts = collectParts(ref, files);
        if (parts.isEmpty()) {
            requireText(files);                            // конверты — не ошибка; сканы — ошибка
            markEmpty(ref, oldIds);
            return;
        }

        String joined = parts.stream().map(ChunkPart::text).collect(Collectors.joining("\u0001"));
        byte[] hash = Sha256.of(joined);

        if (refs.sameHash(ref.guid(), hash)) {             // контент не менялся — эмбеддинг пропускаем
            refs.markIndexed(ref.guid(), hash);
            return;
        }

        String prefix = props.embedding().passagePrefix();
        List<float[]> vectors = embedder.embedAll(
                parts.stream().map(p -> prefix + embedInput(p.fileName(), p.text())).toList());

        List<Long> ids = tx.execute(st -> {
            List<Long> newIds = chunks.replaceChunks(ref.guid(), parts);
            chunks.insertEmbeddings(newIds, vectors, embedder.model());  // резервная копия в MSSQL
            return newIds;
        });

        publishVectors(oldIds, ids, vectors);              // ChromaDB; сбой → EmbeddingServiceException
        refs.markIndexed(ref.guid(), hash);                // статус 'I' только после успеха

        log.info("Проиндексирован {} [{}] — {} чанков из {} файлов за {} мс",
                ref.guid(), ref.classifier(), ids.size(), files.size(),
                System.currentTimeMillis() - t0);
    }

    /** Контента нет (или только машинные конверты) — очищаем чанки и фиксируем «пусто». */
    private void markEmpty(DocumentRef ref, List<Long> oldIds) {
        refs.deleteChunks(ref.guid());
        index.removeChunks(oldIds);
        refs.markIndexed(ref.guid(), Sha256.of(""));
    }

    /** Пустой набор чанков допустим только если все файлы — машинные конверты. */
    private static void requireText(List<ContentLoader.LoadedFile> files) {
        if (!files.stream().allMatch(f -> isMachineEnvelope(f.fileName()))) {
            throw new IllegalStateException("ни один файл не дал текст (сканы без OCR?)");
        }
    }

    private List<ChunkPart> collectParts(DocumentRef ref, List<ContentLoader.LoadedFile> files) {
        List<ChunkPart> parts = new ArrayList<>();
        for (ContentLoader.LoadedFile f : files) {
            if (isMachineEnvelope(f.fileName())) {
                continue;
            }
            parts.addAll(safeExtract(ref, f));
        }
        return parts;
    }

    /** Извлечение и чанкование одного файла; сбой одного файла не валит документ целиком. */
    private List<ChunkPart> safeExtract(DocumentRef ref, ContentLoader.LoadedFile f) {
        try {
            List<ChunkPart> parts = new ArrayList<>();
            for (String piece : chunker.split(extractor.extract(f))) {
                parts.add(new ChunkPart(piece, f.fileId(), f.fileName()));
            }
            return parts;
        } catch (Exception e) {
            log.warn("{}: файл '{}' пропущен — {}", ref.guid(), f.fileName(), e.getMessage());
            return List.of();
        }
    }

    private static boolean isMachineEnvelope(String fname) {
        return fname != null && SKIP_FILES.stream().anyMatch(p -> p.matcher(fname).find());
    }

    /** Запись векторов в Chroma; ошибка = "внешняя система недоступна" — не ретрай документа. */
    private void publishVectors(List<Long> oldIds, List<Long> ids, List<float[]> vectors) {
        try {
            index.removeChunks(oldIds);                    // убрать старые векторы
            index.addAll(ids, vectors);                    // добавить новые
        } catch (Exception e) {
            throw new EmbeddingServiceException("ChromaDB: " + e.getMessage(), e);
        }
    }

    private static String messageOf(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    /** Текст для вектора: имя файла несёт реквизиты (номера, даты, «Список VIN...») —
     *  добавляем его к каждому чанку. rag_chunk.content и content_hash — исходный текст. */
    private static String embedInput(String fileName, String text) {
        String name = fileName == null ? "" : fileName.strip();
        if (name.length() > 100) {
            name = name.substring(0, 100);
        }
        return name.isEmpty() ? text : name + "\n" + text;
    }
}