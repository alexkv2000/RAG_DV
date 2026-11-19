package kvo.itsnn.rag.ask;

import kvo.itsnn.rag.config.LlmProperties;
import kvo.itsnn.rag.ingest.ContentLoader;
import kvo.itsnn.rag.ingest.TextExtractor;
import kvo.itsnn.rag.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Прямой чат с LLM (rag.llm.base-url) — без эмбеддингов, Chroma и rag_control.
 * Отдельные сессии от RAG-чата.
 *
 * Прикреплённые файлы: текст извлекается тем же TextExtractor, что и индексация
 * (pdf-скан без текстового слоя автоматически уходит в OCR), обрезается до лимита
 * и подставляется в КАЖДЫЙ вопрос сессии, пока файл не открепят. В историю реплик
 * текст файла не пишется — иначе каждый следующий вопрос дублировал бы все файлы.
 */
@Service
public class DirectChatService {

    private static final Logger log = LoggerFactory.getLogger(DirectChatService.class);

    /** Форматы, которые умеет TextExtractor (картинки и pdf-сканы — через OCR). */
    private static final Set<String> ALLOWED_EXT = Set.of(
            "pdf", "doc", "docx", "rtf", "txt", "csv", "html", "htm",
            "xlsx", "xls", "xlsm", "msg", "png", "jpg", "jpeg", "tif", "tiff");
    private static final long MAX_FILE_BYTES = 32L * 1024 * 1024;
    /** Символов текста одного файла, уходящих в LLM (3 файла ≈ 36 тыс. знаков контекста). */
    private static final int MAX_FILE_CHARS = 12_000;
    private static final int MAX_FILES = 3;

    private final LlmProperties cfg;
    private final LlmClient llm;
    private final DirectChatPromptProvider prompts;
    private final TextExtractor extractor;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    /** Сессия: история реплик + прикреплённые файлы (LinkedHashMap — порядок загрузки). */
    private static final class Session {
        final ConcurrentLinkedDeque<LlmClient.Message> history = new ConcurrentLinkedDeque<>();
        /** Ключ — имя файла в нижнем регистре; доступ синхронизирован по Session. */
        final Map<String, AttachedFile> files = new LinkedHashMap<>();
    }

    /** Файл, прикреплённый к сессии: имя + извлечённый (и обрезанный) текст. */
    record AttachedFile(String name, String text) {}

    /** Строка выдачи для UI. */
    public record FileInfo(String name, int chars) {}

    public DirectChatService(LlmProperties cfg, LlmClient llm,
                             DirectChatPromptProvider prompts, TextExtractor extractor) {
        this.cfg = cfg;
        this.llm = llm;
        this.prompts = prompts;
        this.extractor = extractor;
        log.info("DirectChatService: свободный чат с LLM ({}), история {} реплик, файлы: {} шт. по {} симв.",
                cfg.model(), cfg.historyTurns(), MAX_FILES, MAX_FILE_CHARS);
    }

    public record DirectReply(String sessionId, String answer) {}

    public DirectReply chat(String sessionId, String message) {
        String sid = (sessionId == null || sessionId.isBlank()) ? UUID.randomUUID().toString() : sessionId;
        Session s = sessions.computeIfAbsent(sid, k -> new Session());

        List<LlmClient.Message> messages = new ArrayList<>();
        messages.add(LlmClient.Message.system(prompts.prompt()));
        messages.addAll(s.history);
        messages.add(LlmClient.Message.user(withFiles(s, message)));   // файлы — только в текущий вопрос

        long t0 = System.currentTimeMillis();
        String answer = llm.chat(messages);
        log.info("DIRECT: вопрос='{}', файлов={}, LLM {} мс",
                message, filesCount(s), System.currentTimeMillis() - t0);

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

        Session s = sessions.computeIfAbsent(sid, k -> new Session());
        synchronized (s) {
            if (!s.files.containsKey(key(name)) && s.files.size() >= MAX_FILES) {
                throw new IllegalArgumentException("к сессии уже прикреплено " + MAX_FILES
                        + " файла — открепите один, чтобы добавить новый");
            }
            s.files.put(key(name), new AttachedFile(name, text));
        }
        log.info("DIRECT: файл '{}' прикреплён к сессии ({} симв. текста)", name, text.length());
        return files(sid);
    }

    /** Открепить файл по имени; несуществующее имя — молча no-op. */
    public List<FileInfo> removeFile(String sessionId, String name) {
        String sid = normSid(sessionId);
        Session s = sessions.get(sid);
        if (s != null && name != null && !name.isBlank()) {
            synchronized (s) {
                s.files.remove(key(name.strip()));
            }
        }
        return files(sid);
    }

    /** Список файлов сессии (восстановление UI после перезагрузки страницы). */
    public List<FileInfo> files(String sessionId) {
        Session s = sessions.get(normSid(sessionId));
        if (s == null) return List.of();
        synchronized (s) {
            return s.files.values().stream()
                    .map(f -> new FileInfo(f.name(), f.text().length())).toList();
        }
    }

    /* ---------- внутреннее ---------- */

    private static String normSid(String sessionId) {
        return sessionId == null ? "" : sessionId.strip();
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static int filesCount(Session s) {
        synchronized (s) {
            return s.files.size();
        }
    }

    /** Тексты всех файлов сессии одним блоком; файлов нет — вопрос уходит как есть. */
    private String withFiles(Session s, String message) {
        List<AttachedFile> fs;
        synchronized (s) {
            if (s.files.isEmpty()) return message;
            fs = new ArrayList<>(s.files.values());
        }
        StringBuilder sb = new StringBuilder("Пользователь прикрепил к диалогу файлы (их текст ниже). "
                + "Опирайся на них при ответе и называй имя файла, когда ссылаешься на их содержимое.\n\n");
        for (AttachedFile f : fs) {
            sb.append("=== Начало файла: ").append(f.name()).append(" ===\n")
                    .append(f.text())
                    .append("\n=== Конец файла: ").append(f.name()).append(" ===\n\n");
        }
        return sb + "Вопрос пользователя: " + message;
    }

    private static String check(String name, long size) {
        if (size == 0) throw new IllegalArgumentException("файл '" + name + "' пустой");
        if (size > MAX_FILE_BYTES) throw new IllegalArgumentException(
                "файл '" + name + "' больше " + MAX_FILE_BYTES / 1024 / 1024 + " МБ");
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
        return text.length() > MAX_FILE_CHARS
                ? text.substring(0, MAX_FILE_CHARS) + "\n…[текст файла обрезан]" : text;
    }
}