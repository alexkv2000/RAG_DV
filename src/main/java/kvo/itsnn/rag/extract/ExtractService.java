package kvo.itsnn.rag.extract;

import kvo.itsnn.rag.ingest.ContentLoader;
import kvo.itsnn.rag.ingest.TextExtractor;
import kvo.itsnn.rag.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Интеграция «файл → JSON»: текст тем же TextExtractor'ом, что и индексация
 * (pdf без текстового слоя автоматически уходит в OCR), затем LLM заполняет
 * поля схемы из rag_control. Ответ — Map по схеме: отсутствующие поля = "".
 * После LLM значения сверяются со справочниками DocsVision (ExtractDirectory):
 * вид/тип → каноническое имя справочника или "" (нет совпадения);
 * organization_LE / contragent разводятся по ИНН из ТЕКСТА документа (сторона,
 * чей ИНН есть в справочнике группы, — наша) с резервом по строгому матчу
 * названий; для organization_LE подставляются ИНН/КПП из справочника.
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

    /**
     * ИНН в тексте документа: 10 цифр (юр. лицо) или 12 (ИП) с границами по не-цифре.
     * КПП (9), ОГРН (13), ОГРНИП (15), счёт (20) не матчатся; даты разбиты точками.
     */
    private static final Pattern INN_TEXT = Pattern.compile("(?<!\\d)(\\d{10}|\\d{12})(?!\\d)");

    /** Пределы длины имени организации за маркером-оргформой. */
    private static final int NAME_MIN_LEN = 2;
    private static final int NAME_MAX_LEN = 60;

    /**
     * Слово текста (Unicode: кириллица входит в \w). Маркеры оргформ распознаются
     * по словам кодом (markerEnd) — регексп предельно прост: нет чередования с
     * русскими литералами, лишних квантификаторов и символов, требующих CANON_EQ
     * (каноническую эквивалентность обеспеч NFC-нормализация окна в lastOrgBefore).
     */
    private static final Pattern WORD = Pattern.compile("\\b\\w+", Pattern.UNICODE_CHARACTER_CLASS);

    /** Оргформы-аббревиатуры — только целым словом, нижний регистр. */
    private static final Set<String> ORG_ABBREV = Set.of("ооо", "оао", "пао", "зао", "ао", "ип");

    /** Основы слов полных оргформ, нижний регистр, окончание любого падежа. */
    private static final String STEM_AO = "акционерн";
    private static final String STEM_SOCIETY = "обществ";

    /** Хвост «…с ограниченной ответственностью» после основы STEM_SOCIETY. */
    private static final List<String> OOO_TAIL = List.of("с", "ограниченной", "ответственностью");

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
        // text, а не limit(text): ИНН сторон бывает и в подвале, у подписей — валидатору
        // он нужен для разводки сторон; в LLM уходит только обрезанный кусок
        return validate(fileName, merge(schema.keySet(), answer), text);
    }

    /** Текущая схема для интеграторов (GET /api/rag/extract/schema). */
    public Map<String, Object> schema() {
        return prompts.schema();
    }

    /* ---------- сверка со справочниками ---------- */

    /**
     * Вид/тип — по справочникам: совпадение → каноническое имя, нет → "".
     * Стороны (organization_LE / contragent) разводим по трём сигналам силы:
     *  1. ИНН из ТЕКСТА документа: сторона, чей ИНН есть в справочнике группы, — наша.
     *  2. Строгое сопоставление названий (ExtractDirectory.leStrict) — без substring'ов.
     *  3. Ничего не нашлось → organization_LE пуст («пустое честнее неверного»).
     * Справочник не прочитан — значения остаются как вернула LLM (проверку пропускаем).
     */
    private Map<String, String> validate(String fileName, Map<String, String> out, String text) {
        if (!directory.available()) return out;
        int matched = 0;
        int cleared = 0;
        int moved = 0;
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
            int[] r = resolveSides(out, kOrg, kCon, text);
            matched += r[0];
            cleared += r[1];
            moved += r[2];
        }
        log.info("EXTRACT-DICT '{}': подтверждено/исправлено {}, очищено {}, переставлено {}",
                fileName, matched, cleared, moved);
        return out;
    }

    /**
     * Развилка сторон; возвращает [исправлено, очищено, переставлено]. Каждый шаг —
     * отдельный метод: scanInns (наш ЮЛ и «чужой» ИНН по тексту), resolveOurs (наше
     * ЮЛ с резервом по названиям), contragentSide (сторона контрагента и перестановки).
     */
    private int[] resolveSides(Map<String, String> out, String kOrg, String kCon, String text) {
        String orgVal = nz(out.get(kOrg));
        String conVal = kCon == null ? "" : nz(out.get(kCon));
        InnScan inns = scanInns(text);
        Ours ours = resolveOurs(inns.ours(), orgVal, conVal);
        ExtractDirectory.Le le = ours.le();
        Contragent other = contragentSide(ours, orgVal, conVal, inns, text);

        writeSides(out, kOrg, kCon, le, other.name());
        log.info("EXTRACT-DICT: наше ЮЛ={}, чужой ИНН={}; LLM вернула: organization_LE='{}', contragent='{}'",
                le == null ? "не найдено" : le.name() + " (ИНН " + le.inn() + ")",
                inns.foreign() == null ? "—" : inns.foreign(), orgVal, conVal);
        boolean fixed = le != null && !le.name().equalsIgnoreCase(orgVal);
        boolean blanked = (le == null && !orgVal.isEmpty())
                || (kCon != null && !conVal.isEmpty() && other.name().isBlank());
        return new int[]{fixed ? 1 : 0, blanked ? 1 : 0, other.moved()};
    }

    /** Скан ИНН документа: наш — первый совпавший со справочником, чужой — первый нет. */
    private record InnScan(ExtractDirectory.Le ours, String foreign, Set<String> all) {}

    /** Наше ЮЛ и признак «LLM поставила его в contragent» (стороны перепутаны). */
    private record Ours(ExtractDirectory.Le le, boolean inContragent) {}

    /** Сторона контрагента: имя и число перестановок. */
    private record Contragent(String name, int moved) {}

    /**
     * ИНН из текста документа: сторона, чей ИНН есть в справочнике группы, — наша.
     * В актах и счетах-фактурах ИНН обеих сторон напечатан — это сильнее любых
     * названий (LLM искажает «МАСТЕР» → «Мастер Авто», а прежний substring-матч
     * подтверждал подмену и ставил ИНН/КПП чужого ЮЛ в organization_LE).
     * «Чужой» ИНН (напечатан, но в справочнике группы его нет) запоминаем — по нему
     * восстанавливаем название контрагента, если LLM его исказила или не заполнила.
     */
    private InnScan scanInns(String text) {
        Set<String> docInns = new LinkedHashSet<>();
        Matcher m = INN_TEXT.matcher(text == null ? "" : text);
        while (m.find()) docInns.add(m.group(1));
        ExtractDirectory.Le ours = null;
        String foreign = null;
        for (String inn : docInns) {
            ExtractDirectory.Le byInn = directory.leByInn(inn);
            if (byInn != null) {
                if (ours == null) ours = byInn;
            } else if (foreign == null) {
                foreign = inn;
            }
        }
        return new InnScan(ours, foreign, docInns);
    }

    /** Наше ЮЛ: сперва по ИНН из текста (сильнейший сигнал), затем строгий матч
     *  названий — сначала contragent (LLM могла перепутать стороны), затем organization_LE. */
    private Ours resolveOurs(ExtractDirectory.Le byInn, String orgVal, String conVal) {
        if (byInn != null) {
            // ИНН уже назвал наше ЮЛ — проверяем, куда его поставила LLM
            boolean inCon = sameLe(directory.leStrict(conVal), byInn)
                    && !sameLe(directory.leStrict(orgVal), byInn);
            return new Ours(byInn, inCon);
        }
        ExtractDirectory.Le le = directory.leStrict(conVal);
        if (le != null) return new Ours(le, true);
        return new Ours(directory.leStrict(orgVal), false);
    }

    /** Сторона контрагента и счётчик перестановок. */
    private Contragent contragentSide(Ours ours, String orgVal, String conVal, InnScan inns, String text) {
        int moved = 0;
        String other = conVal;                          // сторонняя организация
        if (ours.le() == null) {                        // группы в документе нет
            if (!orgVal.isEmpty() && conVal.isEmpty()) {
                other = orgVal;                         // контрагент записан в organization_LE
                moved++;
            }
        } else if (ours.inContragent()) {               // стороны перепутаны местами
            other = orgVal;
            moved++;
        }
        if (other.isBlank() && inns.foreign() != null) {
            other = orgNameByInn(text, inns.foreign()); // LLM контрагента не нашла — текст в помощь
        }
        return new Contragent(cleanContragent(other, ours.le(), inns, text), moved);
    }

    /**
     * contragent не должен остаться названием ЮЛ группы:
     *  дубль нашего ЮЛ — очистить
     *  организация группы, чьего ИНН в документе нет (галлюцинация вроде «Мастер
     *  Авто»: в справочнике есть, а в акте её ИНН не напечатан) — восстановить имя
     *  контрагента из текста по «чужому» ИНН, не вышло — пусто
     *  вторая организация группы, чей ИНН в документе есть (внутригрупповой акт) — оставить
     */
    private String cleanContragent(String other, ExtractDirectory.Le le, InnScan inns, String text) {
        if (other.isBlank()) return other;
        ExtractDirectory.Le otherLe = directory.leStrict(other);
        if (otherLe == null) return other;              // не из справочника группы
        if (sameLe(otherLe, le)) return "";             // дубль нашего ЮЛ
        if (!inns.all().contains(otherLe.inn())) {
            return orgNameByInn(text, inns.foreign());  // ИНН в документе нет — подмена
        }
        return other;                                   // внутригрупповой акт
    }

    /** Запись сторон: наше ЮЛ (или пусто) + контрагент + ИНН/КПП из справочника. */
    private void writeSides(Map<String, String> out, String kOrg, String kCon,
                            ExtractDirectory.Le le, String contragent) {
        out.put(kOrg, le == null ? "" : le.name());
        if (kCon != null) out.put(kCon, contragent);
        out.put(keyOrNew(out, ExtractDirectory.KEY_INN), le == null ? "" : le.inn());
        out.put(keyOrNew(out, ExtractDirectory.KEY_KPP), le == null ? "" : le.kpp());
    }

    /* ---------- восстановление названия по ИНН из текста ---------- */

    /** Слово окна: нормализованный текст (нижний регистр, ё→е) и границы в окне. */
    private record Word(String norm, int start, int end) {}

    /**
     * Название организации перед её ИНН (окно 160 симв. до номера):
     * «между ООО "МАСТЕР" (ИНН 7447273667)…» → ООО "МАСТЕР". Берём последний
     * маркер оргформы (ближайший к ИНН), имя дочитываем кодом. Творительный
     * падеж («Обществом с…») матчится — заголовки актов пишут «между Акционерным
     * обществом…».
     */
    private static String orgNameByInn(String text, String inn) {
        if (text == null || inn == null || inn.isBlank()) return "";
        int i = text.indexOf(inn);
        while (i >= 0) {
            String window = text.substring(Math.max(0, i - 160), i);
            String found = lastOrgBefore(window);
            if (!found.isBlank()) return found;
            i = text.indexOf(inn, i + inn.length());
        }
        return "";
    }

    /**
     * Последняя «оргформа + имя» в окне (ближайшая к концу, т.е. к ИНН); "" — нет.
     * Окно сначала приводится к NFC: разложенные символы («и» + U+0306 вместо «й»)
     * склеиваются — это делает то же, что раньше давал флаг CANON_EQ регекспа.
     * Маркеры ищутся по словам, «оргформа + имя» собирается из подстрок.
     */
    private static String lastOrgBefore(String window) {
        String nfc = Normalizer.normalize(window, Normalizer.Form.NFC);
        List<Word> words = wordsOf(nfc);
        String best = "";
        for (int i = 0; i < words.size(); i++) {
            int end = markerEnd(words, i);
            if (end < 0) continue;
            String tail = nameAfter(nfc, end);
            if (!tail.isBlank()) best = nfc.substring(words.get(i).start(), end) + tail;
        }
        return best.strip();
    }

    private static List<Word> wordsOf(String nfc) {
        List<Word> words = new ArrayList<>();
        Matcher m = WORD.matcher(nfc);
        while (m.find()) words.add(new Word(normWord(m.group()), m.start(), m.end()));
        return words;
    }

    /** Ключ сравнения слова: нижний регистр, ё→е (окно уже в NFC). */
    private static String normWord(String s) {
        return s.toLowerCase(Locale.ROOT).replace('ё', 'е');
    }

    /**
     * Конец маркера оргформы, начинающегося словом i: аббревиатура — конец слова,
     * «Акционерное общество» — конец второго слова, «Общество с ограниченной
     * ответственностью» — конец четвёртого. -1 — слово не начинает оргформу.
     */
    private static int markerEnd(List<Word> words, int i) {
        String w = words.get(i).norm();
        if (ORG_ABBREV.contains(w)) return words.get(i).end();
        if (w.startsWith(STEM_AO) && nextStartsWith(words, i + 1, STEM_SOCIETY)) {
            return words.get(i + 1).end();
        }
        if (w.startsWith(STEM_SOCIETY) && hasTail(words, i + 1)) {
            return words.get(i + OOO_TAIL.size()).end();
        }
        return -1;
    }

    private static boolean nextStartsWith(List<Word> words, int i, String stem) {
        return i < words.size() && words.get(i).norm().startsWith(stem);
    }

    /** Слова с i идут ровно в порядке OOO_TAIL («с ограниченной ответственностью»). */
    private static boolean hasTail(List<Word> words, int i) {
        if (i + OOO_TAIL.size() > words.size()) return false;
        for (int k = 0; k < OOO_TAIL.size(); k++) {
            if (!words.get(i + k).norm().equals(OOO_TAIL.get(k))) return false;
        }
        return true;
    }

    /**
     * Имя за маркером-оргформой — эквивалент прежнего хвоста регекспа
     * \s*["«]?[\w\s.-]{2,60}["»]?: пробелы, необязательная открывающая кавычка,
     * 2-60 символов имени, необязательная закрывающая кавычка.
     * "" — имени нет (короче двух символов, как и раньше не считается).
     */
    private static String nameAfter(String nfc, int markerEnd) {
        int n = nfc.length();
        int i = markerEnd;
        while (i < n && isSpace(nfc.charAt(i))) i++;
        if (i < n && isOpenQuote(nfc.charAt(i))) i++;
        int start = i;
        while (i < n && i - start < NAME_MAX_LEN && isNameChar(nfc.charAt(i))) i++;
        if (i - start < NAME_MIN_LEN) return "";
        if (i < n && isCloseQuote(nfc.charAt(i))) i++;
        return nfc.substring(markerEnd, i);
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '-' || isSpace(c);
    }

    private static boolean isSpace(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c);
    }

    private static boolean isOpenQuote(char c) {
        return c == '"' || c == '«';
    }

    private static boolean isCloseQuote(char c) {
        return c == '"' || c == '»';
    }

    /** Одно и то же ЮЛ: ИНН надёжнее названия (названия искажает и LLM, и OCR). */
    private static boolean sameLe(ExtractDirectory.Le a, ExtractDirectory.Le b) {
        if (a == null || b == null) return false;
        if (!a.inn().isBlank() && a.inn().equals(b.inn())) return true;
        return ExtractDirectory.norm(a.name()).equals(ExtractDirectory.norm(b.name()));
    }

    private static String nz(String s) {
        return s == null ? "" : s.strip();
    }

    /** Сверка одного поля: canonical==null → очистить; иначе подставить. [matched, cleared]. */
    private static int[] align(Map<String, String> out, String key, String canonical) {
        if (key == null) return new int[]{0, 0};
        String cur = out.getOrDefault(key, "");
        if (cur.isBlank()) return new int[]{0, 0};
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
        if (fileName == null) throw new IllegalArgumentException("имя файла не задан");
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
            out.put(k, stringify(got.get(k)));
        }
        return out;
    }

    /** NULL и отсутствие поля → "", строки — strip, прочее — String.valueOf. */
    private static String stringify(Object v) {
        if (v == null) return "";
        if (v instanceof String s) return s.strip();
        return String.valueOf(v);
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