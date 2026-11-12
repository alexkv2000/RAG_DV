package kvo.itsnn.rag.web;

import kvo.itsnn.rag.ask.AskService;
import kvo.itsnn.rag.ask.ChatService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/rag")
public class RagAskController {

    private final AskService ask;
    private final ChatService chat;

    public RagAskController(AskService ask, ChatService chat) { this.ask = ask; this.chat = chat; }

    public record AskRequest(String query) {}
    public record ChatRequest(String sessionId, String message) {}

    @PostMapping("/ask")
    public AskService.AskResult ask(@RequestBody AskRequest req) {
        if (req.query() == null || req.query().isBlank()) throw new IllegalArgumentException("query пустой");
        return ask.ask(req.query().strip(), List.of());
    }

    @PostMapping("/chat")
    public ChatService.ChatReply chat(@RequestBody ChatRequest req) {
        if (req.message() == null || req.message().isBlank()) throw new IllegalArgumentException("message пустой");
        return chat.chat(req.sessionId(), req.message().strip());
    }
}