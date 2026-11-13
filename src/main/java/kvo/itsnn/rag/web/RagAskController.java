package kvo.itsnn.rag.web;

import kvo.itsnn.rag.ask.AskService;
import kvo.itsnn.rag.ask.ChatService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/rag")
public class RagAskController {

    private final AskService ask;
    private final ChatService chat;
    private final JdbcTemplate jdbc;

    public RagAskController(AskService ask, ChatService chat, JdbcTemplate jdbc) {
        this.ask = ask; this.chat = chat; this.jdbc = jdbc;
    }

    public record AskRequest(String query, String classifier) {}
    public record ChatRequest(String sessionId, String message, String classifier) {}

    @PostMapping("/ask")
    public AskService.AskResult ask(@RequestBody AskRequest req) {
        if (req.query() == null || req.query().isBlank()) throw new IllegalArgumentException("query пустой");
        return ask.ask(req.query().strip(), classifierOrNull(req.classifier()), List.of());
    }

    @PostMapping("/chat")
    public ChatService.ChatReply chat(@RequestBody ChatRequest req) {
        if (req.message() == null || req.message().isBlank()) throw new IllegalArgumentException("message пустой");
        return chat.chat(req.sessionId(), req.message().strip(), classifierOrNull(req.classifier()));
    }

    /** Выпадающий список для UI: только реально проиндексированные.
     *  cards  — уникальные карточки документов (document_guid);
     *  files  — уникальные карточки файлов (пара document_guid + row_id). */
    @GetMapping("/classifiers")
    public List<Map<String, Object>> classifiers() {
        return jdbc.query("""
            SELECT classifier,
                   COUNT(DISTINCT document_guid)                       AS cards,
                   COUNT(DISTINCT CONCAT(document_guid, '|', row_id)) AS files
            FROM dbo.rag_document_ref
            WHERE status = 'I' AND classifier IS NOT NULL
            GROUP BY classifier
            ORDER BY classifier
            """, (rs, i) -> Map.of(
                "classifier", rs.getString(1),
                "cards", rs.getInt(2),
                "files", rs.getInt(3)));
    }

    private static String classifierOrNull(String c) {
        return c == null || c.isBlank() ? null : c.strip();
    }
}