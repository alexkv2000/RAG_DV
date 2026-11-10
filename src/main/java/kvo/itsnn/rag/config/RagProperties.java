package kvo.itsnn.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.time.Duration;

@ConfigurationProperties(prefix = "rag")
public record RagProperties(
        @DefaultValue("15s") Duration pollInterval,
        @DefaultValue("16")  int ingestBatch,
        @DefaultValue("100") int cleanupBatch,
        @DefaultValue("3")   int maxRetries,
        @DefaultValue Chunk chunk,
        Embedding embedding,
        @DefaultValue Chroma chroma) {

    // было 1200/200 — снижено из-за лимита 512 токенов у модели
    public record Chunk(@DefaultValue("400") int targetChars,
                        @DefaultValue("80")  int overlapChars) {}

    public record Embedding(
            @DefaultValue("intfloat/multilingual-e5-small") String localModel,
            String localPath,                        // offline: папка с уже скачанной моделью
            String baseUrl, String apiKey,           // поля для будущего возврата к внешнему API
            @DefaultValue("text-embedding-3-small") String model,
            @DefaultValue("384") int dims,
            @DefaultValue("16") int batch,
            @DefaultValue("passage: ") String passagePrefix,   // префиксы обязательны для e5
            @DefaultValue("query: ") String queryPrefix,
            @DefaultValue("/embeddings") String endpointPath) {}

    public record Chroma(@DefaultValue("http://localhost:8000") String baseUrl,
                         String token,
                         @DefaultValue("rag_chunks") String collection,
                         @DefaultValue("128") int batch) {}
}