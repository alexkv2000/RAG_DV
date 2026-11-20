package kvo.itsnn.rag.ask;

import com.github.benmanes.caffeine.cache.Cache;
import kvo.itsnn.rag.config.LlmProperties;
import kvo.itsnn.rag.ingest.ContentLoader;
import kvo.itsnn.rag.ingest.TextExtractor;
import kvo.itsnn.rag.llm.LlmClient;
import kvo.itsnn.rag.util.SessionCaches;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Прямой чат с LLM (rag.llm.base-url) — без эмбеддингов, Chroma и rag_control.
 * Отдельные сессии от RAG-чата.
 * Сессии — кэш Caffeine с TTL rag.llm.session-ttl-minutes (0 — бессрочно):
 * брошенная сессия удаляется из памяти ВМЕСТЕ с прикреплёнными файлами.
 * Прикреплённые файлы: текст извлекается тем же TextExtractor, что и индексация
 * (pdf-скан без текстового слоя автоматически уходит в OCR), обрезается до лимита
 * и подставляется в КАЖДЫЙ вопрос сессии, пока файл не открепят. В историю реплик
 * текст файла не пишется — иначе каждый следующий вопрос дублировал бы все файлы.
 * Четыре лимита файлов, все — rag.llm.*: direct-max-file-mb — размер одного файла
 * (больше 64 задавать нельзя — раньше отклонит multipart-потолок UploadConfig);
 * direct-max-files — файлов на сессию; direct-max-file-chars — обрезка текста
 * одного файла; direct-max-session-tokens — оценочный суммарный бюджет токенов
 * ВСЕХ файлов сессии (файлы уходят в каждый вопрос вместе с историей и резервом
 * max-tokens, а контекст модели конечен — 131 072 токена у Qwen3).
 */
@Service
public class DirectChatService {

    private static final Logger log = LoggerFactory.getLogger(DirectChatService.class);

    /** Multipart-потолок UploadConfig (64 МБ): файл больше него Tomcat отклоняет раньше
     *  сервиса — 413 вместо понятного сообщения. Число дублируется сознательно: прямая
     *  зависимость на бин загрузки здесь не нужна, константа нужна только для WARN'а. */
    private static final long UPLOAD_CEILING_BYTES = 64L * 1024 * 1024;

    /** Форматы, которые умеет TextExtractor (картинки и pdf-сканы — через OCR). */
    private static final Set<String> ALLOWED_EXT = Set.of(
            "pdf", "doc", "docx", "rtf", "txt", "csv", "html", "htm",
            "xlsx", "xls", "xlsm", "msg", "png", "jpg", "jpeg", "tif", "tiff");

    /** Символов текста одного файла, уходящих в LLM; источник — rag.llm.direct-max-file-chars. */
    private final int maxFileChars;

    /** Оценочный бюджет токенов на ВСЕ файлы сессии; источник — rag.llm.direct-max-session-tokens.
     *  Это не лог-значение, а сам лимит: с ним putFile() сверяет сумму токенов файлов. */
    private final int maxSessionTokens;

    /** Потолок РАЗМЕРА одного файла, байты; источник — rag.llm.direct-max-file-mb. */
    private final long maxFileBytes;

    /** Файлов на сессию; источник — rag.llm.direct-max-files. */
    private final int maxFiles;

    private final LlmProperties cfg;
    private final LlmClient llm;
    private final DirectChatPromptProvider prompts;
    private final TextExtractor extractor;
    private final Cache<String, Session> sessions;

    /**
     * Сессия: история реплик + прикреплённые файлы.
     * Доступ к files — только через synchronized-методы (монитор — сама сессия):
     * Sonar запрещает synchronized-блоки по параметру/локальной переменной (S2445 —
     * монитор должен быть final-объектом), а цепочка «проверка лимита файлов +
     * проверка бюджета токенов + put» обязана выполняться атомарно целиком.
     */
    private static final class Session {
        final ConcurrentLinkedDeque<LlmClient.Message> history = new ConcurrentLinkedDeque<>();
        /** Ключ — имя файла в нижнем регистре; LinkedHashMap — порядок загрузки. */
        private final Map<String, AttachedFile> files = new LinkedHashMap<>();

        /** Файлов в сессии. */
        synchronized int filesCount() {
            return files.size();
        }

        /** Копия файлов для сборки промпта: итерация вне блокировки безопасна. */
        synchronized List<AttachedFile> filesSnapshot() {
            return new ArrayList<>(files.values());
        }

        /** Строка выдачи для UI. */
        synchronized List<FileInfo> filesInfo() {
            return files.values().stream()
                    .map(f -> new FileInfo(f.name(), f.text().length())).toList();
        }

        /** Сумма оценок токенов файлов сессии (для лога после attach). */
        synchronized int usedTokens() {
            int sum = 0;
            for (AttachedFile f : files.values()) sum += f.tokens();
            return sum;
        }

