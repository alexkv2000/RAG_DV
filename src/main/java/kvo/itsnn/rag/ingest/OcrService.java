package kvo.itsnn.rag.ingest;

import kvo.itsnn.rag.config.RagProperties;
import net.sourceforge.tess4j.ITesseract;
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

/** OCR сканов через Tesseract (tess4j). Активируется конфигом rag.ocr.enabled.
 *  Страницы, распознанные «вверх ногами»/боком (латинский шум вместо кириллицы),
 *  автоматически допробиваются с поворотами 90/180/270°. */
@Component
public class OcrService {

    private static final Logger log = LoggerFactory.getLogger(OcrService.class);
    private final RagProperties.Ocr cfg;

    public OcrService(RagProperties props) {
        this.cfg = props.ocr();
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
            for (int i = 0; i < pages; i++) {          // страница за страницей: ~15 МБ на стр. при 200 dpi
                BufferedImage img = renderer.renderImageWithDPI(i, cfg.dpi(), ImageType.RGB);
                try {
                    OcrResult r = ocrBestOrientation(newTesseract(), img);
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
            String out = ocrBestOrientation(newTesseract(), img).text();
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

    private record OcrResult(String text, boolean rotated) {}

    /** Один прогон как раньше; при признаках «повёрнутого» текста — повороты 90/180/270,
     *  берём вариант с наибольшим числом кириллических символов (перебор только для кривых страниц). */
    private OcrResult ocrBestOrientation(ITesseract t, BufferedImage img) throws TesseractException {
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

    /** Tesseract не потокобезопасен — экземпляр на вызов.
     *  OEM=1 (только LSTM): работает с tessdata_fast/best, где нет legacy-модели. */
    private ITesseract newTesseract() {
        ITesseract t = new Tesseract();
        if (cfg.dataPath() != null && !cfg.dataPath().isBlank()) {
            t.setDatapath(cfg.dataPath());
        }
        t.setLanguage(cfg.lang());
        t.setOcrEngineMode(1);
        return t;
    }
}