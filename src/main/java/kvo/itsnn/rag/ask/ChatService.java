package kvo.itsnn.rag.ask;

import com.github.benmanes.caffeine.cache.Cache;
import kvo.itsnn.rag.config.LlmProperties;
import kvo.itsnn.rag.llm.LlmClient;
import kvo.itsnn.rag.util.SessionCaches;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Чат с историей (в памяти; перезапуск = новые сессии).
 * Сессии — кэш Caffeine с TTL rag.llm.session-ttl-minutes (0 — бессрочно):
 * неактивная сессия удаляется, память не растёт бесконечно.
 */
@Service
public class ChatService {
    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private final LlmProperties cfg;
    private final AskService ask;
    private final Cache<String, ConcurrentLinkedDeque<LlmClient.Message>> sessions;

    public ChatService(LlmProperties cfg, AskService ask) {
        this.cfg = cfg; this.ask = ask;
        this.sessions = SessionCaches.withTtl(cfg.sessionTtlMinutes());
    }

    public record ChatReply(String sessionId, String answer, List<AskService.Source> sources) {}

    /**
     * Единая нормализация sessionId для ВСЕХ методов: strip, пусто/blank → null.
     * chat() и deleteMessages() гарантированно обращаются к одному и тому же ключу кэша,
     * даже если клиент присылает id с пробелами по краям.
     */
    private static String normSid(String sessionId) {
        if (sessionId == null) return null;
        String s = sessionId.strip();
        return s.isEmpty() ? null : s;
    }

    public ChatReply chat(String sessionId, String message, String classifier) {
        String sid = normSid(sessionId);
        if (sid == null) sid = UUID.randomUUID().toString();
        var history = sessions.get(sid, k -> new ConcurrentLinkedDeque<>());
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
     * Возвращает число удалённых (0 — сессии нет / реплика уже выпала при обрезке historyTurns
     * или истёк TTL сессии — история для LLM начнётся заново).
     */
    public int deleteMessages(String sessionId, String role, String content) {
        String sid = normSid(sessionId);
        var history = sid == null ? null : sessions.getIfPresent(sid);
        if (history == null) return 0;
        boolean all = content == null || content.isBlank();
        int before = history.size();
        history.removeIf(m -> role.equals(m.role()) && (all || content.equals(m.content())));
        int deleted = before - history.size();
        if (deleted > 0) log.info("CHAT: удалено {} реплик роли '{}' из истории", deleted, role);
        return deleted;
    }
}