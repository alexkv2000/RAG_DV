package kvo.itsnn.rag.rerank;

import kvo.itsnn.rag.config.RagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Бин реранкера. Выключен / нет пути / модель не загрузилась → заглушка NoReranker:
 * приложение стартует и отвечает как раньше (порядок кандидатов Chroma) — опциональное
 * качество не должно валить RAG. Spring сам вызовет close() у OnnxReranker при
 * остановке контекста (inferred destroy-метод @Bean).
 */
@Configuration
public class RerankConfig {

    private static final Logger log = LoggerFactory.getLogger(RerankConfig.class);

    @Bean
    public Reranker reranker(RagProperties props) {
        RagProperties.Rerank cfg = props.rerank();
        if (cfg == null || !cfg.enabled()) {
            log.info("Реранкер выключен (rag.rerank.enabled=false) — порядок кандидатов как в Chroma");
            return new NoReranker();
        }
        if (cfg.path() == null || cfg.path().isBlank()) {
            log.warn("Реранкер: enabled=true, но не задан rag.rerank.path — работаем без реранка");
            return new NoReranker();
        }
        try {
            return new OnnxReranker(cfg);
        } catch (Exception e) {
            log.error("Реранкер: модель не загрузилась ({}) — работаем без реранка", e.getMessage());
            return new NoReranker();
        }
    }
}