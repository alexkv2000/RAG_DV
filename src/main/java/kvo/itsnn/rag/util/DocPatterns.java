package kvo.itsnn.rag.util;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Общие regex-паттерны распознавания документов: код (19-ПЛ07.01) и версия (6.0, в.4, ред. 2).
 * Единая точка правды для CompareService («сравнение версий») и DocSummaryService
 * («краткое содержание»): раньше паттерны дублировались в двух классах и расходились
 * при правках (DocSummary так и остался со старым VERSION без lookahead).
 *
 * Здесь — только распознавание сущностей. Слова НАМЕРЕНИЯ («отличается», «краткое
 * содержание») специфичны для сценария и живут в своих сервисах.
 */
public final class DocPatterns {

    /* CANON_EQ: «№» и кириллица чувствительны к нормализации Юникода; флаг приравнивает
       разложенные формы («й» = и+U+0306 из копипасты PDF) к обычным при сопоставлении.
       Приватен: наружу отдаём compile(), чтобы набор флагов жил в одном месте. */
    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
            | Pattern.UNICODE_CHARACTER_CLASS | Pattern.CANON_EQ;

    /** Код документа вида 19-ПЛ07.01 / 12-ИТ3.2 (кириллица или латиница, любой дефис). */
    public static final Pattern DOC_CODE = Pattern.compile(
            "\\d{1,3}\\s*[-–]\\s*[А-ЯA-Z]{1,6}\\s*\\d{1,3}\\.\\d{1,2}", FLAGS);

    /** «версия 6.0», «версии 6,0», «в.6.0», «v.6.0», «редакция 3». Повтор дробной части
     *  ограничен {0,3} (до 4 компонентов номера) — ограниченный квантификатор компилируется
     *  без рекурсии, переполнение стека невозможно. Lookahead (?![.,]\d) не даёт отхватить
     *  «6.0» из начала длинного номера «6.0.1»: матчится либо номер целиком, либо ничего. */
    public static final Pattern VERSION = Pattern.compile(
            "(?:верси\\w*|редакци\\w*|\\b[вv]\\.)\\s*№?\\s*(\\d+(?:[.,]\\d+){0,3})(?![.,]\\d)", FLAGS);

    private DocPatterns() {
    }

    /** Компиляция сценарий-специфичного паттерна с тем же кириллически-безопасным
     *  набором флагов, что и у DOC_CODE/VERSION (COMPARE_WORDS, SUMMARY_WORDS и т.п.). */
    public static Pattern compile(String regex) {
        return Pattern.compile(regex, FLAGS);
    }

    /** Код документа из текста в нормализованном виде («–»→«-», без пробелов); null — кода нет. */
    public static String docCode(String text) {
        if (text == null) return null;
        Matcher m = DOC_CODE.matcher(text);
        return m.find() ? m.group().replace("–", "-").replaceAll("\\s+", "") : null;
    }

    /** Маркерные версии из текста («версия 6.0», «в.4») в порядке появления, без дублей,
     *  запятая в дроби → точка. Код документа вырезается заранее: в «19-ПЛ07.01» есть «07.01»,
     *  а «в.19-ПЛ07.01» не должен дать версию «19». Резервный поиск версий без маркера
     *  («чем 6.0 отличается от 4.0») — сценарий-специфичный, см. CompareService.FRACTION. */
    public static List<String> versions(String text) {
        if (text == null) return List.of();
        String cleaned = DOC_CODE.matcher(text).replaceAll(" ");
        Set<String> out = new LinkedHashSet<>();
        Matcher m = VERSION.matcher(cleaned);
        while (m.find()) out.add(m.group(1).replace(',', '.'));
        return new ArrayList<>(out);
    }

    /** Первая маркерная версия в тексте (обычно — имя файла); null — версий нет. */
    public static String versionOf(String text) {
        List<String> v = versions(text);
        return v.isEmpty() ? null : v.getFirst();
    }

    /** Матчер точной версии в тексте: границы по не-цифре — «6.0» не матчит «16.0»/«6.01». */
    public static Pattern exactVersion(String version) {
        return Pattern.compile("(?<!\\d)" + Pattern.quote(version) + "(?!\\d)");
    }

    /** Ключ сортировки «новее»: 6.0 → 6000, 4.0 → 4000 (первые два компонента); null → -1. */
    public static double versionKey(String v) {
        if (v == null) return -1;
        String[] p = v.split("\\.");
        double d = p.length > 0 ? Integer.parseInt(p[0]) * 1000.0 : 0;
        if (p.length > 1) d += Integer.parseInt(p[1]);
        return d;
    }
}