package kvo.itsnn.rag.ingest;

import ai.djl.huggingface.translator.TextEmbeddingTranslatorFactory;
import ai.djl.inference.Predictor;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import kvo.itsnn.rag.config.RagProperties;
import kvo.itsnn.rag.util.VecMath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Эмбеддинги считаются локально в JVM (DJL, PyTorch engine, CPU).
 * Первый запуск скачивает модель с huggingface.co в ~/.djl.ai (~470 МБ).
 * Для offline-работы скачайте репозиторий модели заранее и укажите rag.embedding.local-path.
 */
@Component
public class LocalEmbedder implements Embedder, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LocalEmbedder.class);

    private final ZooModel<String, float[]> model;
    private final String modelId;

    public LocalEmbedder(RagProperties props) {
        this.modelId = props.embedding().localModel();
        String path = props.embedding().localPath();
        String url = (path == null || path.isBlank())
                ? "djl://ai.djl.huggingface.pytorch/" + modelId
                : "file:///" + path.replace('\\', '/').replaceAll("^/+", "");
        log.info("LocalEmbedder: загрузка модели '{}' из {}", modelId, url);
        Criteria<String, float[]> criteria = Criteria.builder()
                .setTypes(String.class, float[].class)
                .optModelUrls(url)
                .optEngine("PyTorch")
                .optTranslatorFactory(new TextEmbeddingTranslatorFactory())
                .build();
        try {
            this.model = criteria.loadModel();
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось загрузить embedding-модель '"
                    + modelId + "': " + e.getMessage(), e);
        }
        log.info("LocalEmbedder: модель готова");
    }

    @Override public String model()    { return modelId; }
    @Override public String endpoint() { return "local://" + modelId; }

    /** synchronized: Predictor не потокобезопасен, а поиск и индексация идут из разных потоков */
    @Override
    public synchronized List<float[]> embedAll(List<String> texts) {
        if (texts.isEmpty()) return List.of();
        try (Predictor<String, float[]> p = model.newPredictor()) {
            List<float[]> raw = p.batchPredict(texts);
            List<float[]> out = new ArrayList<>(raw.size());
            for (float[] v : raw) out.add(VecMath.normalize(v));   // как и в HTTP-версии
            return out;
        } catch (Exception e) {
            throw new EmbeddingServiceException("LocalEmbedder: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() throws Exception { model.close(); }   // Spring вызовет при остановке
}