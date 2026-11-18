package kvo.itsnn.rag.rerank;

import java.util.List;

/**
 * Кросс-энкодер-реранкер: точная оценка релевантности пары «запрос, фрагмент»
 * (би-энкодер e5 даёт только косинус векторов). Вставляется между грубым отбором
 * Chroma (candidateChunks) и сборкой контекста (contextChunks).
 */
public interface Reranker {

    /** Имя модели для логов и самопроверки. */
    String model();

    /** false — реранкер не настроен; score() не вызывается. */
    default boolean enabled() { return true; }

    /** Скоры пар (query, passages[i]): выше = релевантнее;
     *  размер и порядок результата совпадают с passages. */
    List<Double> score(String query, List<String> passages);
}