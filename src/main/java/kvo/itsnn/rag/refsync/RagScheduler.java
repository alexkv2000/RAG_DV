package kvo.itsnn.rag.refsync;

import kvo.itsnn.rag.config.RagProperties;
import kvo.itsnn.rag.index.IndexCleanupService;
import kvo.itsnn.rag.index.VectorIndex;
import kvo.itsnn.rag.ingest.Embedder;
import kvo.itsnn.rag.ingest.EmbeddingServiceException;
import kvo.itsnn.rag.ingest.IngestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class RagScheduler implements SchedulingConfigurer {

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
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    public RagScheduler(RefRepository refs, IngestService ingest, IndexCleanupService cleanup,
                        VectorIndex index, Embedder embedder, RagProperties props) {
        this.refs = refs; this.ingest = ingest; this.cleanup = cleanup;
        this.index = index; this.embedder = embedder; this.props = props;
    }

    /** Тики ingest/cleanup — через динамический Trigger: пауза между запусками берётся
     *  из rag_control (poll_interval_sec) после каждого прогона, смена в панели действует сразу. */
    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addTriggerTask(this::ingestTick, dynamicTrigger(0));
        registrar.addTriggerTask(this::cleanupTick, dynamicTrigger(10_000));
    }

    /** fixedDelay-семантика: интервал после ЗАВЕРШЕНИЯ прогона; первый запуск через initialDelay. */
    private Trigger dynamicTrigger(long initialDelayMs) {
        return ctx -> {
            Instant done = ctx.lastCompletion();
            return done == null
                    ? Instant.now().plusMillis(initialDelayMs)
                    : done.plusMillis(pollIntervalMs());
        };
    }

    private long pollIntervalMs() {
        int sec = refs.ctrlInt(RefRepository.POLL_INTERVAL_KEY,
                (int) props.pollInterval().toSeconds());
        return Math.max(1L, sec) * 1000L;
    }

    public void ingestTick() {
        if (!ingestBusy.compareAndSet(false, true)) return;
        try {
            if (refs.ingestPaused()) return;                      // общий выключатель из rag_control
            boolean night = isNight();
            if (!night && !acquireDayQuota()) return;             // дневной потолок исчерпан
            int batch = refs.ctrlInt(night ? RefRepository.INGEST_BATCH_NIGHT_KEY
                            : RefRepository.INGEST_BATCH_DAY_KEY,
                    night ? props.ingestBatchNight() : props.ingestBatchDay());
            List<DocumentRef> claimed = refs.claimForIndexing(batch);
            for (DocumentRef r : claimed) {
                try {
                    ingest.processOne(r);
                } catch (EmbeddingServiceException e) {           // сервис лёг — пакет прекращаем
                    log.error("Пакет прерван: {}", e.getMessage());
                    break;
                }
            }
        } finally { ingestBusy.set(false); }
    }

    /** Окно ночи переходит через полночь: ночь = после начала ИЛИ до конца.
     *  Значения — из rag_control (админ-панель); дефолты кода — резерв. */
    private boolean isNight() {
        LocalTime t = LocalTime.now();
        LocalTime start = refs.ctrlTime(RefRepository.NIGHT_START_KEY, props.nightStart());
        LocalTime end = refs.ctrlTime(RefRepository.NIGHT_END_KEY, props.nightEnd());
        return !t.isBefore(start) || t.isBefore(end);
    }

    /** Счётчик карточек за текущий час; мягкий лимит, гонки не критичны. */
    private boolean acquireDayQuota() {
        long bucket = Instant.now().getEpochSecond() / 3600;
        if (bucket != hourBucket) { hourBucket = bucket; hourUsed.set(0); }
        int limit = refs.ctrlInt(RefRepository.DAY_HOUR_LIMIT_KEY, props.dayHourLimit());
        return hourUsed.incrementAndGet() <= limit;
    }

    public void cleanupTick() {
        if (!cleanupBusy.compareAndSet(false, true)) return;
        try { cleanup.purgeDeleted(props.cleanupBatch()); }
        finally { cleanupBusy.set(false); }
    }

    /** Сверка Chroma с резервной копией в MSSQL; перезаливка только при расхождении.
     *  Результат — в rag_control (nightly_embedding), админ-панель берёт его из /stats.
     *  Оба инстанса сверяются независимо: двойной reload безвреден (upsert идемпотентен). */
    @Scheduled(cron = "0 30 3 * * *")
    public boolean nightlyConsistency() {
        if (!checkBusy.compareAndSet(false, true)) return false;
        long t0 = System.currentTimeMillis();
        try {
            int mssql = refs.countIndexedVectors(embedder.model());
            int chroma = index.size();
            if (chroma == mssql) {
                log.info("Ночная сверка пройдена: {} векторов", chroma);
                saveNightly("ok", chroma, chroma, mssql, t0, null);
                return true;
            }
            log.warn("Ночная сверка: Chroma={}, MSSQL={} — пересинхронизация", chroma, mssql);
            index.reload();
            int after = index.size();
            saveNightly(after == mssql ? "resynced" : "diff", chroma, after, mssql, t0, null);
        } catch (Exception e) {
            log.error("Ночная сверка не удалась: {}", e.getMessage());
            saveNightly("error", -1, -1, -1, t0, e.getMessage());
        } finally { checkBusy.set(false); }
        return true;
    }

    /** JSON для nightly_embedding в формате, который ждёт renderNightly() в index.html. */
    private void saveNightly(String result, int chromaBefore, int chromaAfter, int mssql,
                             long t0, String error) {
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("at", Instant.now().toString());
            m.put("result", result);
            m.put("chromaBefore", chromaBefore);
            m.put("chromaAfter", chromaAfter);
            m.put("mssql", mssql);
            m.put("ms", System.currentTimeMillis() - t0);
            if (error != null) m.put("error", error);
            refs.saveNightlyCheck(JSON.writeValueAsString(m));
        } catch (Exception e) {          // сверка прошла, но не записалась — не будим админа 500-м
            log.warn("rag_control: результат сверки не записан ({})", e.getMessage());
        }
    }

    /** Первый старт: сеем все настройки планировщика в rag_control (не перезаписывая существующие). */
    @EventListener(ApplicationReadyEvent.class)
    public void seedSchedulerSettings() {
        try {
            refs.seedCtrlTime(RefRepository.NIGHT_START_KEY, props.nightStart());
            refs.seedCtrlTime(RefRepository.NIGHT_END_KEY, props.nightEnd());
            refs.seedCtrlInt(RefRepository.INGEST_BATCH_DAY_KEY, props.ingestBatchDay());
            refs.seedCtrlInt(RefRepository.INGEST_BATCH_NIGHT_KEY, props.ingestBatchNight());
            refs.seedCtrlInt(RefRepository.DAY_HOUR_LIMIT_KEY, props.dayHourLimit());
            refs.seedCtrlInt(RefRepository.POLL_INTERVAL_KEY, (int) props.pollInterval().toSeconds());
            log.info("rag_control: настройки планировщика (окно, batch, лимит, опрос) проверены/просеяны");
        } catch (Exception e) {
            log.warn("rag_control: настройки не записаны ({}), работают дефолты", e.getMessage());
        }
    }
}