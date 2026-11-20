package kvo.itsnn.rag.ask;

import kvo.itsnn.rag.config.RagProperties;
import kvo.itsnn.rag.llm.LlmClient;
import kvo.itsnn.rag.util.DocPatterns;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Сценарий «краткое содержание документа»: «краткое содержание СТО №19-ПЛ07.01 версия 6.0».
 * Векторный поиск по такому вопросу тащит семантически близкие бланки и реквизиты, а вводная
 * часть документа в топ не попадает — и LLM отказывается. Поэтому файл ищем по коду и версии
 * в ИМЕНИ (как CompareService, без Chroma) и берём первые фрагменты файла — титул, назначение,
 * область применения. Код и версии распознаёт DocPatterns (общий с CompareService);
 * здесь — только слова-маркеры суммаризации. Промпт — в rag_control (summary_system_prompt).
 */
@Service
public class DocSummaryService {

    private static final Logger log = LoggerFactory.getLogger(DocSummaryService.class);

    public static final String CTRL_KEY = "summary_system_prompt";

    private static final int MAX_CHUNK_CHARS = 1200;     // как в AskService
    private static final int MAX_CONTEXT_CHARS = 15_000;
    private static final int HEAD_CHUNKS = 10;           // сколько первых фрагментов берём

    /** Намерение «расскажи о документе»: «краткое содержание», «суть», «о чём», «описание»… */
    private static final Pattern SUMMARY_WORDS = DocPatterns.compile(
            "кратк(ое|о)?\\s+содержан"
                    + "|содержан(ие|ии)\\s+(документа|стандарт|сто|приказ)"
                    + "|суть\\s+(документа|стандарт|сто)"
                    + "|о\\s+ч[её]м\\s+(документ|стандарт|сто)"
                    + "|аннотац|описан(ие|ии)\\s+(документа|стандарт)"
                    + "|расскажи\\s+о\\s+(документе|стандарте)"
                    + "|что\\s+(такое|за)\\s+(документ|стандарт|сто)"
                    + "|основн(ые|ых)\\s+(положени|требован|раздел)");

    /** Резервный промпт; рабочий — в rag_control (summary_system_prompt). */
    private static final String DEFAULT_PROMPT = """
            Ты — помощник по внутренним нормативным документам. Пользователь просит краткое
            содержание конкретного документа. Тебе даны первые фрагменты файла (титул, назначение,
            область применения, начало разделов). Правила:
            1. Ответ начни строкой: тип и обозначение документа с версией — из имени файла.
            2. Затем краткое содержание по фрагментам: назначение, для кого/чего, основные темы,
            которые видны. Не выдумывай разделы, которых во фрагментах нет.
            3. Каждый вывод сопровождай ссылкой вида [имя файла, фрагмент N].
            4. Если во фрагментах только бланки/шаблоны и реквизиты — честно скажи это, опиши
            документ по обозначению и видимым реквизитам и добавь: «Полный текст — по ссылке
            в блоке Источники».
            5. Ответ закончи строкой: Полный текст: [имя файла]
            6. Если ниже указано, что в базе есть другие версии документа, — упомяни их одной
            строкой в конце (по ним можно задать вопрос-сравнение).""";

    private final RagProperties props;
    private final JdbcTemplate jdbc;
    private final LlmClient llm;
    private volatile boolean warned;

    public DocSummaryService(RagProperties props, JdbcTemplate jdbc, LlmClient llm) {
        this.props = props;
        this.jdbc = jdbc;
        this.llm = llm;
        log.info("DocSummaryService: включён, первых фрагментов на файл={}", HEAD_CHUNKS);
    }

    /** Файл, найденный по коду в имени. */
    private record FileHit(String fileName, String fileId, String documentGuid, String version) {}

    /** Фрагмент начала документа. */
    private record HeadChunk(int chunkNo, String content) {}

    /**
     * Наполнитель контекста: текст + источники + бюджет (MAX_CONTEXT_CHARS символов,
     * HEAD_CHUNKS фрагментов). Решение «влезает/нет» живёт здесь, чтобы циклы сборки
     * остались плоскими: один цикл — один break, когнитивная сложность по зубам Sonar.
     */
    private static final class Ctx {
        final StringBuilder text = new StringBuilder();
        final List<AskService.Source> sources = new ArrayList<>();
        private int chars;

