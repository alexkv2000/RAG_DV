package kvo.itsnn.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "rag.llm")
public record LlmProperties(
        String baseUrl,                       // обязательны!
        String apiKey,
        String model,
        @DefaultValue("0.2")   double temperature,
        @DefaultValue("4096")  int maxTokens,
        @DefaultValue("120")   long timeoutSeconds,
        @DefaultValue("3")     int historyTurns,
        @DefaultValue("6")     int contextChunks,
        @DefaultValue("24")    int candidateChunks,
        // true → vLLM: chat_template_kwargs.enable_thinking=false (Qwen3 не «думает», ответ в разы быстрее)
        @DefaultValue("true")  boolean disableThinking,
        // === D: сценарий «сравнение версий одного документа» ===
        @DefaultValue("true")  boolean compareEnabled,
        @DefaultValue("8")     int compareChunksPerSide) {
}