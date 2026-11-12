package kvo.itsnn.rag.refsync;

import kvo.itsnn.rag.config.RagProperties;
import kvo.itsnn.rag.index.IndexCleanupService;
import kvo.itsnn.rag.index.VectorIndex;
import kvo.itsnn.rag.ingest.Embedder;
import kvo.itsnn.rag.ingest.EmbeddingServiceException;
import kvo.itsnn.rag.ingest.IngestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class RagScheduler {

    private static final Logger log = LoggerFactory.getLogger(RagScheduler.class);

    private final RefRepository refs;
    private final IngestService ingest;
    private final IndexCleanupService cleanup;
    private final VectorIndex index;
    private final Embedder embedder;
    private final RagProperties props;
    private final AtomicBoolean ingestBusy = new AtomicBoolean();
    private final AtomicBoolean cleanupBusy = new AtomicBoolean();
    private final AtomicBoolean checkBusy = new AtomicBoolean();
    private final AtomicInteger hourUsed = new AtomicInteger();
    private volatile long hourBucket;

    public RagScheduler(RefRepository refs, IngestService ingest, IndexCleanupService cleanup,
                        VectorIndex index, Embedder embedder, RagProperties props) {
        this.refs = refs; this.ingest = ingest; this.cleanup = cleanup;
        this.index = index; this.embedder = embedder; this.props = props;
    }

    @Scheduled(fixedDelayString = "${rag.poll-interval}")
    public void ingestTick() {
        if (!ingestBusy.compareAndSet(false, true)) return;
        try {
            if (refs.ingestPaused()) return;                      // общий выключатель из rag_control
            boolean night = isNight();
            if (!night && !acquireDayQuota()) return;             // дневной потолок исчерпан
            List<DocumentRef> batch = refs.claimForIndexing(
                    night ? props.ingestBatchNight() : props.ingestBatchDay());
            for (DocumentRef r : batch) {
                try {
                    ingest.processOne(r);
                } catch (EmbeddingServiceException e) {           // сервис лёг — пакет прекращаем
                    log.error("Пакет прерван: {}", e.getMessage());
                    break;
                }
            }
        } finally { ingestBusy.set(false); }
    }

    /** Окно 23:00–05:00 переходит через полночь: ночь = после начала ИЛИ до конца. */
    private boolean isNight() {
        LocalTime t = LocalTime.now();
        return !t.isBefore(props.nightStart()) || t.isBefore(props.nightEnd());
    }

    /** Счётчик карточек за текущий час; мягкий лимит, гонки не критичны. */
    private boolean acquireDayQuota() {
        long bucket = Instant.now().getEpochSecond() / 3600;
        if (bucket != hourBucket) { hourBucket = bucket; hourUsed.set(0); }
        return hourUsed.incrementAndGet() <= props.dayHourLimit();
    }

    @Scheduled(fixedDelayString = "${rag.poll-interval}", initialDelayString = "10s")
    public void cleanupTick() {
        if (!cleanupBusy.compareAndSet(false, true)) return;
        try { cleanup.purgeDeleted(props.cleanupBatch()); }
        finally { cleanupBusy.set(false); }
    }

    /** Сверка Chroma с резервной копией в MSSQL; перезаливка только при расхождении.
     *  Оба инстанса сверяются независимо: двойной reload безвреден (upsert идемпотентен). */
    @Scheduled(cron = "0 30 3 * * *")
    public void nightlyConsistency() {
        if (!checkBusy.compareAndSet(false, true)) return;
        try {
            int mssql = refs.countIndexedVectors(embedder.model());
            int chroma = index.size();
            if (chroma == mssql) { log.info("Ночная сверка пройдена: {} векторов", chroma); return; }
            log.warn("Ночная сверка: Chroma={}, MSSQL={} — пересинхронизация", chroma, mssql);
            index.reload();
        } catch (Exception e) {
            log.error("Ночная сверка не удалась: {}", e.getMessage());
        } finally { checkBusy.set(false); }
    }
}