        /** Бюджет исчерпан — следующие файлы даже не запрашиваем из БД. */
        boolean done() {
            return chars >= MAX_CONTEXT_CHARS || sources.size() >= HEAD_CHUNKS;
        }

        /** Добавить блок, если он влезает в бюджет; false — стоп для текущего файла. */
        boolean addIfFits(String block, AskService.Source s) {
            if (done() || chars + block.length() > MAX_CONTEXT_CHARS) {
                return false;
            }
            text.append(block);
            chars += block.length();
            sources.add(s);
            return true;
        }
    }

    /** Пустой Optional — вопрос не про содержание документа: обычный RAG-путь. */
    public Optional<AskService.AskResult> trySummary(String query, String classifier) {
        String code = DocPatterns.docCode(query);
        if (code == null || !SUMMARY_WORDS.matcher(query).find()) return Optional.empty();
        List<String> vs = DocPatterns.versions(query);
        if (vs.size() >= 2) return Optional.empty();   // две версии — это CompareService

        List<FileHit> files = findFiles(code, classifier);
        if (files.isEmpty()) return Optional.empty();

        String want = vs.isEmpty() ? null : vs.getFirst();
        List<FileHit> matched = want == null ? List.of(newest(files)) : withVersion(files, want);
        if (matched.isEmpty()) {
            return Optional.of(noSuchVersion(code, want, files));
        }
        return Optional.of(summarize(query, code, want, matched, otherVersions(files, matched)));
    }

    private AskService.AskResult summarize(String query, String code, String want,
                                           List<FileHit> matched, List<String> others) {
        Ctx ctx = buildContext(matched);
        String note = others.isEmpty() ? "" : "\nДругие версии документа в базе: "
                + String.join(", ", others) + ".";
        String user = "Документ: " + code + (want == null ? "" : ", версия " + want)
                + ".\n\nФрагменты начала файла:\n" + ctx.text + note + "\nВопрос: " + query.strip();

        long t0 = System.currentTimeMillis();
        String answer = llm.chat(List.of(LlmClient.Message.system(prompt()),
                LlmClient.Message.user(user)));
        log.info("SUMMARY: {}{} — {} фрагментов, LLM {} мс", code,
                want == null ? "" : " в." + want, ctx.sources.size(),
                System.currentTimeMillis() - t0);
        return new AskService.AskResult(answer, ctx.sources);
    }

    /** Контекст: первые фрагменты каждого найденного файла, пока влезает общий бюджет. */
    private Ctx buildContext(List<FileHit> matched) {
        Ctx ctx = new Ctx();
        for (FileHit f : matched) {                    // обычно один файл
            if (ctx.done()) break;                     // единственный break: не дёргаем БД зря
            appendFile(ctx, f);
        }
        return ctx;
    }

    /** Фрагменты одного файла — до первого не влезающего блока. */
    private void appendFile(Ctx ctx, FileHit f) {
        for (HeadChunk c : headChunks(f)) {
            String block = "[" + (ctx.sources.size() + 1) + "] (" + f.fileName()
                    + ", фрагмент " + c.chunkNo() + ")\n" + clip(c.content()) + "\n\n";
            AskService.Source src = new AskService.Source(f.documentGuid(), f.fileName(),
                    c.chunkNo(), 1.0, props.docCardUrl(f.fileId()));
            if (!ctx.addIfFits(block, src)) break;     // единственный break метода
        }
    }

    /** Фрагмент — не длиннее MAX_CHUNK_CHARS (как в AskService). */
    private static String clip(String content) {
        return content.length() > MAX_CHUNK_CHARS ? content.substring(0, MAX_CHUNK_CHARS) : content;
    }

    /** Названной версии нет — честно говорим, какие есть, со ссылками. */
    private AskService.AskResult noSuchVersion(String code, String want, List<FileHit> files) {
        List<String> vers = files.stream().map(FileHit::version)
                .filter(Objects::nonNull).distinct().sorted().toList();
        log.info("SUMMARY: {} — версии {} нет, есть {}", code, want, vers);
        List<AskService.Source> sources = files.stream()
                .map(f -> new AskService.Source(f.documentGuid(), f.fileName(), 0, 1.0,
                        props.docCardUrl(f.fileId())))
                .toList();
        return new AskService.AskResult(
                "В базе знаний нет документа " + code + " версии " + want + ". Доступны версии: "
                        + String.join(", ", vers) + ". Откройте нужную по ссылкам в источниках ниже"
                        + " или спросите, например: «краткое содержание " + code + " версия "
                        + vers.getFirst() + "».", sources);
    }

