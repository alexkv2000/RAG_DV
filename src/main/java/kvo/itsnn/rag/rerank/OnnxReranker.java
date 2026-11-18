package kvo.itsnn.rag.rerank;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import kvo.itsnn.rag.config.RagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Кросс-энкодер-реранкер: BAAI/bge-reranker-v2-m3 в ONNX, исполнение на CPU
 * через ONNX Runtime (зависимости уже в pom). Папка rag.rerank.path содержит
 * model.onnx (или onnx/model.onnx) + tokenizer.json. Пара «запрос, фрагмент»
 * токенизируется HuggingFaceTokenizer'ом (разделитель </s> ставит сам токенизатор —
 * как в sentence-transformers CrossEncoder); выход-логит переводится сигмоидой
 * в 0..1. Сигмоида монотонна: порядок тот же, но скор читается как вероятность.
 * Совместим любой ONNX-реранкер с входами input_ids/attention_mask —
 * v2-m3 (~2,2 ГБ ОЗУ) при желании заменяется на bge-reranker-base (~1,1 ГБ).
 */
public class OnnxReranker implements Reranker, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OnnxReranker.class);

    private final String modelId;
    private final int maxTokens;
    private final int batch;
    private final HuggingFaceTokenizer tokenizer;
    private final OrtEnvironment env;
    private final OrtSession session;
    private final boolean tokenTypeIds;

    public OnnxReranker(RagProperties.Rerank cfg) {
        this.modelId = cfg.model() == null || cfg.model().isBlank()
                ? "BAAI/bge-reranker-v2-m3" : cfg.model().strip();
        this.maxTokens = cfg.maxTokens() > 0 ? cfg.maxTokens() : 512;
        this.batch = Math.max(1, cfg.batch());

        Path dir = Path.of(cfg.path().strip());
        Path onnx = firstExisting(dir, "model.onnx", "onnx/model.onnx",
                "model_quantized.onnx", "onnx/model_quantized.onnx");
        if (onnx == null) {
            throw new IllegalStateException("реранкер: в " + dir + " нет model.onnx / onnx/model.onnx"
                    + " / model_quantized.onnx — скачайте ONNX-экспорт " + modelId);
        }

        HuggingFaceTokenizer t;
        try {
            // optMaxLength обязателен: как у LocalEmbedder, DJL-фабрика сама лимит не ставит
            t = HuggingFaceTokenizer.builder()
                    .optTokenizerPath(dir)
                    .optMaxLength(maxTokens)
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("реранкер: токенизатор не загрузился: " + e.getMessage(), e);
        }
        this.tokenizer = t;

        OrtEnvironment environment;
        OrtSession s;
        try {
            environment = OrtEnvironment.getEnvironment();
            try (OrtSession.SessionOptions opt = new OrtSession.SessionOptions()) {
                s = environment.createSession(onnx.toString(), opt);
            }
        } catch (Exception | UnsatisfiedLinkError e) {   // нет нативов / битный onnx — не повод валить приложение
            try { t.close(); } catch (Exception ignore) { }
            throw new IllegalStateException("реранкер: ONNX-сессия не создана (" + onnx + "): "
                    + e.getMessage(), e);
        }
        this.env = environment;
        this.session = s;

        Set<String> inputs = s.getInputNames();
        if (!inputs.contains("input_ids") || !inputs.contains("attention_mask")) {
            close();
            throw new IllegalStateException("реранкер: в " + onnx
                    + " нет входов input_ids/attention_mask — это не reranker-экспорт?");
        }
        this.tokenTypeIds = inputs.contains("token_type_ids");   // XLM-R-модели его не требуют, но граф может
        log.info("OnnxReranker: {} готов (батч {}, {} ток., входы: {})", modelId, batch, maxTokens, inputs);
    }
    /** Первый существующий файл из списка; null — ничего нет. */
    private static Path firstExisting(Path dir, String... names) {
        for (String n : names) {
            Path p = dir.resolve(n);
            if (Files.isRegularFile(p)) return p;
        }
        return null;
    }
    @Override
    public String model() {
        return modelId;
    }

    /** synchronized: сессией и токенизатором пользуются все вопросы. */
    @Override
    public synchronized List<Double> score(String query, List<String> passages) {
        if (passages.isEmpty()) return List.of();
        String q = query == null ? "" : query.strip();
        List<Double> out = new ArrayList<>(passages.size());
        for (int from = 0; from < passages.size(); from += batch) {
            scoreBatch(q, passages.subList(from, Math.min(passages.size(), from + batch)), out);
        }
        return out;
    }

    /** Батч пар → один прогон сессии → логиты → сигмоида. */
    private void scoreBatch(String query, List<String> passages, List<Double> out) {
        int n = passages.size();
        long[][] ids = new long[n][];
        long[][] mask = new long[n][];
        long[][] types = new long[n][];
        int len = 0;
        for (int i = 0; i < n; i++) {
            // Encoding в DJL не AutoCloseable: нативную память под него освобождает
            // Cleaner — тот же паттерн, что в TextEmbeddingTranslator (LocalEmbedder
            // гоняет через него тысячи чанков без явного close).
            Encoding e = tokenizer.encode(query, nz(passages.get(i)));
            ids[i] = e.getIds();
            mask[i] = e.getAttentionMask();
            types[i] = e.getTypeIds();
            len = Math.max(len, ids[i].length);
        }
        len = Math.max(len, 1);                              // пустой текст не должен ломать форму тензора
        long[] shape = {n, len};
        try (OnnxTensor tIds = OnnxTensor.createTensor(env, LongBuffer.wrap(pad(ids, len)), shape);
             OnnxTensor tMask = OnnxTensor.createTensor(env, LongBuffer.wrap(pad(mask, len)), shape);
             OnnxTensor tTypes = OnnxTensor.createTensor(env, LongBuffer.wrap(pad(types, len)), shape)) {
            Map<String, OnnxTensor> input = new LinkedHashMap<>();
            input.put("input_ids", tIds);
            input.put("attention_mask", tMask);
            if (tokenTypeIds) input.put("token_type_ids", tTypes);
            try (OrtSession.Result result = session.run(input)) {
                for (float v : logitsOf(result)) out.add(sigmoid(v));
            }
        } catch (Exception e) {
            throw new IllegalStateException("реранкер: прогон ONNX не удался: " + e.getMessage()
                    + " (у экспорта должна быть динамическая ось batch)", e);
        }
    }

    /** [n][] → [n*len] с добивкой нулями: pad-позиции закрыты attention_mask=0,
     *  поэтому конкретный pad-идентификатор не важен. */
    private static long[] pad(long[][] src, int len) {
        long[] flat = new long[src.length * len];
        for (int i = 0; i < src.length; i++) {
            System.arraycopy(src[i], 0, flat, i * len, src[i].length);
        }
        return flat;
    }

    /** Логиты выхода: форма [n] или [n][1]. */
    private static float[] logitsOf(OrtSession.Result result) throws OrtException {
        Object v = result.get(0).getValue();
        if (v instanceof float[][] m) {
            float[] out = new float[m.length];
            for (int i = 0; i < m.length; i++) out[i] = m[i].length > 0 ? m[i][0] : 0f;
            return out;
        }
        if (v instanceof float[] a) return a;
        throw new IllegalStateException("реранкер: неожиданный выход ONNX: " + v.getClass().getSimpleName());
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static double sigmoid(float x) {
        return 1.0 / (1.0 + Math.exp(-x));
    }

    @Override
    public void close() {
        try { session.close(); } catch (Exception ignore) { }
        try { tokenizer.close(); } catch (Exception ignore) { }
    }
}