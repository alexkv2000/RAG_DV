package kvo.itsnn.rag.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import kvo.itsnn.rag.config.LlmProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Клиент OpenAI-совместимого /chat/completions (ai.loodsen.ru). */
@Component
public class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);

    // Qwen3 может вернуть блок размышлений — вырезаем его из ответа
    private static final Pattern THINK =
            Pattern.compile("(?s)" + "<" + "think" + ">" + ".*?" + "<" + "/" + "think" + ">");

    /** Разбор JSON сами (Jackson 3): шлюз иногда отвечает content-type=application/octet-stream,
     *  и конвертеры Spring выбрасывали "Error while extracting response". */
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    /** Лог исходящего тела: RAG-контекст длинный (до 15 000 симв.) — обрезаем;
     *  самопроверка llm-selftest помещается целиком. Поднимите, если нужно всё. */
    private static final int MAX_REQ_LOG = 4_000;

    private final RestClient http;
    private final LlmProperties cfg;

    public LlmClient(LlmProperties cfg) {
        this.cfg = cfg;
        var f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(10_000);
        f.setReadTimeout((int) (cfg.timeoutSeconds() * 1000));
        this.http = RestClient.builder()
                .baseUrl(cfg.baseUrl())
                .defaultHeader("Authorization", "Bearer " + cfg.apiKey())
                .requestFactory(f)
                .build();
        if (log.isInfoEnabled()) {
            log.info("LlmClient: url='{}', model='{}', maxTokens={}, temperature={}, timeout={} с, thinking={}",
                    cfg.baseUrl(), cfg.model(), cfg.maxTokens(), cfg.temperature(), cfg.timeoutSeconds(),
                    cfg.disableThinking() ? "выключен" : "включён");
        }
    }

    public String chat(List<Message> messages) {
        String url = cfg.baseUrl().replaceAll("/+$", "") + "/chat/completions";
        long t0 = System.currentTimeMillis();
        Request payload = buildRequest(messages);
        // >>> исходящий запрос в интернет: строка и тело ровно те, что уходят в сеть без token api-key.
        // S2629: сериализация тела для лога дорогая (весь RAG-контекст) — только при включённом INFO.
        if (log.isInfoEnabled()) {
            log.info(">>> LLM POST {} тело: {}", url, reqBodyForLog(payload));
        }
        Response resp = parseResponse(post(url, payload), url);
        Response.Choice c = firstChoice(resp, url);
        warnIfTruncated(c);
        logTiming(t0, messages, resp);
        return answerText(c, resp);
    }

    /** vLLM: размышления Qwen3 выключаем на уровне chat-шаблона — быстрее и дешевле. */
    private Request buildRequest(List<Message> messages) {
        Map<String, Object> noThink = cfg.disableThinking()
                ? Map.of("enable_thinking", false) : null;
        return new Request(cfg.model(), messages, cfg.temperature(), cfg.maxTokens(), noThink);
    }

    /** POST к шлюзу; byte[] читается при любом content-type. Ошибка HTTP → IllegalStateException с подсказкой. */
    private String post(String url, Request payload) {
        try {
            byte[] bytes = http.post()
                    .uri("/chat/completions")
                    .accept(MediaType.APPLICATION_JSON)   // шлюзу намекаем, что ждём JSON
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)                        // тот же объект, что записан в лог выше
                    .retrieve()
                    .body(byte[].class);
            String raw = bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);
            log.debug("LLM RAW: {}", raw);
            if (raw.isBlank()) {
                throw new IllegalStateException("LLM: пустой ответ от " + url);
            }
            return raw;
        } catch (RestClientResponseException e) {
            throw new IllegalStateException("LLM " + e.getStatusCode().value() + " на " + url
                    + hint(e.getStatusCode().value()) + ": " + brief(e.getResponseBodyAsString()), e);
        }
    }

    private Response parseResponse(String raw, String url) {
        try {
            return JSON.readValue(raw, Response.class);
        } catch (Exception e) {
            throw new IllegalStateException("LLM: ответ не JSON от " + url + ": " + brief(raw), e);
        }
    }

    private Response.Choice firstChoice(Response resp, String url) {
        if (resp.choices() == null || resp.choices().isEmpty()) {
            throw new IllegalStateException("LLM: пустой ответ от " + url);
        }
        return resp.choices().get(0);
    }

    private void warnIfTruncated(Response.Choice c) {
        if ("length".equals(c.finish_reason()) && log.isWarnEnabled()) {
            log.warn("LLM: ответ обрезан (finish_reason=length) — лимит max_tokens={}; поднимите rag.llm.max-tokens",
                    cfg.maxTokens());
        }
    }

    private void logTiming(long t0, List<Message> messages, Response resp) {
        if (log.isInfoEnabled()) {
            log.info("<<< LLM {} мс, сообщений={}, tokens={}",
                    System.currentTimeMillis() - t0, messages.size(), totalTokens(resp));
        }
    }

    /** Qwen3 (thinking) может положить весь ответ в поле размышлений, оставив content пустым, — берём его. */
    private String answerText(Response.Choice c, Response resp) {
        String text = stripThink(c.message().content());
        if (text.isBlank()) {
            String reasoning = stripThink(firstNonNull(c.message().reasoning_content(), c.message().reasoning()));
            if (!reasoning.isBlank()) {
                if (log.isWarnEnabled()) {
                    log.warn("LLM: content пуст — ответ собран из размышлений ({} симв.)", reasoning.length());
                }
                text = reasoning;
            }
        }
        if (text.isBlank()) {
            throw new IllegalStateException("LLM: модель не вернула текст ответа (finish_reason="
                    + c.finish_reason() + ", tokens=" + totalTokens(resp)
                    + ") — вероятно, весь лимит израсходован на блок размышлений");
        }
        return text;
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }

    private static String totalTokens(Response resp) {
        return resp.usage() == null ? "?" : String.valueOf(resp.usage().total_tokens());
    }

    /** Тело запроса одной строкой для лога; длинное — с обрезкой и счётчиком. */
    private String reqBodyForLog(Request payload) {
        String json = JSON.writeValueAsString(payload);   // Jackson 3: исключение unchecked
        return json.length() > MAX_REQ_LOG
                ? json.substring(0, MAX_REQ_LOG) + "…(обрезано, всего " + json.length() + " симв.)"
                : json;
    }

    private static String stripThink(String s) {
        if (s == null) {
            return "";
        }
        String out = THINK.matcher(s).replaceAll("");
        int open = out.indexOf("<" + "think" + ">");   // конкатенация соберётся в тег при компиляции
        if (open >= 0) {
            out = out.substring(0, open);
        }
        return out.strip();
    }

    private static String hint(int status) {
        return switch (status) {
            case 401, 403 -> " — неверный api-key";
            case 404      -> " — путь не существует, проверьте base-url";
            case 405      -> " — путь существует, но POST туда не принимается: base-url собран не в тот URL";
            default       -> "";
        };
    }

    private static String brief(String body) {
        if (body == null || body.isBlank()) return "";
        String s = body.replaceAll("\\s+", " ").trim();
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }

    public record Message(String role, String content) {
        public static Message system(String c)    { return new Message("system", c); }
        public static Message user(String c)      { return new Message("user", c); }
        public static Message assistant(String c) { return new Message("assistant", c); }
    }

    /** NON_NULL: без флага поле chat_template_kwargs не попадает в JSON вовсе. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Request(String model, List<Message> messages, double temperature, int max_tokens,
                   Map<String, Object> chat_template_kwargs) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Response(List<Choice> choices, Usage usage) {
        @JsonIgnoreProperties(ignoreUnknown = true) record Choice(Message2 message, String finish_reason) {}
        @JsonIgnoreProperties(ignoreUnknown = true)
        record Message2(String content, String reasoning_content, String reasoning) {}   // vLLM 0.28 → "reasoning"
        @JsonIgnoreProperties(ignoreUnknown = true) record Usage(int total_tokens) {}
    }
}