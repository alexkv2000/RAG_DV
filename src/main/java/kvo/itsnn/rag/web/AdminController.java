package kvo.itsnn.rag.web;

import kvo.itsnn.rag.config.RagProperties;
import kvo.itsnn.rag.index.VectorIndex;
import kvo.itsnn.rag.refsync.RagScheduler;
import kvo.itsnn.rag.refsync.RefRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.*;

/** Управление индексацией и настройками: /stats, /pause, /resume, /reindex, /rebuild,
 *  /nightly-check, /errors, /night-window, /settings. */
@RestController
@RequestMapping("/api/rag/admin")
public class AdminController {
    private static final Logger log = LoggerFactory.getLogger(AdminController.class);
    private static final String RESULT = "result";
    private static final String SAVED = "saved";
    private static final String NIGHT_START = "nightStart";
    private static final String NIGHT_END = "nightEnd";
    private static final String ERROR = "error";

    private final RefRepository refs;
    private final VectorIndex index;
    private final JdbcTemplate jdbc;
    private final RagProperties props;
    private final RagScheduler scheduler;

    public AdminController(RefRepository refs, VectorIndex index, JdbcTemplate jdbc,
                           RagProperties props, RagScheduler scheduler) {
        this.refs = refs;
        this.index = index;
        this.jdbc = jdbc;
        this.props = props;
        this.scheduler = scheduler;
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>(refs.statusCounts());
        out.put("indexSize", index.size());
        out.put("paused", refs.ingestPaused());
        out.put("nightly", refs.loadNightlyCheck());   // null → в UI «ещё не выполнялась»
        return out;
    }

    /** Текущее ночное окно (rag_control; резерв — yaml) и признак «сейчас ночь». */
    @GetMapping("/night-window")
    public Map<String, Object> nightWindow() {
        LocalTime start = refs.ctrlTime(RefRepository.NIGHT_START_KEY, props.nightStart());
        LocalTime end = refs.ctrlTime(RefRepository.NIGHT_END_KEY, props.nightEnd());
        LocalTime now = LocalTime.now();
        boolean night = !now.isBefore(start) || now.isBefore(end);
        return Map.of(NIGHT_START, start.toString(), NIGHT_END, end.toString(), "night", night);
    }

    public record NightWindowRequest(String nightStart, String nightEnd) {}

    /** Сохранение окна из админ-панели; применяется ближайшим тиком планировщика. */
    @PostMapping("/night-window")
    public Map<String, Object> setNightWindow(@RequestBody NightWindowRequest req) {
        LocalTime start = parseTime(req.nightStart());
        LocalTime end = parseTime(req.nightEnd());
        if (start == null || end == null)
            throw new IllegalArgumentException("время должно быть в формате HH:mm, например 23:00");
        if (start.equals(end))
            throw new IllegalArgumentException("начало и конец окна не должны совпадать");
        refs.setNightWindow(start, end);
        return Map.of(RESULT, SAVED, NIGHT_START, start.toString(), NIGHT_END, end.toString());
    }

