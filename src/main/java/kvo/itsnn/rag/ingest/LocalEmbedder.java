package kvo.itsnn.rag.ingest;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.huggingface.translator.TextEmbeddingTranslator;
import ai.djl.inference.Predictor;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import kvo.itsnn.rag.config.RagProperties;
import kvo.itsnn.rag.util.VecMath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
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

    /** Потолок модели: XLM-RoBERTa (e5-small) — max_position_embeddings = 512. */
    private static final int MAX_TOKENS = 512;

    private final ZooModel<String, float[]> model;
    private final String modelId;
    private final HuggingFaceTokenizer tokenizer;

    public LocalEmbedder(RagProperties props) throws IOException {
        this.modelId = props.embedding().localModel();
        String path = props.embedding().localPath();
        String url = (path == null || path.isBlank())
                ? "djl://ai.djl.huggingface.pytorch/" + modelId
                : "file:///" + path.replace('\\', '/').replaceAll("^/+", "");
        log.info("LocalEmbedder: загрузка модели '{}' из {}", modelId, url);

        // Токенизатор собираем всегда сами и с явным лимитом: фабрика DJL 0.39
        // не пробрасывает maxLength — лимит брался из tokenizer_config.json с WARN.
        HuggingFaceTokenizer.Builder tb = HuggingFaceTokenizer.builder().optMaxLength(MAX_TOKENS);
        if (path == null || path.isBlank()) {
            tb.optTokenizerName(modelId);            // запасная ветка: tokenizer-файлы с HF
        } else {
            tb.optTokenizerPath(Path.of(path));      // локальная папка с tokenizer.json
        }
        this.tokenizer = tb.build();

        Criteria<String, float[]> criteria = Criteria.builder()
                .setTypes(String.class, float[].class)
                .optModelUrls(url)
                .optEngine("PyTorch")
                .optTranslator(TextEmbeddingTranslator.builder(tokenizer).build())
                .build();
        try {
            this.model = criteria.loadModel();
        } catch (Exception e) {
            try {
                tokenizer.close();                   // native-ресурс не оставляем при сбое старта
            } catch (Exception ignore) {             // старт и так падает — причина вторична
            }
            throw new IllegalStateException("Не удалось загрузить embedding-модель '"
                    + modelId + "': " + e.getMessage(), e);
        }
        log.info("LocalEmbedder: модель готова (обрезка токенизатора = {} ток.)", MAX_TOKENS);
    }

    @Override public String model()    { return modelId; }
    @Override public String endpoint() { return "local://" + modelId; }

    /** Synchronized: Predictor не потокобезопасен, а поиск и индексация идут из разных потоков */
    @Override
    public synchronized List<float[]> embedAll(List<String> texts) {
        if (texts.isEmpty()) return List.of();
        try (Predictor<String, float[]> p = model.newPredictor()) {
            List<float[]> raw = p.batchPredict(texts);
            List<float[]> out = new ArrayList<>(raw.size());
            // вектор только что создан моделью и никому не принадлежит — мутация безопасна, клонировать незачем
            for (float[] v : raw) out.add(VecMath.normalizeInPlace(v));   // как и в HTTP-версии
            return out;
        } catch (Exception e) {
            throw new EmbeddingServiceException("LocalEmbedder: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        model.close();        // бина создаётся только при успешном конструкторе — null-проверки не нужны
        tokenizer.close();
    }
}