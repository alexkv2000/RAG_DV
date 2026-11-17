package kvo.itsnn.rag.extract;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import java.util.LinkedHashMap;

import java.util.List;
import java.util.Map;

/**
 * POST http://localhost:8080/api/rag/extract (form-data, file = любой docx/pdf)
 * Промпт и JSON-структура интеграции «файл → JSON» живут в dbo.rag_control
 * (ключи 'extract_system_prompt' и 'extract_json_schema') — правятся на вкладке
 * «Промты» или UPDATE'ом в SSMS и применяются к следующему файлу без рестарта.
 * Дефолты хранятся в коде как резерв и при первом старте сеются в таблицу.
 * В схеме ключ — имя поля JSON, значение — подсказка LLM (может быть пустой).
 */
@Component
public class ExtractPromptProvider {

    private static final Logger log = LoggerFactory.getLogger(ExtractPromptProvider.class);

    public static final String CTRL_KEY_PROMPT = "extract_system_prompt";
    public static final String CTRL_KEY_SCHEMA = "extract_json_schema";

    /**
     * Резерв: строк в rag_control нет / пустые / БД недоступна.
     */
    private static final String DEFAULT_PROMPT = """
            Ты — извлекатель реквизитов из документов. Тебе дан текст документа (возможно,
            распознанный с картинки) и список полей с описаниями. Правила:
            1. Верни ТОЛЬКО валидный JSON-объект: без markdown, без ```json, без пояснений до и после.
            2. Ключи — ровно те и в том же написании, что в списке полей. Лишних ключей не добавляй.
            3. Значение каждого поля — строка. Нет значения в документе — пустая строка "".
            4. Не выдумывай: пиши только то, что видно в тексте.
            5. Даты приводи к формату ДД.ММ.ГГГГ, суммы — цифрами без пробелов и валюты.
            6. Строку organization_LE — только юр. лицо ГРУППЫ; сторонняя организация — в поле contragent, не путай их.
            7. Поля document_kind (вид), document_type (тип) и organization_LE (юр. лицо группы) система сверяет со справочниками: пиши значение так, как оно звучит в справочнике.
            8. Поля organization_LE_INN / organization_LE_KPP не заполняй — их подставит система.""";
    private static final String DEFAULT_SCHEMA = """
            {"document_kind": "Вид документа",
            "document_type": "Тип документа (приказ, распоряжение, договор, письмо и т.п.)",
            "document_function": "Функция документа",
            "organization_LE": "Ю.Л. Группы",
            "organization_LE_INN": "не заполнять — подставляется из справочника",
            "organization_LE_KPP": "не заполнять — подставляется из справочника",
            "document_number": "Номер документа",
            "document_date": "дата документа, ДД.ММ.ГГГГ",
            "subject_agreement": "Предмет договора - ОРД/Внутренний/другие документы",
            "contragent": "Организация, Контрагентов",
            "subject": "Тема/заголовок документа",
            "amount": "Сумма цифрами, если есть",
            "amount NDS": "Сумма НДС, если есть",
            "summary": "Краткое содержание в 1-2 предложениях",
            "regisration_number": "Рег.номер, если есть",
            "regisration_date": "Дата регистрации, если есть",
            "signer": "Подписант со стороны Ю.Л. Группы, если есть"
            }""";

    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final String REQ_HINT = "не заполнять — подставляется из справочника";
    private final JdbcTemplate jdbc;
    private volatile boolean warned;

    public ExtractPromptProvider(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * При первом старте сеем оба дефолта, чтобы схемой и промптом можно было управлять из БД.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        seedOne(CTRL_KEY_PROMPT, DEFAULT_PROMPT);
        seedOne(CTRL_KEY_SCHEMA, DEFAULT_SCHEMA);
    }

    /**
     * Текущий промпт; пусто/сбой чтения → дефолт из кода.
     */
    public String prompt() {
        String v = ctrlRaw(CTRL_KEY_PROMPT);
        return v == null ? DEFAULT_PROMPT : v;
    }

