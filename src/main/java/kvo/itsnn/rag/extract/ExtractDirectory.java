package kvo.itsnn.rag.extract;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Справочники DocsVision для проверки значений интеграции «файл → JSON»:
 *  - виды документов  dvtable_{c7ba000c-…}                 → поле document_kind
 *  - типы документов  dvtable_{1b1a44fb-…} под узлами «тип…» → поле document_type
 *  - юр. лица группы  dvtable_{7473f07f-…} Type=0, активные → поле organization_LE (+ ИНН/КПП)
 *
 * Правила сопоставления (после нормализации: нижний регистр, ё→е, без кавычек/
 * пунктуации/реквизитов/оргформ):
 *  - вид/тип: точное совпадение, иначе имя справочника ЦЕЛИКОМ входит в значение модели.
 *    Обратное направление (значение внутри длинного названия маршрута) запрещено —
 *    «Письмо» не должно подтягивать «Маршрут согласования (письмо о предоплате)».
 *  - юр. лицо: leByInn — по ИНН из документа (сильнейший сигнал), leStrict — строгое
 *    совпадение названий: точное равенство либо одна сторона целиком из слов другой
 *    (минимум по два слова с каждой). Прежний leOf с substring-матчем удалён:
 *    «МАСТЕР» ⊂ «Мастер Авто» — разные организации, а матч «наше» решал развилку сторон.
 * Справочники маленькие и меняются редко: кэш на 10 минут, перечитывается лениво.
 */
@Component
public class ExtractDirectory {

    private static final Logger log = LoggerFactory.getLogger(ExtractDirectory.class);

    /** 10 минут в мс. Первый операнд long: иначе произведение вычисляется в int
     *  и при росте констант возможно переполнение (Sonar S2184). */
    private static final long TTL_MS = 10L * 60 * 1000;
    /** Короче 4 символов ключ «содержится в» не рассматриваем — защита от шума. */
    private static final int MIN_CONTAINS_LEN = 4;

    /** Юр. лицо группы: каноническое Name + реквизиты из справочника. */
    public record Le(String name, String inn, String kpp) {}

    private record Dicts(Map<String, String> kinds, Map<String, String> types,
                         Map<String, Le> les, Map<String, Le> byInn, Instant at) {}

    public static final String KEY_KIND = "document_kind";
    public static final String KEY_TYPE = "document_type";
    public static final String KEY_CONTRAGENT = "contragent";
    public static final String KEY_ORG = "organization_LE";
    public static final String KEY_INN = "organization_LE_INN";
    public static final String KEY_KPP = "organization_LE_KPP";

    /* ---------- нормализация: шаблоны компилируются один раз ---------- */

    /** Кавычки и пунктуация → пробел (№ тоже, чтобы не склеивался с числом). */
    private static final Pattern PUNCT = Pattern.compile("[«»\"'“”‘’()\\-—–.,;:!?№]");

