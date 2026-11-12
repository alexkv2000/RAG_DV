package kvo.itsnn.rag.ask;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * System-промпт RAG живёт в dbo.rag_control (ключ 'ask_system_prompt') —
 * правится UPDATE'ом в SSMS и применяется к следующему вопросу без рестарта.
 * Дефолт хранится в коде как резерв и при первом старте записывается в таблицу.
 * Чтение на каждый ask(): один SELECT по ключу несущественен на фоне LLM-вызова.
 */
@Component
public class SystemPromptProvider {

    private static final Logger log = LoggerFactory.getLogger(SystemPromptProvider.class);

    public static final String CTRL_KEY = "ask_system_prompt";

    /** Резерв: строки в rag_control нет / пустая / БД недоступна. */
    private static final String DEFAULT = """
            Ты — ассистент по внутренним документам организации. Отвечай строго на основе
            приведённого контекста. Правила:
            1. Отвечай на русском, по делу, полно, но без воды.
            2. Каждое утверждение сопровождай источником в квадратных скобках, например [Приказ 41.pdf, фрагмент 3].
            3. Если в контексте нет нужной информации — ответь: «В доступных документах нет данных по этому вопросу». Не выдумывай.
            4. Если информация разбита по нескольким фрагментам — объединяй её в связный ответ.""";

    private final JdbcTemplate jdbc;
    private volatile boolean warned;

    public SystemPromptProvider(JdbcTemplate jdbc) {
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
                log.info("rag_control: записан дефолтный system-промпт ('{}')", CTRL_KEY);
            }
        } catch (Exception e) {
            log.warn("rag_control: дефолтный промпт не записан ({}), работает резерв из кода", e.getMessage());
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
            if (!warned) {                 // один WARN на серию сбоев, а не на каждый ask()
                log.warn("rag_control: промпт не прочитан ({}), используется дефолт", e.getMessage());
                warned = true;
            }
        }
        return DEFAULT;
    }
}