        /** Добавить или заменить файл; АТОМАРНО проверяет лимит количества и бюджет
         *  токенов (заменяемый файл из суммы исключается). Нарушение лимита —
         *  IllegalArgumentException → HTTP 400 с текстом для UI. */
        synchronized void putFile(String name, String text, int tokens, int maxFiles, int maxTokens) {
            String k = name.toLowerCase(Locale.ROOT);
            if (!files.containsKey(k) && files.size() >= maxFiles) {
                throw new IllegalArgumentException("достигнут лимит файлов сессии (" + maxFiles
                        + ") — открепите один, чтобы добавить новый");
            }
            int rest = 0;                          // бюджет уже прикреплённых файлов
            for (Map.Entry<String, AttachedFile> e : files.entrySet()) {
                if (!e.getKey().equals(k)) rest += e.getValue().tokens();
            }
            if (rest + tokens > maxTokens) {
                throw new IllegalArgumentException("'" + name + "': с уже прикреплёнными файлами ≈"
                        + tk(rest + tokens) + " токенов — больше бюджета сессии " + tk(maxTokens)
                        + " (rag.llm.direct-max-session-tokens). Открепите один из файлов"
                        + " или прикрепите меньший");
            }
            files.put(k, new AttachedFile(name, text, tokens));
        }

        /** Открепить файл по имени; несуществующее имя — молча no-op. */
        synchronized void removeFile(String name) {
            files.remove(name.toLowerCase(Locale.ROOT));
        }

        /** «89 тыс.» — формат токенов в сообщении об ошибке. */
        private static String tk(int tokens) {
            return Math.round(tokens / 1000.0) + " тыс.";
        }
    }

    /** Файл, прикреплённый к сессии: имя + извлечённый (обрезанный) текст + оценка токенов. */
    record AttachedFile(String name, String text, int tokens) {}

    /** Строка выдачи для UI. */
    public record FileInfo(String name, int chars) {}

    public DirectChatService(LlmProperties cfg, LlmClient llm,
                             DirectChatPromptProvider prompts, TextExtractor extractor) {
        this.cfg = cfg;
        this.llm = llm;
        this.prompts = prompts;
        this.extractor = extractor;
        this.maxFileChars = Math.max(1, cfg.directMaxFileChars());
        this.maxSessionTokens = Math.max(1, cfg.directMaxSessionTokens());
        this.maxFileBytes = Math.max(1, cfg.directMaxFileMb()) * 1024L * 1024L;
        this.maxFiles = Math.max(1, cfg.directMaxFiles());
        if (maxFileBytes > UPLOAD_CEILING_BYTES) {
            log.warn("DirectChatService: rag.llm.direct-max-file-mb={} выше multipart-потолка {} МБ "
                            + "(UploadConfig) — Tomcat будет отклонять такие файлы раньше, с HTTP 413",
                    cfg.directMaxFileMb(), UPLOAD_CEILING_BYTES / 1024 / 1024);
        }
        this.sessions = SessionCaches.withTtl(cfg.sessionTtlMinutes());
        log.info("DirectChatService: свободный чат с LLM ({}), история {} реплик, файлы: {} шт. до {} МБ, "
                        + "текст {} симв./файл, бюджет сессии {} токенов, TTL сессии {} (rag.llm.direct-max-file-mb "
                        + "/ direct-max-files / direct-max-file-chars / direct-max-session-tokens / session-ttl-minutes)",
                cfg.model(), cfg.historyTurns(), maxFiles, maxFileBytes / 1024 / 1024,
                maxFileChars, maxSessionTokens,
                cfg.sessionTtlMinutes() <= 0 ? "∞" : cfg.sessionTtlMinutes() + " мин");
    }

    public record DirectReply(String sessionId, String answer) {}

    public DirectReply chat(String sessionId, String message) {
        String sid = (sessionId == null || sessionId.isBlank()) ? UUID.randomUUID().toString() : sessionId;
        Session s = sessions.get(sid, k -> new Session());

        List<LlmClient.Message> messages = new ArrayList<>();
        messages.add(LlmClient.Message.system(prompts.prompt()));
        messages.addAll(s.history);
        messages.add(LlmClient.Message.user(withFiles(s, message)));   // файлы — только в текущий вопрос

        long t0 = System.currentTimeMillis();
        String answer = llm.chat(messages);
        log.info("DIRECT: вопрос='{}', файлов={}, LLM {} мс",
                message, s.filesCount(), System.currentTimeMillis() - t0);

        s.history.add(LlmClient.Message.user(message));                // в историю — без текста файлов
        String a = answer == null ? "" : answer.strip();
        if (!a.isBlank()) s.history.add(LlmClient.Message.assistant(a));
        while (s.history.size() > cfg.historyTurns() * 2) s.history.pollFirst();
        return new DirectReply(sid, answer);
    }

    /* ---------- прикреплённые файлы ---------- */

