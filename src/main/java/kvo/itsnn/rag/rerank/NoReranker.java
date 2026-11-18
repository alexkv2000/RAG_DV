package kvo.itsnn.rag.rerank;

import java.util.List;

/** Заглушка: реранкер не настроен; AskService по enabled() не вызывает score(). */
final class NoReranker implements Reranker {

    @Override
    public String model() { return "off"; }

    @Override
    public boolean enabled() { return false; }

    @Override
    public List<Double> score(String query, List<String> passages) {
        throw new UnsupportedOperationException("реранкер выключен (rag.rerank.enabled=false)");
    }
}