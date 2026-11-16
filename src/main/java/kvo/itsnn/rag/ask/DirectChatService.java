package kvo.itsnn.rag.ask;

import kvo.itsnn.rag.config.LlmProperties;
import kvo.itsnn.rag.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Прямой чат с LLM (rag.llm.base-url из application.yaml) — без эмбеддингов,
 * Chroma и rag_control. Отдельные сессии от RAG-чата: переключение режима
 * в UI не смешивает истории (ключи в разных map'ах).
 * System-промпт — в rag_control (ключ direct_chat_prompt), см. DirectChatPromptProvider.
 */
@Service
public class DirectChatService {

    private static final Logger log = LoggerFactory.getLogger(DirectChatService.class);

    private final LlmProperties cfg;
    private final LlmClient llm;
    private final DirectChatPromptProvider prompts;   // промпт свободного чата из rag_control
    private final Map<String, ConcurrentLinkedDeque<LlmClient.Message>> sessions = new ConcurrentHashMap<>();

    public DirectChatService(LlmProperties cfg, LlmClient llm, DirectChatPromptProvider prompts) {
        this.cfg = cfg;
        this.llm = llm;
        this.prompts = prompts;
        log.info("DirectChatService: свободный чат с LLM ({}), история {} реплик",
                cfg.model(), cfg.historyTurns());
    }

    public record DirectReply(String sessionId, String answer) {}

    public DirectReply chat(String sessionId, String message) {
        String sid = (sessionId == null || sessionId.isBlank()) ? UUID.randomUUID().toString() : sessionId;
        var history = sessions.computeIfAbsent(sid, k -> new ConcurrentLinkedDeque<>());

        List<LlmClient.Message> messages = new ArrayList<>();
        messages.add(LlmClient.Message.system(prompts.prompt()));
        messages.addAll(history);
        messages.add(LlmClient.Message.user(message));

        long t0 = System.currentTimeMillis();
        String answer = llm.chat(messages);
        log.info("DIRECT: вопрос='{}', LLM {} мс", message, System.currentTimeMillis() - t0);

        history.add(LlmClient.Message.user(message));
        String a = answer == null ? "" : answer.strip();
        if (!a.isBlank()) history.add(LlmClient.Message.assistant(a));
        while (history.size() > cfg.historyTurns() * 2) history.pollFirst();
        return new DirectReply(sid, answer);
    }
}