    /** Загрузка: извлечь текст и положить в сессию. Возвращает полный список файлов сессии. */
    public List<FileInfo> addFile(String sessionId, String fileName, byte[] bytes) {
        String sid = (sessionId == null || sessionId.isBlank()) ? UUID.randomUUID().toString() : sessionId;
        String name = fileName == null || fileName.isBlank() ? "файл" : fileName.strip();
        String ext = check(name, bytes.length);
        String text = extractText(name, ext, bytes);
        int tokens = estimateTokens(text);

        Session s = sessions.get(sid, k -> new Session());
        s.putFile(name, text, tokens, maxFiles, maxSessionTokens);
        log.info("DIRECT: файл '{}' прикреплён к сессии ({} симв. ≈ {} ток., сессия {}/{})",
                name, text.length(), tokens, s.usedTokens(), maxSessionTokens);
        return files(sid);
    }

    /** Открепить файл по имени; несуществующее имя — молча no-op. */
    public List<FileInfo> removeFile(String sessionId, String name) {
        Session s = sessions.getIfPresent(normSid(sessionId));
        if (s != null && name != null && !name.isBlank()) {
            s.removeFile(name.strip());
        }
        return files(sessionId);
    }

    /** Список файлов сессии (восстановление UI после перезагрузки страницы). */
    public List<FileInfo> files(String sessionId) {
        Session s = sessions.getIfPresent(normSid(sessionId));
        return s == null ? List.of() : s.filesInfo();
    }

    /** Удаление реплик из истории сессии — аналог ChatService.deleteMessages. */
    public int deleteMessages(String sessionId, String role, String content) {
        Session s = sessions.getIfPresent(normSid(sessionId));
        if (s == null) return 0;
        boolean all = content == null || content.isBlank();
        int before = s.history.size();
        s.history.removeIf(m -> role.equals(m.role()) && (all || content.equals(m.content())));
        int deleted = before - s.history.size();
        if (deleted > 0) log.info("DIRECT: удалено {} реплик роли '{}' из истории", deleted, role);
        return deleted;
    }

    /* ---------- внутреннее ---------- */

    private static String normSid(String sessionId) {
        return sessionId == null ? "" : sessionId.strip();
    }

    /** Оценка токенов Qwen: кириллица ≈ 2 симв./токен, остальное (код, латиница,
     *  цифры, разметка) ≈ 3,8. Калибровка 19.11.2026: дамп исходников 340 473 симв.
     *  → 89 433 токенов (3,82) — оценка сходится с замером с точностью ~0,2 %.
     *  Нужна только для бюджета attach'а: запас в лимите покрывает погрешность. */
    private static int estimateTokens(String text) {
        int cyr = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x0400 && c <= 0x04FF) cyr++;     // кириллица, включая ё
        }
        return Math.max(1, (int) Math.round(cyr / 2.0 + (text.length() - cyr) / 3.8));
    }

    /** Тексты всех файлов сессии одним блоком; файлов нет — вопрос уходит как есть. */
    private String withFiles(Session s, String message) {
        List<AttachedFile> fs = s.filesSnapshot();
        if (fs.isEmpty()) return message;
        StringBuilder sb = new StringBuilder("""
                Пользователь прикрепил к диалогу файлы (их текст ниже). Опирайся на них при ответе и называй имя файла, когда ссылаешься на их содержимое.

                """);
        for (AttachedFile f : fs) {
            sb.append("=== Начало файла: ").append(f.name()).append(" ===\n")
                    .append(f.text())
                    .append("\n=== Конец файла: ").append(f.name()).append(" ===\n\n");
        }
        return sb + "Вопрос пользователя: " + message;
    }

    /** Проверки файла: не пустой, не больше rag.llm.direct-max-file-mb, допустимый формат. */
    private String check(String name, long size) {
        if (size == 0) throw new IllegalArgumentException("файл '" + name + "' пустой");
        if (size > maxFileBytes) throw new IllegalArgumentException(
                "файл '" + name + "' больше " + maxFileBytes / 1024 / 1024
                        + " МБ (rag.llm.direct-max-file-mb)");
        int d = name.lastIndexOf('.');
        String ext = d < 0 || d == name.length() - 1 ? "" : name.substring(d + 1).toLowerCase(Locale.ROOT);
        if (!ALLOWED_EXT.contains(ext)) throw new IllegalArgumentException(
                "формат '" + (ext.isEmpty() ? "?" : ext) + "' не поддерживается; допустимы: "
                        + String.join(", ", ALLOWED_EXT.stream().sorted().toList()));
        return ext;
    }

    /** Текст файла тем же extractor'ом, что и индексация (OCR сканов внутри). */
    private String extractText(String name, String ext, byte[] bytes) {
        String text;
        try {
            text = extractor.extract(new ContentLoader.LoadedFile(UUID.randomUUID(), name, ext, bytes));
        } catch (IllegalArgumentException e) {          // OCR выключен, пустой файл и пр. — вина запроса
            throw new IllegalArgumentException(name + ": " + e.getMessage(), e);
        } catch (Exception e) {                          // битый файл
            throw new IllegalArgumentException(name + ": не удалось извлечь текст ("
                    + e.getMessage() + ")", e);
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(name + ": файл не содержит извлекаемого текста");
        }
        text = text.strip();
        return text.length() > maxFileChars
                ? text.substring(0, maxFileChars) + "\n…[текст файла обрезан]" : text;
    }
}