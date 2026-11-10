package kvo.itsnn.rag.refsync;

import kvo.itsnn.rag.config.RagProperties;
import kvo.itsnn.rag.index.IndexCleanupService;
import kvo.itsnn.rag.index.VectorIndex;
import kvo.itsnn.rag.ingest.EmbeddingServiceException;
import kvo.itsnn.rag.ingest.IngestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class RagScheduler {

    private static final Logger log = LoggerFactory.getLogger(RagScheduler.class);

    private final RefRepository refs;
    private final IngestService ingest;
    private final IndexCleanupService cleanup;
    private final VectorIndex index;
    private final RagProperties props;
    private final AtomicBoolean ingestBusy = new AtomicBoolean();
    private final AtomicBoolean cleanupBusy = new AtomicBoolean();

    public RagScheduler(RefRepository refs, IngestService ingest, IndexCleanupService cleanup,
                        VectorIndex index, RagProperties props) {
        this.refs = refs; this.ingest = ingest; this.cleanup = cleanup;
        this.index = index; this.props = props;
    }

    @Scheduled(fixedDelayString = "${rag.poll-interval}")
    public void ingestTick() {
        if (!ingestBusy.compareAndSet(false, true)) return;
        try {
            List<DocumentRef> batch = refs.claimForIndexing(props.ingestBatch());
            for (DocumentRef r : batch) {
                try {
                    ingest.processOne(r);
                } catch (EmbeddingServiceException e) {   // сервис лёг — пакет прекращаем,
                    log.error("Пакет прерван: {}", e.getMessage());   // остальные карточки не трогаем
                    break;
                }
            }
        } finally { ingestBusy.set(false); }
    }

    @Scheduled(fixedDelayString = "${rag.poll-interval}", initialDelayString = "10s")
    public void cleanupTick() {
        if (!cleanupBusy.compareAndSet(false, true)) return;
        try { cleanup.purgeDeleted(props.cleanupBatch()); }
        finally { cleanupBusy.set(false); }
    }

    @Scheduled(cron = "0 30 3 * * *")
    public void nightlyRebuild() { index.reload(); }
}