    private static LocalTime parseTime(String s) {
        if (s == null) return null;
        try {
            return LocalTime.parse(s.strip());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** Текущие настройки скорости (rag_control; резерв — дефолты кода). */
    @GetMapping("/settings")
    public Map<String, Object> schedulerSettings() {
        return Map.of(
                "pollIntervalSec", refs.ctrlInt(RefRepository.POLL_INTERVAL_KEY, (int) props.pollInterval().toSeconds()),
                "ingestBatchDay", refs.ctrlInt(RefRepository.INGEST_BATCH_DAY_KEY, props.ingestBatchDay()),
                "ingestBatchNight", refs.ctrlInt(RefRepository.INGEST_BATCH_NIGHT_KEY, props.ingestBatchNight()),
                "dayHourLimit", refs.ctrlInt(RefRepository.DAY_HOUR_LIMIT_KEY, props.dayHourLimit()));
    }

    public record SettingsRequest(Integer pollIntervalSec, Integer ingestBatchDay,
                                  Integer ingestBatchNight, Integer dayHourLimit) {}

    @PostMapping("/settings")
    public Map<String, Object> setSchedulerSettings(@RequestBody SettingsRequest req) {
        int poll = intInRange(req.pollIntervalSec(), "Опрос (сек)", 1, 600);
        int day = intInRange(req.ingestBatchDay(), "Batch день", 1, 100);
        int night = intInRange(req.ingestBatchNight(), "Batch ночь", 1, 100);
        int limit = intInRange(req.dayHourLimit(), "Лимит днём (карт/час)", 1, 10_000);
        refs.setCtrlInt(RefRepository.POLL_INTERVAL_KEY, poll);
        refs.setCtrlInt(RefRepository.INGEST_BATCH_DAY_KEY, day);
        refs.setCtrlInt(RefRepository.INGEST_BATCH_NIGHT_KEY, night);
        refs.setCtrlInt(RefRepository.DAY_HOUR_LIMIT_KEY, limit);
        return Map.of(RESULT, SAVED, "pollIntervalSec", poll, "ingestBatchDay", day,
                "ingestBatchNight", night, "dayHourLimit", limit);
    }

    private static int intInRange(Integer v, String name, int min, int max) {
        if (v == null) throw new IllegalArgumentException("не задано: " + name);
        if (v < min || v > max) throw new IllegalArgumentException(name + " — допустимо от " + min + " до " + max);
        return v;
    }

    @PostMapping("/reindex/{guid}")
    public Map<String, String> reindex(@PathVariable UUID guid) {
        refs.forceReindex(guid);
        return Map.of(RESULT, "queued");
    }

    @PostMapping("/rebuild")
    public Map<String, String> rebuild() {
        // пауза на время пересборки: переиндексация документа во время reload
        // может вернуть в Chroma только что удалённые id (снова diff)
        boolean wasPaused = refs.ingestPaused();
        if (!wasPaused) refs.setPaused(true);
        try {
            index.reload();            // пересинхронизация Chroma из резервной копии в MSSQL
        } finally {
            if (!wasPaused) refs.setPaused(false);
        }
        return Map.of(RESULT, "resynced", "size", String.valueOf(index.size()));
    }

    /** Ручная сверка Chroma↔MSSQL — то же, что ночная в 03:30.
     *  Возвращает свежий nightly_embedding; при расхождении гоняет reload (может занять минуты). */
    @PostMapping("/nightly-check")
    public Map<String, Object> nightlyCheck() {
        boolean ran = scheduler.nightlyConsistency();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(RESULT, ran ? "done" : "busy");
        out.put("nightly", refs.loadNightlyCheck());
        return out;
    }

    /** Последние ошибки индексации; строка = файл карточки, ошибка у карточки общая. */
    @GetMapping("/errors")
    public List<Map<String, Object>> errors() {
        return jdbc.query("""
                SELECT TOP (20) document_guid, doc_name, status, retry_count,
                       LEFT(error_msg, 300) AS error, updated_at
                FROM dbo.rag_document_ref
                WHERE status = 'E' OR error_msg IS NOT NULL
                ORDER BY updated_at DESC
                """, (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("guid", rs.getString(1));
            m.put("name", rs.getString(2));
            m.put("status", rs.getString(3));
            m.put("retries", rs.getInt(4));
            m.put(ERROR, rs.getString(5) == null ? "" : rs.getString(5));
            m.put("at", rs.getTimestamp(6).toString());
            return m;
        });
    }
    /** Удаление КАРТОЧКИ с ошибкой из базы знаний RAG: все строки dbo.rag_document_ref
     *  этого document_guid (по одной на файл), чанки, эмбеддинги и векторы Chroma.
     *  Документ в DocsVision не трогается; если синхронизация снова создаст заявку,
     *  карточка вернётся как новая (status N). Индексируемую сейчас (P) не удаляем. */
    @DeleteMapping("/errors/{guid}")
    public Map<String, Object> deleteErrorCard(@PathVariable UUID guid) {
        record RefRow(String status, String errorMsg) {}
        List<RefRow> rows = jdbc.query(
                "SELECT status, error_msg FROM dbo.rag_document_ref WHERE document_guid = ?",
                (rs, i) -> new RefRow(rs.getString(1), rs.getString(2)), guid);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("карточка " + guid + " в dbo.rag_document_ref не найдена");
        }
        if (rows.stream().anyMatch(r -> "P".equals(r.status()))) {
            throw new IllegalArgumentException("карточка сейчас индексируется (P) — обновите список после завершения");
        }
        if (rows.stream().noneMatch(r -> "E".equals(r.status())
                || (r.errorMsg() != null && !r.errorMsg().isBlank()))) {
            throw new IllegalArgumentException("у карточки нет ошибок — удаляются только карточки из списка ошибок");
        }

        List<Long> chunkIds = refs.chunkIdsOf(guid);        // id для Chroma берём ДО удаления
        int removed = jdbc.update("""
                DELETE FROM dbo.rag_document_ref
                WHERE document_guid = ? AND status <> 'P'
                """, guid);
        if (removed == 0) {                                 // застали claim между проверкой и удалением
            throw new IllegalArgumentException("карточка сейчас индексируется (P) — попробуйте ещё раз");
        }
        jdbc.update("""
                DELETE FROM dbo.rag_embedding
                WHERE chunk_id IN (SELECT chunk_id FROM dbo.rag_chunk WHERE document_guid = ?)
                """, guid);
        jdbc.update("DELETE FROM dbo.rag_chunk WHERE document_guid = ?", guid);
        boolean vectorsClean = removeVectors(guid, chunkIds);
        log.info("ADMIN: удалена карточка с ошибкой {} — строк ref: {}, чанков: {}, Chroma: {}",
                guid, removed, chunkIds.size(), vectorsClean ? "вычищено" : "сироты (добьёт сверка)");
        return Map.of(RESULT, "deleted", "guid", guid.toString(),
                "refRows", removed, "chunks", chunkIds.size(),
                "vectors", vectorsClean ? "removed" : "orphaned");
    }

    /** Векторы Chroma — best-effort: сбой Chroma не должен рушить удаление карточки
     *  (сирот добьёт ночная сверка/пересборка, как в IndexCleanupService). */
    private boolean removeVectors(UUID guid, List<Long> chunkIds) {
        if (chunkIds.isEmpty()) return true;
        try {
            index.removeChunks(chunkIds);
            return true;
        } catch (Exception e) {
            log.warn("ADMIN: векторы Chroma {} не удалены ({})", guid, e.getMessage());
            return false;
        }
    }
    @PostMapping("/pause")
    public Map<String, String> pause() {
        refs.setPaused(true);
        return Map.of(RESULT, "paused");
    }

    @PostMapping("/resume")
    public Map<String, String> resume() {
        refs.setPaused(false);
        return Map.of(RESULT, "resumed");
    }
}