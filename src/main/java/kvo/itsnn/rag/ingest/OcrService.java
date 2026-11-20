package kvo.itsnn.rag.ingest;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import kvo.itsnn.rag.config.RagProperties;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
/** OCR сканов через Tesseract (tess4j). Активируется конфигом rag.ocr.enabled.
 *  Страницы, распознанные «вверх ногами»/боком (латинский шум вместо кириллицы),
 *  автоматически допробиваются с поворотами 90/180/270°.
 *
 *  Пул инстансов: Tesseract не потокобезопасен, но его инициализация (загрузка
 *  traineddata rus+eng) стоит секунды — создание инстанса на каждую страницу PDF
 *  было основной тратой времени OCR. Теперь инстансы переиспользуются: один —
 *  на весь документ (recognizePdf), по числу параллельных вызовов — в пуле
 *  (планировщик ingest + HTTP-вызовы extract/direct-chat). Пул наполняется
 *  лениво: при выключенном OCR накладных расходов нет. */
@Component
public class OcrService {

    private static final Logger log = LoggerFactory.getLogger(OcrService.class);

    /** Инстансов в пуле. 2 покрывают типичную нагрузку: один держит ingest-поток
     *  планировщика, второй — HTTP-запрос (/api/rag/extract, /api/rag/direct-chat/files).
     *  Больше не нужно: OCR на CPU и так съедает все ядра. */
    private static final int POOL_SIZE = 2;
    private final RagProperties.Ocr cfg;

    /** Ожидание права на инстанс, когда пул пуст и лимит исчерпан (HTTP-поток не висит вечно). */
    private static final long ACQUIRE_WAIT_MS = 60_000;
    /** Жёсткий потолок ЖИВЫХ инстансов Tesseract: пул + выданные в работу. */
    private final Semaphore permits = new Semaphore(POOL_SIZE);

    /** Свободные инстансы; capacity = POOL_SIZE, лишние при release закрываются. */
    private final BlockingQueue<PooledTesseract> pool = new ArrayBlockingQueue<>(POOL_SIZE);

    public OcrService(RagProperties props) {
        this.cfg = props.ocr();
    }
    @PostConstruct
    void warmUp() { //при старте создаем первый инстанс (прогрев системы)
        if (!cfg.enabled()) return;
        PooledTesseract t = acquire();
        try {
            if (!pool.offer(t)) {       // первый инстанс живёт с самого старта
                throw new IllegalStateException("OCR: пул переполнен при прогреве"); }
        } catch (Exception e) {
            release(t);
            throw e;
        }
    }
    /** Взять инстанс: сначала пул; пусто и лимит живых исчерпан — ждать до ACQUIRE_WAIT_MS,
     *  дальше — 503 «повторите позже» (ingest вернёт документ в очередь без штрафа). */
    private PooledTesseract acquire() {
        PooledTesseract t = pool.poll();
        if (t != null) return t;                  // инстанс из пула — пермит уже «принадлежит» ему
        try {
            if (!permits.tryAcquire(ACQUIRE_WAIT_MS, TimeUnit.MILLISECONDS)) {
                throw new EmbeddingServiceException("OCR: все инстансы заняты — повторите позже", null);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EmbeddingServiceException("OCR: прервано ожидание Tesseract инстанса", e);
        }
        try {
            return newTesseract();                // право на новый инстанс получено
        } catch (Exception e) {                   // создание не удалось — пермит возвращается в семафор
            permits.release();
            throw new EmbeddingServiceException("OCR: не удалось создать инстанс Tesseract", e);
        }
    }
    /** Вернуть инстанс в пул; пул полон — закрыть и вернуть пермит. */
    private void release(PooledTesseract t) {
        if (pool.offer(t)) return;               // живёт в пуле, пермит остаётся «занят» инстансом
        disposeQuietly(t);                       // защитная ветка: лишний закрываем
        permits.release();
    }

    public String recognizePdf(byte[] pdf) {
        if (!cfg.enabled()) {
            throw new IllegalArgumentException("скан без текстового слоя — OCR выключен (rag.ocr.enabled)");
        }
        long t0 = System.currentTimeMillis();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDFRenderer renderer = new PDFRenderer(doc);
            int total = doc.getNumberOfPages();
            int pages = Math.min(total, cfg.maxPages());
            StringBuilder sb = new StringBuilder();
            int rotated = 0;
            PooledTesseract t = acquire();              // ОДИН инстанс на весь документ, не на страницу
            try {
                for (int i = 0; i < pages; i++) {       // страница за страницей: ~15 МБ на стр. при 200 dpi
                    BufferedImage img = renderer.renderImageWithDPI(i, cfg.dpi(), ImageType.RGB);
                    try {
                        OcrResult r = ocrBestOrientation(t, img);
                        if (r.rotated()) {
                            rotated++;
                        }
                        if (!r.text().isBlank()) {
                            sb.append(r.text()).append("\n\n");
                        }
                    } finally {
                        img.flush();
                    }
                }
            } finally {
                release(t);
            }
            if (total > pages) {
                sb.append("[OCR: обработаны первые ").append(pages)
                        .append(" страниц из ").append(total).append("]\n\n");
            }
            String out = sb.toString().strip();
            if (out.isBlank()) {
                throw new IllegalArgumentException("OCR не дал текста (пустые страницы)");
            }
            log.info("OCR: {} стр. ({} повернуто) за {} мс",
                    pages, rotated, System.currentTimeMillis() - t0);
            return out;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("OCR: " + e.getMessage(), e);
        }
    }

