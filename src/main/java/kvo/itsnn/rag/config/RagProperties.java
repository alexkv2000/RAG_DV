package kvo.itsnn.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.time.LocalTime;
import java.util.Map;

@ConfigurationProperties(prefix = "rag")
public record RagProperties(
        @DefaultValue("15s")   Duration pollInterval,
        @DefaultValue("1")     int ingestBatchDay,     // дневной поток: по одной карточке
        @DefaultValue("16")    int ingestBatchNight,   // ночное окно: полная скорость
        @DefaultValue("23:00") LocalTime nightStart,
        @DefaultValue("05:00") LocalTime nightEnd,
        @DefaultValue("60")    int dayHourLimit,       // потолок дневной скорости, карточек/час
        @DefaultValue("100")   int cleanupBatch,
        @DefaultValue("3")     int maxRetries,
        @DefaultValue Chunk chunk,
        Embedding embedding,
        @DefaultValue Chroma chroma,
        @DefaultValue Ocr ocr,
        Content content) {

    // было 1200/200 — снижено из-за лимита 512 токенов у модели
    public record Chunk(@DefaultValue("400") int targetChars,
                        @DefaultValue("80")  int overlapChars) {}

    public record Embedding(
            @DefaultValue("intfloat/multilingual-e5-small") String localModel,
            String localPath,
            String baseUrl, String apiKey,
            @DefaultValue("text-embedding-3-small") String model,
            @DefaultValue("384") int dims,
            @DefaultValue("16") int batch,
            @DefaultValue("passage: ") String passagePrefix,
            @DefaultValue("query: ") String queryPrefix,
            @DefaultValue("/embeddings") String endpointPath) {}

    public record Chroma(@DefaultValue("http://localhost:8000") String baseUrl,
                         String token,
                         @DefaultValue("rag_chunks") String collection,
                         @DefaultValue("128") int batch) {}

    public record Ocr(@DefaultValue("false") boolean enabled,
                      @DefaultValue("rus+eng") String lang,
                      @DefaultValue("200") int dpi,
                      @DefaultValue("30") int maxPages,   // защита CPU: длинные сканы — первые N страниц
                      String dataPath) {}                 // папка с tessdata, напр. C:/tessdata/

    /** Корни файловых хранилищ Docsvision: имя из dvsys_binary_storages.Name → корень (UNC/папка).
     *  Для стора 'Database' (хранение в БД) корень не нужен — байты приходят из колонки content. */
    public record Content(Map<String, String> roots) {}
}