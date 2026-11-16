package kvo.itsnn.rag.web;

import kvo.itsnn.rag.ask.DirectChatService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/rag")
public class DirectChatController {

    private final DirectChatService chat;

    public DirectChatController(DirectChatService chat) {
        this.chat = chat;
    }

    public record DirectChatRequest(String sessionId, String message) {}

    /** Прямой диалог с LLM (rag.llm из application.yaml) — без embedding/Chroma/rag_control.
     *  Сбои LLM → IllegalStateException → 502 {"error": ...} через ApiExceptionHandler. */
    @PostMapping("/direct-chat")
    public DirectChatService.DirectReply chat(@RequestBody DirectChatRequest req) {
        if (req.message() == null || req.message().isBlank())
            throw new IllegalArgumentException("message пустой");
        return chat.chat(req.sessionId(), req.message().strip());
    }
}