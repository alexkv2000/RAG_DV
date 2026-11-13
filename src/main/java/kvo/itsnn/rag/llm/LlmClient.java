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
        log.info("LlmClient: url='{}', model='{}', maxTokens={}, temperature={}, timeout={} с, thinking={}",
                cfg.baseUrl(), cfg.model(), cfg.maxTokens(), cfg.temperature(), cfg.timeoutSeconds(),
                cfg.disableThinking() ? "выключен" : "включён");
    }

    public String chat(List<Message> messages) {
        String url = cfg.baseUrl().replaceAll("/+$", "") + "/chat/completions";
        long t0 = System.currentTimeMillis();
        // vLLM: размышления Qwen3 выключаем на уровне chat-шаблона — быстрее и дешевле
        Map<String, Object> noThink = cfg.disableThinking()
                ? Map.of("enable_thinking", false) : null;
        String raw;
        try {
            byte[] bytes = http.post()
                    .uri("/chat/completions")
                    .accept(MediaType.APPLICATION_JSON)   // шлюзу намекаем, что ждём JSON
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new Request(cfg.model(), messages, cfg.temperature(), cfg.maxTokens(), noThink))
                    .retrieve()
                    .body(byte[].class);                  // byte[] читается при любом content-type
            raw = bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);
            log.debug("LLM RAW: {}", raw);
        } catch (RestClientResponseException e) {
            throw new IllegalStateException("LLM " + e.getStatusCode().value() + " на " + url
                    + hint(e.getStatusCode().value()) + ": " + brief(e.getResponseBodyAsString()), e);
        }
        if (raw.isBlank()) throw new IllegalStateException("LLM: пустой ответ от " + url);

        Response resp;
        try {
            resp = JSON.readValue(raw, Response.class);
        } catch (Exception e) {
            throw new IllegalStateException("LLM: ответ не JSON от " + url + ": " + brief(raw), e);
        }
        if (resp.choices() == null || resp.choices().isEmpty())
            throw new IllegalStateException("LLM: пустой ответ от " + url);

        Response.Choice c = resp.choices().get(0);
        if ("length".equals(c.finish_reason())) {
            log.warn("LLM: ответ обрезан (finish_reason=length) — лимит max_tokens={}; поднимите rag.llm.max-tokens",
                    cfg.maxTokens());
        }
        log.info("LLM: {} мс, сообщений={}, tokens={}",
                System.currentTimeMillis() - t0, messages.size(),
                resp.usage() == null ? "?" : resp.usage().total_tokens());

        String text = stripThink(c.message().content());
        if (text.isBlank()) {
            // Qwen3 (thinking) может положить весь ответ в поле размышлений, оставив content пустым;
            // vLLM 0.28 называет его "reasoning", другие шлюзы — "reasoning_content" — берём любой
            String r = c.message().reasoning_content() != null
                    ? c.message().reasoning_content() : c.message().reasoning();
            String reasoning = stripThink(r);
            if (!reasoning.isBlank()) {
                log.warn("LLM: content пуст — ответ собран из размышлений ({} симв.)", reasoning.length());
                text = reasoning;
            }
        }
        if (text.isBlank()) {
            throw new IllegalStateException("LLM: модель не вернула текст ответа (finish_reason="
                    + c.finish_reason() + ", tokens="
                    + (resp.usage() == null ? "?" : resp.usage().total_tokens())
                    + ") — вероятно, весь лимит израсходован на блок размышлений");
        }
        return text;
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