    /** Первые фрагменты файла — титул, назначение, область применения. */
    private List<HeadChunk> headChunks(FileHit f) {
        return jdbc.query("""
                SELECT TOP (?) chunk_no, LEFT(content, ?) AS content
                FROM dbo.rag_chunk
                WHERE file_id = ? AND document_guid = ?
                ORDER BY chunk_no
                """, (rs, i) -> new HeadChunk(rs.getInt(1), rs.getString(2)),
                HEAD_CHUNKS, MAX_CHUNK_CHARS, UUID.fromString(f.fileId()),
                UUID.fromString(f.documentGuid()));
    }

    /** Файлы по коду в имени; копии одного файла (одно имя, разные file_id) не дублируем. */
    private List<FileHit> findFiles(String code, String classifier) {
        boolean byCls = classifier != null && !classifier.isBlank();
        String sql = """
                SELECT c.file_name, c.file_id, c.document_guid
                FROM dbo.rag_chunk c
                JOIN dbo.rag_document_ref r ON r.document_guid = c.document_guid AND r.row_id = c.file_id
                WHERE r.status = 'I' AND c.file_name LIKE ?
                """ + (byCls ? " AND r.classifier = ?" : "") + " ORDER BY c.file_name";
        List<Object> args = new ArrayList<>(List.of("%" + code + "%"));
        if (byCls) args.add(classifier);

        List<FileHit> all = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        jdbc.query(sql, rs -> {
            String name = rs.getString(1);
            if (!seen.add(name.toLowerCase(Locale.ROOT))) return;
            all.add(new FileHit(name, rs.getString(2), rs.getString(3), DocPatterns.versionOf(name)));
        }, args.toArray());
        return all;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        try {
            int n = jdbc.update("""
                    INSERT INTO dbo.rag_control (ctrl_key, ctrl_value)
                    SELECT ?, ?
                    WHERE NOT EXISTS (SELECT 1 FROM dbo.rag_control WHERE ctrl_key = ?)
                    """, CTRL_KEY, DEFAULT_PROMPT, CTRL_KEY);
            if (n > 0) {
                log.info("rag_control: записан дефолт промпта краткого содержания ('{}')", CTRL_KEY);
            }
        } catch (Exception e) {
            log.warn("rag_control: дефолт промпта не записан ({}), работает резерв из кода",
                    e.getMessage());
        }
    }

    private String prompt() {
        try {
            List<String> v = jdbc.queryForList(
                    "SELECT ctrl_value FROM dbo.rag_control WHERE ctrl_key = ?",
                    String.class, CTRL_KEY);
            if (!v.isEmpty() && v.getFirst() != null && !v.getFirst().isBlank()) return v.getFirst();
        } catch (Exception e) {
            if (!warned) {
                log.warn("rag_control: промпт краткого содержания не прочитан ({}), дефолт",
                        e.getMessage());
                warned = true;
            }
        }
        return DEFAULT_PROMPT;
    }

    /** «6.0» не должна матчить «16.0»/«6.01» — границы по не-цифре (DocPatterns.exactVersion). */
    private static List<FileHit> withVersion(List<FileHit> files, String version) {
        Pattern p = DocPatterns.exactVersion(version);
        return files.stream()
                .filter(f -> f.version() != null && p.matcher(f.fileName()).find()).toList();
    }

    private static FileHit newest(List<FileHit> files) {
        return files.stream().max(Comparator.comparingDouble(f -> DocPatterns.versionKey(f.version()))).orElseThrow();
    }

    private static List<String> otherVersions(List<FileHit> files, List<FileHit> matched) {
        Set<String> used = new HashSet<>();
        matched.forEach(f -> used.add(f.version()));
        return files.stream().map(FileHit::version)
                .filter(v -> v != null && !used.contains(v))
                .distinct().sorted().toList();
    }
}