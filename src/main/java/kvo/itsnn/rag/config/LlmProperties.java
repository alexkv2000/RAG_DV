package kvo.itsnn.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
@ConfigurationProperties(prefix = "rag.llm")
public record LlmProperties(
        String baseUrl,                       // обязательны!
        String apiKey,
        String model,
        @DefaultValue("0.2") double temperature,
        @DefaultValue("4096") int maxTokens,
        @DefaultValue("120") long timeoutSeconds,
        @DefaultValue("3") int historyTurns,
        @DefaultValue("6") int contextChunks,
        @DefaultValue("24") int candidateChunks,
        // true → vLLM: chat_template_kwargs.enable_thinking=false (Qwen3 не «думает», ответ в разы быстрее)
        @DefaultValue("true") boolean disableThinking,
        // === D: сценарий «сравнение версий одного документа» ===
        @DefaultValue("true") boolean compareEnabled,
        @DefaultValue("8") int compareChunksPerSide,
        /* === файлы свободного чата (direct-chat) === */
        /* Потолок РАЗМЕРА одного прикреплённого файла, МБ. Больше 64 задавать бессмысленно:
           multipart-потолок UploadConfig (64 МБ) отклонит запрос раньше — HTTP 413
           вместо понятного сообщения сервиса (DirectChatService предупредит WARN'ом). */
        @DefaultValue("32") int directMaxFileMb,
        /* Сколько файлов можно прикрепить к одной сессии свободного чата. */
        @DefaultValue("3") int directMaxFiles,
        /* Лимит текста одного прикреплённого файла свободного чата, симв. */
        @DefaultValue("120000") int directMaxFileChars,
        /* Оценочный бюджет токенов на ВСЕ прикреплённые файлы сессии свободного чата:
           контекст Qwen3 131 072 − max-tokens 4 096 − история (3 ответа × 4 096) и вопрос ≈ 110 тыс. */
        @DefaultValue("100000") int directMaxSessionTokens,
        /* TTL сессий чата (RAG и свободного), минут: неактивная дольше — удаляется из памяти
           вместе с прикреплёнными файлами. localStorage клиента живёт дольше — вернувшемуся
           пользователю лента останется, но история для LLM начнётся заново.
           0 — бессрочно, как раньше (Map без очистки). */
        @DefaultValue("30") int sessionTtlMinutes) {
}