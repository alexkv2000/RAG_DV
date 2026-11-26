package kvo.itsnn.rag.web;

import kvo.itsnn.rag.ask.ComparePromptProvider;
import kvo.itsnn.rag.ask.DirectChatPromptProvider;
import kvo.itsnn.rag.ask.DocSummaryService;
import kvo.itsnn.rag.ask.SystemPromptProvider;
import kvo.itsnn.rag.extract.ExtractPromptProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** Вкладка «Промты»: чтение/сохранение/сброс system-промптов в dbo.rag_control. */
@RestController
@RequestMapping("/api/rag/admin")
public class AdminPromptController {

    private static final String RESULT = "result";
    private static final String SAVED = "saved";
    private static final String KEY = "key";
    private static final tools.jackson.databind.ObjectMapper JSON =
            tools.jackson.databind.json.JsonMapper.builder().build();

    /** Редактируемые system-промпты (ключ rag_control → подпись). */
    private record PromptDef(String key, String title) {}

    /* XML-интеграция (файл → XML): потребителя в Java нет — значения лежат в dbo.rag_control
       и правятся на вкладке «Промты»; GET /prompts, POST /prompts и /prompts/reset идут
       по списку PROMPTS ниже и подхватывают новые ключи автоматически. Дефолтов в коде нет:
       «Дефолт из кода» просто удаляет строку из таблицы (значение станет пустым).
       Написание ключей — как в rag_control: 'extract_system_xml_promt' (проверьте вторую 'p'). */
    private static final String CTRL_KEY_XML_PROMPT = "extract_system_xml_promt";
    private static final String CTRL_KEY_XML_SCHEMA = "extract_xml_schema";

    private static final List<PromptDef> PROMPTS = List.of(
            new PromptDef(SystemPromptProvider.CTRL_KEY, "Системный — обычные вопросы"),
            new PromptDef(ComparePromptProvider.CTRL_KEY, "Сравнение — отличия версий"),
            new PromptDef(DocSummaryService.CTRL_KEY, "Суммарный — краткое содержание"),
            new PromptDef(DirectChatPromptProvider.CTRL_KEY, "Свободный чат — без базы знаний"),
            new PromptDef(ExtractPromptProvider.CTRL_KEY_PROMPT, "Извлечение — промпт (файл → JSON)"),
            new PromptDef(ExtractPromptProvider.CTRL_KEY_SCHEMA, "Извлечение — JSON-структура"),
            new PromptDef(CTRL_KEY_XML_PROMPT, "Извлечение — промпт (файл → XML)"),
            new PromptDef(CTRL_KEY_XML_SCHEMA, "Извлечение — XML-структура"));

    private final JdbcTemplate jdbc;

    public AdminPromptController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Список промптов с текущими значениями из БД (пусто — работает дефолт из кода). */
    @GetMapping("/prompts")
    public List<Map<String, Object>> prompts() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PromptDef p : PROMPTS) {
            List<String> v = jdbc.queryForList(
                    "SELECT ctrl_value FROM dbo.rag_control WHERE ctrl_key = ?", String.class, p.key());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put(KEY, p.key());
            m.put("title", p.title());
            m.put("value", v.isEmpty() || v.get(0) == null ? "" : v.get(0));
            out.add(m);
        }
        return out;
    }

    public record PromptRequest(String key, String value) {}

    /** Сохранение промпта; применяется к следующему вопросу без рестарта.
     *  JSON-валидация — только у extract_json_schema; extract_xml_schema (формат XML)
     *  сохраняется как обычный текст. */
    @PostMapping("/prompts")
    public Map<String, Object> savePrompt(@RequestBody PromptRequest req) {
        titleOf(req.key());
        String value = req.value() == null ? "" : req.value();
        if (value.isBlank()) throw new IllegalArgumentException("промпт пустой — используйте «Дефолт из кода»");
        if (ExtractPromptProvider.CTRL_KEY_SCHEMA.equals(req.key())) {   // схема обязана быть валидным JSON-объектом
            try {
                JSON.readValue(value, Map.class);
            } catch (Exception e) {
                throw new IllegalArgumentException(
                        "JSON-структура — не валидный JSON-объект: " + e.getMessage());
            }
        }
        jdbc.update("""
                MERGE dbo.rag_control WITH (HOLDLOCK) AS t
                USING (SELECT ? AS k) AS s ON t.ctrl_key = s.k
                WHEN MATCHED THEN UPDATE SET t.ctrl_value = ?
                WHEN NOT MATCHED THEN INSERT (ctrl_key, ctrl_value) VALUES (s.k, ?);
                """, req.key(), value, value);
        return Map.of(RESULT, SAVED, KEY, req.key(), "chars", value.length());
    }

    /** Сброс: строка удаляется — провайдер тут же берёт DEFAULT из кода,
     *  а следующий рестарт просеет дефолт в таблицу заново (для XML-ключей дефолта
     *  в коде нет — строка просто удаляется, значение становится пустым). */
    @PostMapping("/prompts/reset")
    public Map<String, String> resetPrompt(@RequestBody PromptRequest req) {
        titleOf(req.key());
        jdbc.update("DELETE FROM dbo.rag_control WHERE ctrl_key = ?", req.key());
        return Map.of(RESULT, "default", KEY, req.key());
    }

    /** Подпись по ключу; IllegalArgumentException — ключ не из списка. */
    private static String titleOf(String key) {
        if (key != null) {
            for (PromptDef p : PROMPTS) {
                if (p.key().equals(key)) return p.title();
            }
        }
        throw new IllegalArgumentException("неизвестный промпт: " + key);
    }
}