package kvo.itsnn.rag.ask;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * System-промпт сценария «сравнение версий» живёт в dbo.rag_control
 * (ключ 'compare_system_prompt') — правится UPDATE'ом в SSMS и применяется
 * к следующему вопросу без рестарта. Дефолт хранится в коде как резерв
 * и при первом старте записывается в таблицу.
 * Чтение на каждый compare(): один SELECT по ключу несущественен на фоне LLM-вызова.
 */
@Component
public class ComparePromptProvider {

    private static final Logger log = LoggerFactory.getLogger(ComparePromptProvider.class);

    public static final String CTRL_KEY = "compare_system_prompt";

    /** Резерв: строки в rag_control нет / пустая / БД недоступна. */
    private static final String DEFAULT = """
            Ты — помощник по нормативным документам. Пользователь спрашивает об отличиях двух версий
            одного документа. Тебе даны фрагменты обеих версий в секциях «=== ВЕРСИЯ ... ===»,
            каждый фрагмент — строкой вида [№] (имя файла, фрагмент N).
            Правила:
            1. Сравнивай только то, что видно во фрагментах: никаких внешних знаний и догадок.
            2. Ответ структурируй разделами: «Изменилось», «Добавлено в новой версии»,
            «Убрано из новой версии», «Не изменилось (по доступным фрагментам)». Пустые разделы опускай.
            3. Каждый пункт подтверждай ссылкой на фрагмент, например: (версия 6.0, фрагмент 12).
            4. Если фрагменты не покрывают тему вопроса или различий не видно — прямо напиши об этом;
            не придумывай различия.
            5. Если по вопросу в одной из версий данных нет, а в другой есть — так и скажи.""";

    private final JdbcTemplate jdbc;
    private volatile boolean warned;

    public ComparePromptProvider(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** При первом старте сеем дефолт, чтобы промптом можно было управлять из БД. */
    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        try {
            int n = jdbc.update("""
                    INSERT INTO dbo.rag_control (ctrl_key, ctrl_value)
                    SELECT ?, ?
                    WHERE NOT EXISTS (SELECT 1 FROM dbo.rag_control WHERE ctrl_key = ?)
                    """, CTRL_KEY, DEFAULT, CTRL_KEY);
            if (n > 0) {
                log.info("rag_control: записан дефолтный промпт сравнения ('{}')", CTRL_KEY);
            }
        } catch (Exception e) {
            log.warn("rag_control: дефолт промпта сравнения не записан ({}), работает резерв из кода",
                    e.getMessage());
        }
    }

    /** Текущий промпт; пустое значение или сбой чтения → дефолт из кода. */
    public String prompt() {
        try {
            List<String> v = jdbc.queryForList(
                    "SELECT ctrl_value FROM dbo.rag_control WHERE ctrl_key = ?", String.class, CTRL_KEY);
            if (!v.isEmpty() && v.get(0) != null && !v.get(0).isBlank()) {
                warned = false;
                return v.get(0);
            }
        } catch (Exception e) {
            if (!warned) {                 // один WARN на серию сбоев, а не на каждый compare()
                log.warn("rag_control: промпт сравнения не прочитан ({}), используется дефолт",
                        e.getMessage());
                warned = true;
            }
        }
        return DEFAULT;
    }
}