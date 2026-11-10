package kvo.itsnn.rag.ingest;

/** Системный сбой (сервис недоступен/неверно настроен) — не начисляет ретраи документам. */
public class EmbeddingServiceException extends RuntimeException {
    public EmbeddingServiceException(String message, Throwable cause) { super(message, cause); }
}