package kvo.itsnn.rag.ask;

import kvo.itsnn.rag.llm.LlmClient;
import org.springframework.stereotype.Service;
import kvo.itsnn.rag.config.LlmProperties;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
/** Чат с историей (в памяти; перезапуск = новые сессии). */
@Service
public class ChatService {
    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private final LlmProperties cfg;
    private final AskService ask;
    private final Map<String, ConcurrentLinkedDeque<LlmClient.Message>> sessions = new ConcurrentHashMap<>();

    public ChatService(LlmProperties cfg, AskService ask) {
        this.cfg = cfg; this.ask = ask;
    }

    public record ChatReply(String sessionId, String answer, List<AskService.Source> sources) {}

    public ChatReply chat(String sessionId, String message, String classifier) {
        String sid = (sessionId == null || sessionId.isBlank()) ? UUID.randomUUID().toString() : sessionId;
        var history = sessions.computeIfAbsent(sid, k -> new ConcurrentLinkedDeque<>());
        AskService.AskResult r = ask.ask(message, classifier, List.copyOf(history));
        history.add(LlmClient.Message.user(message));
        String a = r.answer() == null ? "" : r.answer().strip();
        if (!a.isBlank() && !"undefined".equalsIgnoreCase(a)) {   // мусорный ответ в историю не попадёт
            history.add(LlmClient.Message.assistant(a));
        }
        while (history.size() > cfg.historyTurns() * 2) history.pollFirst();
        return new ChatReply(sid, r.answer(), r.sources());
    }
    public ChatReply chat(String sessionId, String message) { return chat(sessionId, message, null); }
    /**
     * Удаление реплик из истории сессии: content пуст → ВСЕ реплики роли
     * (кнопка «Только вопросы»/«Только ответы»), иначе — реплики с точным текстом (✕ на сообщении).
     * Лента на клиенте уже очищена — здесь убираем то, что видит LLM в следующих вопросах.
     * Возвращает число удалённых (0 — сессии нет / реплика уже выпала при обрезке historyTurns).
     */
    public int deleteMessages(String sessionId, String role, String content) {
        var history = sessionId == null || sessionId.isBlank()
                ? null : sessions.get(sessionId.strip());
        if (history == null) return 0;
        boolean all = content == null || content.isBlank();
        int before = history.size();
        history.removeIf(m -> role.equals(m.role()) && (all || content.equals(m.content())));
        int deleted = before - history.size();
        if (deleted > 0) log.info("CHAT: удалено {} реплик роли '{}' из истории", deleted, role);
        return deleted;
    }
}