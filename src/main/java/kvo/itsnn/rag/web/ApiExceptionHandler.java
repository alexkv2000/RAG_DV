package kvo.itsnn.rag.web;

import kvo.itsnn.rag.ingest.EmbeddingServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;

import java.util.Map;

/**
 * Единая точка логирования сбоев веб-слоя. Нижние слои (LlmClient, Embedder,
 * ChromaVectorIndex) только бросают исключения с самодостаточными сообщениями —
 * логируем и переводим в HTTP-статусы здесь, ровно один раз.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final String ERROR = "error";

    /** Сбой внешнего сервиса на нашей стороне (LLM вернул 4xx/5xx, пустой ответ). */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> upstream(IllegalStateException e) {
        log.warn("Сбой внешнего сервиса: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of(ERROR, String.valueOf(e.getMessage())));
    }

    /** Эмбеддинг-сервис недоступен (LocalEmbedder/Chroma) — повторите позже. */
    @ExceptionHandler(EmbeddingServiceException.class)
    public ResponseEntity<Map<String, String>> embedding(EmbeddingServiceException e) {
        log.warn("Embedding сбой: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of(ERROR, String.valueOf(e.getMessage())));
    }

    /** Таймаут/обрыв связи с внешним сервисом (RestClientResponseException перехвачен
     *  в LlmClient, сюда доходит connect/read timeout и обрыв соединения). */
    @ExceptionHandler(RestClientException.class)
    public ResponseEntity<Map<String, String>> network(RestClientException e) {
        log.warn("Внешний сервис недоступен: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                .body(Map.of(ERROR, "внешний сервис недоступен: " + e.getMessage()));
    }

    /** Ошибка в самом запросе: пустой query/message и т.п. — было 500, стало 400. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest()
                .body(Map.of(ERROR, String.valueOf(e.getMessage())));
    }

    /** Тело запроса не разбирается как JSON — тоже ошибка клиента, в нашем формате. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> unreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest()
                .body(Map.of(ERROR, "некорректное тело запроса: ожидается JSON {\"query\": \"...\"}"));
    }
}