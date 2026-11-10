package kvo.itsnn.rag.ingest;

import kvo.itsnn.rag.config.RagProperties;
import kvo.itsnn.rag.util.VecMath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.*;

public class OpenAiCompatibleEmbedder implements Embedder {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleEmbedder.class);

    private final RestClient http;
    private final RagProperties.Embedding cfg;
    private final String path;   // uri-путь для запроса
    private final String url;    // полный URL для логов/диагностики

    public OpenAiCompatibleEmbedder(RagProperties props) {
        this.cfg = props.embedding();
        String base = cfg.baseUrl() == null ? "" : cfg.baseUrl().replaceAll("/+$", "");
        String p = cfg.endpointPath() == null ? "/embeddings"
                : cfg.endpointPath().startsWith("/") ? cfg.endpointPath() : "/" + cfg.endpointPath();
        this.path = p;
        this.url = base + p;
        this.http = RestClient.builder()
                .baseUrl(base)
                .defaultHeader("Authorization", "Bearer " + cfg.apiKey())
                .build();
        log.info("Embedding-сервис: url='{}', model='{}', batch={}", url, cfg.model(), cfg.batch());
    }

    @Override public String model()    { return cfg.model(); }
    @Override public String endpoint() { return url; }

    @Override
    public List<float[]> embedAll(List<String> texts) {
        List<float[]> out = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += cfg.batch()) {
            List<String> part = texts.subList(from, Math.min(texts.size(), from + cfg.batch()));
            ResponseDto resp;
            try {
                resp = http.post().uri(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(new RequestDto(cfg.model(), part))
                        .retrieve().body(ResponseDto.class);
            } catch (RestClientResponseException e) {
                throw new EmbeddingServiceException(describe(e.getStatusCode().value(), e.getResponseBodyAsString()), e);
            } catch (RestClientException e) {   // connection refused, таймаут, HTML вместо JSON
                throw new EmbeddingServiceException("Embedding-сервис " + url + " недоступен: " + e.getMessage(), e);
            }
            if (resp == null || resp.data() == null || resp.data().size() != part.size())
                throw new EmbeddingServiceException("Некорректный ответ от " + url
                        + " — ожидается {\"data\":[{\"embedding\":[...]}]}", null);
            resp.data().forEach(d -> out.add(VecMath.normalize(d.embedding())));
        }
        return out;
    }

    private String describe(int status, String body) {
        String hint = switch (status) {
            case 401, 403 -> " — проверьте api-key (заголовок 'Authorization: Bearer <ключ>')";
            case 404       -> " — путь не существует, проверьте endpoint-path";
            case 405       -> " — путь есть, но POST запрещён (обычно не тот путь)";
            default        -> "";
        };
        return "Embedding-сервис " + url + " ответил " + status + hint + ": " + brief(body);
    }

    /** Ответ в одну строку, ≤300 символов; HTML распознаём и помечаем. */
    private static String brief(String body) {
        if (body == null || body.isBlank()) return "";
        String s = body.replaceAll("\\s+", " ").trim();
        if (s.startsWith("<")) return "[HTML вместо JSON — это страница UI, а не API-путь]";
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }

    record RequestDto(String model, List<String> input) {}
    record ResponseDto(List<Item> data) {}
    record Item(float[] embedding) {}
}