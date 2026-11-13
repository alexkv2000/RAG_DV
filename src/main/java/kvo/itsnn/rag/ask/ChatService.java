package kvo.itsnn.rag.ask;

import kvo.itsnn.rag.llm.LlmClient;
import org.springframework.stereotype.Service;
import kvo.itsnn.rag.config.LlmProperties;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/** Чат с историей (в памяти; перезапуск = новые сессии). */
@Service
public class ChatService {

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
}