    /**
     * Схема полей как упорядоченная Map (LinkedHashMap — порядок ключей сохраняется).
     * Битый JSON в таблице → WARN один раз + дефолт из кода.
     * Реквизиты organization_LE_INN / organization_LE_KPP всегда ставятся сразу после
     * organization_LE (вырезаются из любого другого места, добавляются, если их нет) —
     * чтобы в ответе интеграции ИНН/КПП шли за юр. лицом, а не хвостом в конце JSON.
     * organization_LE в схеме нет — схема возвращается как есть (без ЮЛ реквизиты не заполняются).
     */
    public Map<String, Object> schema() {
        String raw = ctrlRaw(CTRL_KEY_SCHEMA);
        if (raw == null) raw = DEFAULT_SCHEMA;
        Map<String, Object> schema;
        try {
            schema = JSON.readValue(raw, Map.class);
        } catch (Exception e) {
            if (!warned) {
                log.warn("rag_control: extract_json_schema не разобран ({}), используется дефолт из кода",
                        e.getMessage());
                warned = true;
            }
            schema = defaultSchema();
        }
        return withOrgRequisites(schema);
    }

    /** Дефолт из кода; парсинг всегда успешен (литерал), но читаем одинаково. */
    private Map<String, Object> defaultSchema() {
        return JSON.readValue(DEFAULT_SCHEMA, Map.class);
    }

    /** Пересборка схемы: org → INN → KPP подряд, в каноническом написании ключей. */
    private static Map<String, Object> withOrgRequisites(Map<String, Object> schema) {
        if (!hasKey(schema, ExtractDirectory.KEY_ORG)) return schema;
        Object innHint = hintOf(schema, ExtractDirectory.KEY_INN);
        Object kppHint = hintOf(schema, ExtractDirectory.KEY_KPP);
        Map<String, Object> out = new LinkedHashMap<>(schema.size() + 2);
        for (Map.Entry<String, Object> e : schema.entrySet()) {
            String k = e.getKey();
            if (k.equalsIgnoreCase(ExtractDirectory.KEY_INN) || k.equalsIgnoreCase(ExtractDirectory.KEY_KPP)) {
                continue;                       // вырежем и вставим после organization_LE
            }
            out.put(k, e.getValue());
            if (k.equalsIgnoreCase(ExtractDirectory.KEY_ORG)) {
                out.put(ExtractDirectory.KEY_INN, innHint);
                out.put(ExtractDirectory.KEY_KPP, kppHint);
            }
        }
        return out;
    }

    private static boolean hasKey(Map<String, Object> schema, String name) {
        for (String k : schema.keySet()) {
            if (k.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    /** Подсказка ключа из схемы (сохраняем при переносе); нет — стандартная REQ_HINT. */
    private static Object hintOf(Map<String, Object> schema, String name) {
        for (Map.Entry<String, Object> e : schema.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return REQ_HINT;
    }

    private void seedOne(String key, String value) {
        try {
            int n = jdbc.update("""
                    INSERT INTO dbo.rag_control (ctrl_key, ctrl_value)
                    SELECT ?, ?
                    WHERE NOT EXISTS (SELECT 1 FROM dbo.rag_control WHERE ctrl_key = ?)
                    """, key, value, key);
            if (n > 0) {
                log.info("rag_control: записан дефолт '{}' (интеграция файл → JSON)", key);
            }
        } catch (Exception e) {
            log.warn("rag_control: дефолт '{}' не записан ({}), работает резерв из кода", key, e.getMessage());
        }
    }

    /**
     * Значение по ключу; null — строки нет / пустая / БД недоступна.
     */
    private String ctrlRaw(String key) {
        try {
            List<String> v = jdbc.queryForList(
                    "SELECT ctrl_value FROM dbo.rag_control WHERE ctrl_key = ?", String.class, key);
            if (v.isEmpty()) return null;
            String raw = v.getFirst();
            warned = false;
            return raw == null || raw.isBlank() ? null : raw.strip();
        } catch (Exception e) {
            if (!warned) {
                log.warn("rag_control: '{}' не прочитан ({}), используется дефолт", key, e.getMessage());
                warned = true;
            }
            return null;
        }
    }
}