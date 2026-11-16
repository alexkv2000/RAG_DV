package kvo.itsnn.rag.extract;

import kvo.itsnn.rag.ingest.ContentLoader;
import kvo.itsnn.rag.ingest.TextExtractor;
import kvo.itsnn.rag.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.*;

/**
 * Интеграция «файл → JSON»: текст тем же TextExtractor'ом, что и индексация
 * (pdf без текстового слоя автоматически уходит в OCR), затем LLM заполняет
 * поля схемы из rag_control. Ответ — Map по схеме: отсутствующие поля = "".
 */
@Service
public class ExtractService {

    private static final Logger log = LoggerFactory.getLogger(ExtractService.class);

    /** Форматы интеграции; pdf-скан распознаётся внутри TextExtractor (rag.ocr.enabled). */
    private static final Set<String> ALLOWED_EXT = Set.of("doc", "docx", "pdf", "rtf", "txt");
    private static final long MAX_FILE_BYTES = 32L * 1024 * 1024;
    /** Больше этого текста в LLM не отправляем (лимит токенов модели). */
    private static final int MAX_TEXT_CHARS = 20_000;

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final TextExtractor extractor;
    private final LlmClient llm;
    private final ExtractPromptProvider prompts;

    public ExtractService(TextExtractor extractor, LlmClient llm, ExtractPromptProvider prompts) {
        this.extractor = extractor;
        this.llm = llm;
        this.prompts = prompts;
        log.info("ExtractService: форматы={}, лимит файла {} МБ, лимит текста {} симв.",
                ALLOWED_EXT, MAX_FILE_BYTES / 1024 / 1024, MAX_TEXT_CHARS);
    }

    /** Готовый JSON по схеме: ключи схемы в её порядке, пустые значения — "". */
    public Map<String, String> extract(String fileName, byte[] bytes) {
        String ext = check(fileName, bytes.length);
        String text = extractText(fileName, ext, bytes);

        Map<String, Object> schema = prompts.schema();
        if (schema.isEmpty()) {
            throw new IllegalStateException("rag_control: extract_json_schema пуст — задайте схему на вкладке «Промты»");
        }

        String user = "Поля (ключ — описание; описание пустое — заполни по смыслу):\n"
                + fieldsOf(schema)
                + "\nТекст документа:\n" + limit(text)
                + "\n\nВерни JSON-объект с этими ключами и только с ними.";

        long t0 = System.currentTimeMillis();
        String answer = llm.chat(List.of(
                LlmClient.Message.system(prompts.prompt()),
                LlmClient.Message.user(user)));
        log.info("EXTRACT: '{}', {} симв. текста, полей={}, LLM {} мс",
                fileName, text.length(), schema.size(), System.currentTimeMillis() - t0);
        return merge(schema.keySet(), answer);
    }

    /** Текущая схема для интеграторов (GET /api/rag/extract/schema). */
    public Map<String, Object> schema() {
        return prompts.schema();
    }

    /* ---------- внутреннее ---------- */

    private static String check(String fileName, long size) {
        if (fileName == null) throw new IllegalArgumentException("имя файла не задано");
        if (size == 0) throw new IllegalArgumentException("файл '" + fileName + "' пустой");
        if (size > MAX_FILE_BYTES) throw new IllegalArgumentException(
                "файл '" + fileName + "' больше " + MAX_FILE_BYTES / 1024 / 1024 + " МБ");
        int d = fileName.lastIndexOf('.');
        String ext = d < 0 || d == fileName.length() - 1
                ? "" : fileName.substring(d + 1).toLowerCase(Locale.ROOT);
        if (!ALLOWED_EXT.contains(ext)) throw new IllegalArgumentException(
                "формат '" + (ext.isEmpty() ? "?" : ext) + "' не поддерживается; допустимы: "
                        + String.join(", ", ALLOWED_EXT));
        return ext;
    }

    /** Текст файла через тот же extractor, что и индексация (OCR сканов внутри). */
    private String extractText(String fileName, String ext, byte[] bytes) {
        ContentLoader.LoadedFile f = new ContentLoader.LoadedFile(UUID.randomUUID(), fileName, ext, bytes);
        String text;
        try {
            text = extractor.extract(f);
        } catch (IllegalArgumentException e) {          // OCR выключен, пустой файл и пр. — вина запроса
            throw new IllegalArgumentException(fileName + ": " + e.getMessage(), e);
        } catch (Exception e) {                          // битый файл
            throw new IllegalArgumentException(fileName + ": не удалось извлечь текст ("
                    + e.getMessage() + ")", e);
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(fileName + ": файл не содержит извлекаемого текста");
        }
        return text.strip();
    }

    /** Список полей для промпта: "- ключ: описание". */
    private static String fieldsOf(Map<String, Object> schema) {
        StringBuilder sb = new StringBuilder();
        schema.forEach((k, v) -> {
            String hint = v == null ? "" : String.valueOf(v).strip();
            sb.append("- ").append(k).append(hint.isEmpty() ? "" : ": " + hint).append('\n');
        });
        return sb.toString();
    }

    /** Ответ LLM → Map строго по схеме: лишние ключи модели игнорируем, NULL/отсутствие → "". */
    private Map<String, String> merge(Set<String> keys, String answer) {
        Map<String, Object> got;
        try {
            got = JSON.readValue(jsonBody(answer), Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("EXTRACT: LLM вернул не JSON: " + brief(answer), e);
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (String k : keys) {
            Object v = got.get(k);
            out.put(k, v == null ? "" : (v instanceof String s ? s.strip() : String.valueOf(v)));
        }
        return out;
    }

    /** Вырезаем JSON-объект из ответа: срезаем ```-обёртку, берём от '{' до '}'. */
    private static String jsonBody(String s) {
        String t = s == null ? "" : s.strip();
        t = t.replaceAll("(?s)^```[a-zA-Z]*\\s*", "").replaceAll("(?s)```$", "").strip();
        int a = t.indexOf('{');
        int b = t.lastIndexOf('}');
        if (a < 0 || b <= a) throw new IllegalArgumentException("в ответе нет JSON-объекта");
        return t.substring(a, b + 1);
    }

    private static String limit(String text) {
        return text.length() > MAX_TEXT_CHARS
                ? text.substring(0, MAX_TEXT_CHARS) + "\n…[текст обрезан по лимиту]" : text;
    }

    private static String brief(String s) {
        String t = s == null ? "" : s.replaceAll("\\s+", " ").trim();
        return t.length() > 300 ? t.substring(0, 300) + "…" : t;
    }
}