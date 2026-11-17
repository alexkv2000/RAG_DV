package kvo.itsnn.rag.web;

import kvo.itsnn.rag.extract.ExtractDirectory;
import kvo.itsnn.rag.extract.ExtractService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/rag")
public class ExtractController {

    private final ExtractService extract;
    private final ExtractDirectory directory;
    public ExtractController(ExtractService extract, ExtractDirectory directory) {
        this.extract = extract;
        this.directory = directory;
    }

    /**
     * Интеграция: файл (doc|docx|pdf|pdf-скан|rtf|txt) → текст → LLM → JSON по схеме
     * из dbo.rag_control (extract_json_schema / extract_system_prompt).
     * multipart/form-data, поле 'file'. Ответ — готовый JSON; поля без значения — "".
     */
    @PostMapping(value = "/extract", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, String> extract(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("файл не прикреплён (multipart-поле 'file')");
        }
        try {
            return extract.extract(file.getOriginalFilename(), file.getBytes());
        } catch (IOException e) {
            throw new IllegalArgumentException("не удалось прочитать прикреплённый файл: " + e.getMessage(), e);
        }
    }

    /** Текущая схема полей — чтобы интегратор знал, какие ключи ждать. */
    @GetMapping("/extract/schema")
    public Map<String, Object> schema() {
        return extract.schema();
    }

    /** Размеры справочников и время загрузки кэша (админ-панель). */
    @GetMapping("/extract/dictionaries")
    public Map<String, Object> dictionaries() {
        return directory.stats();
    }
}