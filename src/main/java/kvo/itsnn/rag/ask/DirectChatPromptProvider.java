package kvo.itsnn.rag.ask;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * System-промпт свободного чата (без базы знаний) живёт в dbo.rag_control
 * (ключ 'direct_chat_system_prompt') — правится в админ-вкладке «Промты» или
 * UPDATE'ом в SSMS и применяется к следующему вопросу без рестарта.
 * Дефолт хранится в коде как резерв и при первом старте записывается в таблицу.
 */
@Component
public class DirectChatPromptProvider {

    private static final Logger log = LoggerFactory.getLogger(DirectChatPromptProvider.class);

    public static final String CTRL_KEY = "direct_chat_system_prompt";

    /** Резерв: строки в rag_control нет / пустая / БД недоступна. */
    private static final String DEFAULT = """
            Ты — ассистент. Отвечай на русском, по делу, без воды.
            Если вопрос касается внутренних документов/документов организации — честно скажи,
            что в этом режиме у тебя нет доступа к базе знаний, и предложи переключиться
            в режим «По документам».""";

    private final JdbcTemplate jdbc;
    private volatile boolean warned;

    public DirectChatPromptProvider(JdbcTemplate jdbc) {
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
                log.info("rag_control: записан дефолтный промпт свободного чата ('{}')", CTRL_KEY);
            }
        } catch (Exception e) {
            log.warn("rag_control: дефолт промпта свободного чата не записан ({}), работает резерв из кода",
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
            if (!warned) {                 // один WARN на серию сбоев, а не на каждый вопрос
                log.warn("rag_control: промпт свободного чата не прочитан ({}), используется дефолт",
                        e.getMessage());
                warned = true;
            }
        }
        return DEFAULT;
    }
}