    /** «ИНН 7447273667», «КПП 526002003» — реквизиты, не часть названия. */
    private static final Pattern REQUISITE = Pattern.compile(
            "\\b(инн|кпп|огрн|огрнип)\\s*[:№]?\\s*\\d+\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);

    /** Полные оргформы («Акционерное общество», «Общество с ограниченной ответственностью»…)
     *  вырезаем ДО аббревиатур — длинные фразы первыми, иначе от «публичное акционерное
     *  общество» остался бы хвост «публичное».
     *  CANON_EQ: буква «й» в «индивидуальный» канонически разложима (и + U+0306); без
     *  флага текст, набранный в разложенной форме, не матчился бы и оргформа не вырезалась. */
    private static final Pattern ORG_FORM_FULL = Pattern.compile(
            "\\b(публичное акционерное общество|непубличное акционерное общество"
                    + "|открытое акционерное общество|закрытое акционерное общество"
                    + "|акционерное общество|общество с ограниченной ответственностью"
                    + "|индивидуальный предприниматель"
                    + "|федеральное государственное унитарное предприятие"
                    + "|государственное унитарное предприятие)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS | Pattern.CANON_EQ);

    /** Оргформы-аббревиатуры вырезаем. Флаг UNICODE_CHARACTER_CLASS обязателен: без него \b —
     *  ASCII-граница, у кириллических «ооо» границы нет и вырезание молча не работало. */
    private static final Pattern ORG_FORM = Pattern.compile(
            "\\b(ооо|оао|зао|пао|ао|нпо|фгуп|фгу|гуп|ип)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);

    private static final Pattern SPACES = Pattern.compile("\\s+");

    private final JdbcTemplate jdbc;

    /** Доступ к cache — только под монитором в dicts(): volatile на ссылке не
     *  публикует содержимое карт, поэтому чтение/запись синхронизированы (Sonar S3077). */
    private Dicts cache;
    private volatile boolean warned;

    public ExtractDirectory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Канонический вид документа; null — в справочнике нет (поле будет очищено). */
    public String kindOf(String value) {
        return best(value, dicts().kinds(), false);
    }

    /** Канонический тип документа; null — в справочнике нет. */
    public String typeOf(String value) {
        return best(value, dicts().types(), false);
    }

    /** Наше ЮЛ по ИНН, как он напечатан в документе; null — ИНН чужой или пустой. */
    public Le leByInn(String inn) {
        if (inn == null || inn.isBlank()) return null;
        return dicts().byInn().get(inn.strip());
    }

    /**
     * СТРОГОЕ сопоставление названия со справочником — для развилки «наше/чужое».
     * Точное равенство нормализованных названий, либо одна сторона ЦЕЛИКОМ состоит
     * из слов другой, и в каждой не меньше двух слов. Однословное значение («мастер»)
     * матчит ТОЛЬКО точное равенство: «мастер» ⊄ «мастер авто» — разные организации.
     * Псевдонимы работают как раньше: «ЦИТРБ» — точное попадание в Name,
     * «Центр ИТ решений для бизнеса» — слова целиком входят в FullName.
     */
    public Le leStrict(String value) {
        if (value == null || value.isBlank()) return null;
        String n = norm(value);
        if (n.isEmpty()) return null;
        Le exact = dicts().les().get(n);
        if (exact != null) return exact;
        List<String> v = List.of(n.split(" "));
        if (v.size() < 2) return null;              // однословное — только точное совпадение
        for (Map.Entry<String, Le> e : dicts().les().entrySet()) {
            List<String> k = List.of(e.getKey().split(" "));
            if (k.size() >= 2 && (v.containsAll(k) || k.containsAll(v))) return e.getValue();
        }
        return null;
    }

    /** Справочник прочитан (хотя бы один непустой)? Нет — проверку значений пропускаем. */
    public boolean available() {
        Dicts d = dicts();
        return !d.kinds().isEmpty() || !d.types().isEmpty() || !d.les().isEmpty();
    }

    /** Размеры и время загрузки — для админ-панели (GET /api/rag/extract/dictionaries). */
    public Map<String, Object> stats() {
        Dicts d = dicts();
        return Map.of("kinds", d.kinds().size(), "types", d.types().size(),
                "les", d.les().size(), "refreshedAt", d.at().toString());
    }

    /* ---------- внутреннее ---------- */

    /**
     * Точное совпадение, иначе «содержится» — для видов/типов.
     * allowLongerKey=false: только ключ ЦЕЛИКОМ внутри значения модели; длинные названия
     * маршрутов, содержащие короткое слово модели, — ложные попадания.
     * Из кандидатов берём самый длинный ключ — самое конкретное имя.
     */
    private static String best(String value, Map<String, String> dict, boolean allowLongerKey) {
        if (value == null || value.isBlank()) return null;
        String n = norm(value);
        if (n.isEmpty()) return null;
        String exact = dict.get(n);
        if (exact != null) return exact;
        String best = null;
        int bestLen = 0;
        for (Map.Entry<String, String> e : dict.entrySet()) {
            String key = e.getKey();
            int len = key.length();
            if (len < MIN_CONTAINS_LEN || len <= bestLen) continue;
            if (n.contains(key) || (allowLongerKey && key.contains(n))) {
                best = e.getValue();
                bestLen = len;
            }
        }
        return best;
    }

    /** Чтение и ленивая перезагрузка под монитором: публикация карт, заполненных в load(),
     *  гарантируется happens-before монитора, поэтому volatile на поле не нужен. */
    private Dicts dicts() {
        synchronized (this) {
            Dicts d = cache;
            if (d != null && System.currentTimeMillis() - d.at().toEpochMilli() < TTL_MS) return d;
            d = load();
            cache = d;
            return d;
        }
    }

    private Dicts load() {
        Map<String, String> kinds = new LinkedHashMap<>();
        Map<String, String> types = new LinkedHashMap<>();
        Map<String, Le> les = new LinkedHashMap<>();
        Map<String, Le> byInn = new LinkedHashMap<>();
        try {
            // Каст к RowCallbackHandler обязателен: expression-лямбда rs -> map.put(...)
            // без него одинаково подходит и под RowCallbackHandler (void), и под
            // ResultSetExtractor (put возвращает String) — выбор перегрузки неоднозначен.
            jdbc.query("""
                    SELECT Name FROM dbo.[dvtable_{c7ba000c-6203-4d7f-8c6b-5cb6f1e6f851}] (nolock)
                    """, (RowCallbackHandler) rs -> kinds.put(norm(rs.getString(1)), rs.getString(1).strip()));
            jdbc.query("""
                    SELECT base_rows.Name
                    FROM dbo.[dvtable_{1b1a44fb-1fb1-4876-83aa-95ad38907e24}] base_rows (nolock)
                    JOIN dbo.[dvtable_{a1dce6c1-db96-4666-b418-5a075cdb02c9}] base_types (nolock)
                      ON base_types.RowID = base_rows.ParentRowID
                    WHERE base_types.Name LIKE 'тип%'
                    """, (RowCallbackHandler) rs -> types.put(norm(rs.getString(1)), rs.getString(1).strip()));
            jdbc.query("""
                    SELECT Name, FullName, INN, KPP
                    FROM dbo.[dvtable_{7473f07f-11ed-4762-9f1e-7ff10808ddd1}] (nolock)
                    WHERE Type = 0 AND (NotAvailable IS NULL OR NotAvailable = 0) AND RowID<>'EDF97159-CD48-4500-9D1B-8FA6AB3B001A'
                    """, rs -> {
                String name = rs.getString(1);
                Le le = new Le(name == null ? "" : name.strip(), nz(rs.getString(3)), nz(rs.getString(4)));
                les.put(norm(name), le);
                String full = rs.getString(2);
                if (full != null && !full.isBlank()) les.putIfAbsent(norm(full), le);
                if (!le.inn().isBlank()) byInn.putIfAbsent(le.inn(), le);   // ИНН → ЮЛ для leByInn
            });
            warned = false;
            log.info("Справочники извлечения: виды={}, типы={}, ЮЛ={}", kinds.size(), types.size(), les.size());
        } catch (Exception e) {
            if (!warned) {          // один WARN на серию сбоев
                log.warn("Справочники извлечения не прочитаны ({}) — проверка значений отключена", e.getMessage());
                warned = true;
            }
        }
        return new Dicts(kinds, types, les, byInn, Instant.now());
    }

    /** Ключ сопоставления: нижний регистр, ё→е, без кавычек/пунктуации/№, реквизитов
     *  (ИНН/КПП/ОГРН) и оргформ — и полных фраз, и аббревиатур (ООО, ПАО…). */
    static String norm(String s) {
        if (s == null) return "";
        String n = s.toLowerCase(Locale.ROOT).replace('ё', 'е');
        n = REQUISITE.matcher(n).replaceAll(" ");
        n = PUNCT.matcher(n).replaceAll(" ");
        n = ORG_FORM_FULL.matcher(n).replaceAll(" ");
        n = ORG_FORM.matcher(n).replaceAll(" ");
        return SPACES.matcher(n).replaceAll(" ").strip();
    }

    private static String nz(String s) {
        return s == null ? "" : s.strip();
    }
}