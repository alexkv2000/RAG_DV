package kvo.itsnn.rag.index;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

public interface VectorIndex {

    /** Метаданные чанка в Chroma (document_guid, classifier, org_le). */
    void addAll(List<Long> chunkIds, List<float[]> vectors, List<Map<String, String>> metadatas);

    void removeChunks(Collection<Long> ids);

    int size();

    /**
     * Поиск с фильтрацией по классификатору и юридическим лицам.
     *
     * @param classifier фильтр по классификатору; null → без фильтра
     * @param orgLe      множество доступных пользователю ЮЛ;
     *                   null → фильтр выключен (superAdmin, eval, внутренние вызовы);
     *                   пустое множество → только общие документы (org_le="");
     *                   непустое → свои ЮЛ + общие (org_le IN (...) OR org_le="").
     */
    List<Hit> search(float[] query, int topK, String classifier, Set<String> orgLe);

    default List<Hit> search(float[] query, int topK, String classifier) {
        return search(query, topK, classifier, null);
    }

    default List<Hit> search(float[] query, int topK) {
        return search(query, topK, null, null);
    }

    void reload();

    record Hit(long chunkId, double score) {}
}