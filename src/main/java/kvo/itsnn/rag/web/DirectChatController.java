package kvo.itsnn.rag.web;

import kvo.itsnn.rag.ask.DirectChatService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/rag")
public class DirectChatController {

    private final DirectChatService chat;

    public DirectChatController(DirectChatService chat) {
        this.chat = chat;
    }

    public record DirectChatRequest(String sessionId, String message) {}

    /** Прямой диалог с LLM. Сбои LLM → IllegalStateException → 502 через ApiExceptionHandler. */
    @PostMapping("/direct-chat")
    public DirectChatService.DirectReply chat(@RequestBody DirectChatRequest req) {
        if (req.message() == null || req.message().isBlank())
            throw new IllegalArgumentException("message пустой");
        return chat.chat(req.sessionId(), req.message().strip());
    }

    /** Прикрепить файл к сессии свободного чата (multipart: sessionId + file).
     *  Текст извлекается на сервере (pdf-скан → OCR) и участвует в каждом следующем
     *  вопросе, пока файл не открепить. Ответ — актуальный список файлов сессии. */
    @PostMapping(value = "/direct-chat/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public List<DirectChatService.FileInfo> addFile(@RequestParam("sessionId") String sessionId,
                                                    @RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty())
            throw new IllegalArgumentException("файл не прикреплён (multipart-поле 'file')");
        try {
            return chat.addFile(sessionId, file.getOriginalFilename(), file.getBytes());
        } catch (IOException e) {
            throw new IllegalArgumentException("не удалось прочитать прикреплённый файл: " + e.getMessage(), e);
        }
    }

    /** Файлы сессии — восстановление списка в UI после перезагрузки страницы. */
    @GetMapping("/direct-chat/files")
    public List<DirectChatService.FileInfo> files(@RequestParam("sessionId") String sessionId) {
        return chat.files(sessionId);
    }

    /** Открепить файл по имени. */
    @DeleteMapping("/direct-chat/files")
    public List<DirectChatService.FileInfo> remove(@RequestParam("sessionId") String sessionId,
                                                   @RequestParam("name") String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name пустой");
        return chat.removeFile(sessionId, name);
    }
}