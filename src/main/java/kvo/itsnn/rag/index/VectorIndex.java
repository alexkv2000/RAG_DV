package kvo.itsnn.rag.index;

import java.util.Collection;
import java.util.List;

public interface VectorIndex {
    void addAll(List<Long> chunkIds, List<float[]> vectors);
    void removeChunks(Collection<Long> ids);
    int size();
    List<Hit> search(float[] query, int topK);
    void reload();

    record Hit(long chunkId, double score) {}
}