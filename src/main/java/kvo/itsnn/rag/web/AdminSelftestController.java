package kvo.itsnn.rag.web;

import jakarta.servlet.http.HttpServletRequest;
import kvo.itsnn.rag.ingest.Embedder;
import kvo.itsnn.rag.llm.LlmClient;
import kvo.itsnn.rag.rerank.Reranker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Самопроверки каналов: эмбеддер, LLM, реранкер. */
@RestController
@RequestMapping("/api/rag/admin")
public class AdminSelftestController {

    private static final Logger log = LoggerFactory.getLogger(AdminSelftestController.class);
    private static final String OK = "ok";
    private static final String ERROR = "error";
    private static final String ENABLED = "enabled";
    private static final String MODEL = "model";
    private static final String ENDPOINT = "endpoint";
    private static final String MS = "ms";

    private final Embedder embedder;
    private final LlmClient llm;
    private final Reranker reranker;

    public AdminSelftestController(Embedder embedder, LlmClient llm, Reranker reranker) {
        this.embedder = embedder;
        this.llm = llm;
        this.reranker = reranker;
    }

    @GetMapping("/selftest")
    public Map<String, Object> selftest() {
        long t0 = System.currentTimeMillis();
        try {
            float[] v = embedder.embedAll(List.of("проверка связи")).get(0);
            return Map.of(OK, true, ENDPOINT, embedder.endpoint(),
                    MODEL, embedder.model(), "dims", v.length,
                    MS, System.currentTimeMillis() - t0);
        } catch (Exception e) {
            return Map.of(OK, false, ENDPOINT, embedder.endpoint(),
                    MODEL, embedder.model(),
                    ERROR, String.valueOf(e.getMessage()),
                    MS, System.currentTimeMillis() - t0);
        }
    }

    @GetMapping("/llm-selftest")
    public Map<String, Object> llmSelftest(HttpServletRequest request) {
        log.info(">>> {} {}{}", request.getMethod(), request.getRequestURI(),
                request.getQueryString() == null ? "" : "?" + request.getQueryString());
        long t0 = System.currentTimeMillis();
        try {
            String answer = llm.chat(List.of(LlmClient.Message.user("Ответь одним словом: связь.")));
            return Map.of(OK, true, "answer", answer,
                    MS, System.currentTimeMillis() - t0);
        } catch (Exception e) {
            return Map.of(OK, false, ERROR, String.valueOf(e.getMessage()),
                    MS, System.currentTimeMillis() - t0);
        }
    }

    /** Самопроверка реранкера: скоринг релевантной и нерелевантной пары. */
    @GetMapping("/rerank-selftest")
    public Map<String, Object> rerankSelftest() {
        long t0 = System.currentTimeMillis();
        if (!reranker.enabled()) {
            return Map.of(OK, false, ENABLED, false, MODEL, "-",
                    ERROR, "реранкер выключен (rag.rerank.enabled=false)", MS, 0);
        }
        try {
            List<Double> s = reranker.score("Чем отличается версия 6.0 от 4.0?", List.of(
                    "В версии 6.0 добавлен раздел «Требования охраны труда», в версии 4.0 его нет.",
                    "Погода в мае была тёплой, дожди прошли в конце месяца."));
            return Map.of(OK, true, ENABLED, true, MODEL, reranker.model(),
                    "answer", String.format(Locale.ROOT,
                            "скор: %.3f / %.3f (релевантный фрагмент должен быть заметно выше)",
                            s.get(0), s.get(1)),
                    MS, System.currentTimeMillis() - t0);
        } catch (Exception e) {
            return Map.of(OK, false, ENABLED, true, MODEL, String.valueOf(reranker.model()),
                    ERROR, String.valueOf(e.getMessage()), MS, System.currentTimeMillis() - t0);
        }
    }
}