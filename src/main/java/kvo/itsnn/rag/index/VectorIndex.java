package kvo.itsnn.rag.index;

import java.util.Collection;
import java.util.List;
import java.util.Map;

public interface VectorIndex {
    /** Метаданные чанка в Chroma (document_guid, classifier). */
    void addAll(List<Long> chunkIds, List<float[]> vectors, List<Map<String, String>> metadatas);
    void removeChunks(Collection<Long> ids);
    int size();
    /** classifier == null → поиск по всему индексу. */
    List<Hit> search(float[] query, int topK, String classifier);
    default List<Hit> search(float[] query, int topK) { return search(query, topK, null); }
    void reload();

    record Hit(long chunkId, double score) {}
}