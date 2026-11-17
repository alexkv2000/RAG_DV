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
 * После LLM значения сверяются со справочниками DocsVision (ExtractDirectory):
 * вид/тип/юр.лицо → каноническое имя справочника или "" (нет совпадения);
 * для organization_LE добавляются ИНН/КПП найденного юр. лица.
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
    private final ExtractDirectory directory;

    public ExtractService(TextExtractor extractor, LlmClient llm,
                          ExtractPromptProvider prompts, ExtractDirectory directory) {
        this.extractor = extractor;
        this.llm = llm;
        this.prompts = prompts;
        this.directory = directory;
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
        return validate(fileName, merge(schema.keySet(), answer));
    }

    /** Текущая схема для интеграторов (GET /api/rag/extract/schema). */
    public Map<String, Object> schema() {
        return prompts.schema();
    }

    /* ---------- сверка со справочниками ---------- */

    /**
     * Вид/тип — по справочникам: совпадение → каноническое имя, нет → "".
     * Юр. лицо группы и контрагент разводим по справочнику ЮЛ (dvtable_{7473f07f-…}):
     * найдено в справочнике → предприятие группы → organization_LE (+ ИНН/КПП),
     * не найдено → контрагент → contragent. LLM часто путает эти поля — раскладываем по местам.
     * Справочник не прочитан — значения остаются как вернула LLM (проверку пропускаем).
     */
    private Map<String, String> validate(String fileName, Map<String, String> out) {
        if (!directory.available()) return out;
        int matched = 0, cleared = 0, moved = 0;
        String kKind = findKey(out, ExtractDirectory.KEY_KIND);
        int[] a = align(out, kKind, directory.kindOf(val(out, kKind)));
        matched += a[0];
        cleared += a[1];
        String kType = findKey(out, ExtractDirectory.KEY_TYPE);
        a = align(out, kType, directory.typeOf(val(out, kType)));
        matched += a[0];
        cleared += a[1];
        String kOrg = findKey(out, ExtractDirectory.KEY_ORG);
        String kCon = findKey(out, ExtractDirectory.KEY_CONTRAGENT);
        if (kOrg != null) {
            String orgVal = nz(out.get(kOrg));
            String conVal = kCon == null ? "" : nz(out.get(kCon));
            ExtractDirectory.Le le = directory.leOf(orgVal);          // org — наше ЮЛ?
            if (le == null) {
                ExtractDirectory.Le leCon = directory.leOf(conVal);   // контрагент — наше ЮЛ?
                if (leCon != null) {
                    // поля перепутаны местами: наш ЮЛ стоит в contragent,
                    // внешняя организация — в organization_LE. Меняем местами.
                    out.put(kOrg, leCon.name());
                    out.put(kCon, orgVal);          // старое значение — в контрагенты (может стать "")
                    le = leCon;
                    moved++;
                } else if (!orgVal.isEmpty()) {
                    // в ЮЛ записан контрагент: переносим в contragent (если там пусто), ЮЛ очищаем
                    if (kCon != null && conVal.isEmpty()) {
                        out.put(kCon, orgVal);
                        moved++;
                    }
                    out.put(kOrg, "");
                    cleared++;
                }
            } else if (kCon != null && le.equals(directory.leOf(conVal))) {
                out.put(kCon, "");                  // контрагент дублирует наше же ЮЛ
                cleared++;
            }
            String kInn = keyOrNew(out, ExtractDirectory.KEY_INN);
            String kKpp = keyOrNew(out, ExtractDirectory.KEY_KPP);
            out.put(kInn, le == null ? "" : le.inn());
            out.put(kKpp, le == null ? "" : le.kpp());
        }
        log.info("EXTRACT-DICT '{}': подтверждено/исправлено {}, очищено {}, переставлено {}",
                fileName, matched, cleared, moved);
        return out;
    }

    private static String nz(String s) {
        return s == null ? "" : s.strip();
    }
    /** Сверка одного поля: canonical==null → очистить; иначе подставить. [matched, cleared]. */
    private static int[] align(Map<String, String> out, String key, String canonical) {
        if (key == null) return new int[]{0, 0};
        String cur = out.get(key);
        if (cur == null || cur.isBlank()) return new int[]{0, 0};
        if (canonical == null) {
            out.put(key, "");
            return new int[]{0, 1};
        }
        out.put(key, canonical);
        return canonical.equals(cur) ? new int[]{0, 0} : new int[]{1, 0};
    }

    /** Существующий ключ (без учёта регистра) или новый с пустым значением. */
    private static String keyOrNew(Map<String, String> out, String name) {
        String k = findKey(out, name);
        if (k != null) return k;
        out.put(name, "");
        return name;
    }

    private static String findKey(Map<String, String> out, String name) {
        for (String k : out.keySet()) {
            if (k.equalsIgnoreCase(name)) return k;
        }
        return null;
    }

    private static String val(Map<String, String> out, String key) {
        return key == null ? null : out.get(key);
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