    public String recognizeImage(byte[] image) {
        if (!cfg.enabled()) {
            throw new IllegalArgumentException("скан-картинка — OCR выключен (rag.ocr.enabled)");
        }
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(image));
            if (img == null) {
                throw new IllegalArgumentException("неизвестный формат изображения");
            }
            PooledTesseract t = acquire();
            String out;
            try {
                out = ocrBestOrientation(t, img).text();
            } finally {
                release(t);
            }
            if (out.isBlank()) {
                throw new IllegalArgumentException("OCR не дал текста");
            }
            return out;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("OCR: " + e.getMessage(), e);
        }
    }

    /** Освобождение нативной памяти инстансов пула при остановке приложения. */
    @PreDestroy
    public void close() {
        pool.forEach(OcrService::disposeQuietly);
        pool.clear();
    }

    private static void disposeQuietly(PooledTesseract t) {
        try {
            t.dispose();
        } catch (Exception e) {                     // остановка не должна падать на нативном close
            log.debug("Tesseract dispose: {}", e.getMessage());
        }
    }

    /**
     * Обёртка над Tesseract с публичным dispose(): в библиотеке метод объявлен
     * protected (внутренний хук очистки, наружу не отдаётся), а пулу нужно
     * освобождать нативную память сверхлимитных инстансов и при остановке.
     * Переопределение с расширением видимости protected → public разрешено JLS;
     * функциональность — та же (super.dispose()). Инстансы этого типа создаёт
     * только сам OcrService, поэтому кастов и утечек чужих типов нет.
     */
    private static final class PooledTesseract extends Tesseract {
        @Override
        public void dispose() {
            super.dispose();
        }
    }

    private record OcrResult(String text, boolean rotated) {}

    /** Один прогон как раньше; при признаках «повёрнутого» текста — повороты 90/180/270,
     *  берём вариант с наибольшим числом кириллических символов (перебор только для кривых страниц). */
    private OcrResult ocrBestOrientation(Tesseract t, BufferedImage img) throws TesseractException {
        String text = t.doOCR(img).strip();
        if (!needsRotate(text)) {
            return new OcrResult(text, false);
        }

        String best = text;
        int bestScore = cyrCount(text);
        for (int q = 1; q <= 3; q++) {
            BufferedImage turned = rotate(img, q);
            String cand;
            try {
                cand = t.doOCR(turned).strip();
            } catch (TesseractException e) {           // не смогли — оставляем исходный
                log.debug("Поворот {}° не распознан: {}", q * 90, e.getMessage());
                break;
            } finally {
                turned.flush();
            }
            int score = cyrCount(cand);
            if (score > bestScore * 1.5) {             // 1.5x: смена ориентации должна быть уверенной
                bestScore = score;
                best = cand;
            }
        }
        return new OcrResult(best, true);
    }

    /** Эвристика: кириллицы почти нет, но латиницы много → страница, скорее всего, повёрнута. */
    private static boolean needsRotate(String t) {
        return t != null && cyrCount(t) < 20 && latinCount(t) > 60;
    }

    private static int cyrCount(String t) {
        if (t == null) {
            return 0;
        }
        int n = 0;
        for (char c : t.toCharArray()) {
            if (c >= 0x0400 && c <= 0x04FF) {
                n++;
            }
        }
        return n;
    }

    private static int latinCount(String t) {
        if (t == null) {
            return 0;
        }
        int n = 0;
        for (char c : t.toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                n++;
            }
        }
        return n;
    }

    /** Поворот на quarterTurns * 90° по часовой. */
    private static BufferedImage rotate(BufferedImage img, int quarterTurns) {
        int q = ((quarterTurns % 4) + 4) % 4;
        if (q == 0) {
            return img;
        }
        int w = img.getWidth();
        int h = img.getHeight();
        BufferedImage out = new BufferedImage(q % 2 == 1 ? h : w, q % 2 == 1 ? w : h,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.translate((out.getWidth() - w) / 2.0, (out.getHeight() - h) / 2.0);
            g.transform(AffineTransform.getQuadrantRotateInstance(q, w / 2.0, h / 2.0));
            g.drawRenderedImage(img, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    /** Новый инстанс Tesseract с настройками из rag.ocr.*.
     *  OEM=1 (только LSTM): работает с tessdata_fast/best, где нет legacy-модели. */
    private PooledTesseract newTesseract() {
        PooledTesseract t = new PooledTesseract();
        if (cfg.dataPath() != null && !cfg.dataPath().isBlank()) {
            t.setDatapath(cfg.dataPath());
        }
        t.setLanguage(cfg.lang());
        t.setOcrEngineMode(1);
        return t;
    }
}