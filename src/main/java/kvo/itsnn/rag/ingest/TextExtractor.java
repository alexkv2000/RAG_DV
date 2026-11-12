package kvo.itsnn.rag.ingest;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hsmf.MAPIMessage;
import org.apache.poi.hsmf.exceptions.ChunkNotFoundException;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import org.apache.poi.hssf.extractor.ExcelExtractor;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.xssf.extractor.XSSFExcelExtractor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import javax.swing.text.rtf.RTFEditorKit;

@Component
public class TextExtractor {
    /**
     * Меньше этого — текстового слоя нет (или только штамп-надпись на скане).
     */
    private static final int MIN_TEXT_CHARS = 50;
    private final OcrService ocr;

    public TextExtractor(OcrService ocr) {
        this.ocr = ocr;
    }

    public String extract(ContentLoader.LoadedFile f) throws IOException {
        return switch (f.ext()) {
            case "pdf" -> pdf(f.bytes());
            case "docx" -> docx(f.bytes());
            case "doc" -> doc(f.bytes());
            case "html", "htm" -> Jsoup.parse(new String(f.bytes(), StandardCharsets.UTF_8)).text();
            case "tif", "tiff", "png", "jpg", "jpeg" -> ocr.recognizeImage(f.bytes());
            case "xlsx", "xlsm" -> xlsx(f.bytes());
            case "xls" -> xls(f.bytes());
            case "msg" -> msg(f.bytes());
            case "rtf" -> rtf(f.bytes());
            default -> plain(f.bytes());
        };
    }

    private String xlsx(byte[] b) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(b));
             XSSFExcelExtractor ex = new XSSFExcelExtractor(wb)) {
            String t = ex.getText();
            if (t.isBlank()) {
                throw new IllegalArgumentException("пустая таблица");
            }
            return t;
        }
    }

    private String xls(byte[] b) throws IOException {
        try (HSSFWorkbook wb = new HSSFWorkbook(new ByteArrayInputStream(b));
             ExcelExtractor ex = new ExcelExtractor(wb)) {
            String t = ex.getText();
            if (t.isBlank()) {
                throw new IllegalArgumentException("пустая таблица");
            }
            return t;
        }
    }

    private String pdf(byte[] b) throws IOException {
        try (PDDocument doc = Loader.loadPDF(b)) {
            String t = new PDFTextStripper().getText(doc);
            if (t != null && t.strip().length() >= MIN_TEXT_CHARS) {
                return t;
            }
        }
        return ocr.recognizePdf(b);   // скан без слоя (или почти пустой) — через OCR
    }

    private String doc(byte[] b) throws IOException {
        try (HWPFDocument d = new HWPFDocument(new ByteArrayInputStream(b));
             WordExtractor ex = new WordExtractor(d)) {
            String t = ex.getText();
            if (t == null || t.isBlank()) {
                throw new IllegalArgumentException("пустой .doc");
            }
            return t;
        }
    }

    private String rtf(byte[] b) throws IOException {
        try {
            RTFEditorKit kit = new RTFEditorKit();
            javax.swing.text.Document d = kit.createDefaultDocument();
            kit.read(new ByteArrayInputStream(b), d, 0);
            String t = d.getText(0, d.getLength());
            if (t.isBlank()) {
                throw new IllegalArgumentException("пустой rtf");
            }
            return t;
        } catch (javax.swing.text.BadLocationException e) {
            throw new IllegalArgumentException("битый rtf: " + e.getMessage());
        }
    }

    private String msg(byte[] b) throws IOException {
        try (MAPIMessage m = new MAPIMessage(new ByteArrayInputStream(b))) {
            String body = """
                    От: %s
                    Кому: %s
                    Тема: %s
                    %s
                    """.formatted(field(m::getDisplayFrom), field(m::getDisplayTo),
                    field(m::getSubject), field(m::getTextBody));
            if (body.isBlank()) {
                throw new IllegalArgumentException("пустое письмо");
            }
            return body;
        }
    }

    @FunctionalInterface
    private interface Field {
        String get() throws IOException, ChunkNotFoundException;
    }

    private static String field(Field g) {
        try {
            String v = g.get();
            return v == null ? "" : v;
        } catch (IOException | ChunkNotFoundException e) {
            return "";
        }
    }

    private String docx(byte[] b) throws IOException {
        try (XWPFWordExtractor ex = new XWPFWordExtractor(new XWPFDocument(new ByteArrayInputStream(b)))) {
            return ex.getText();
        }
    }

    private String plain(byte[] b) {
        String s = decode(b);
        if (isBinaryJunk(s)) {
            throw new IllegalArgumentException("бинарный файл неизвестного формата");
        }
        return s;
    }

    /**
     * BOM важнее эвристик: UTF-16 из Directum раньше читался как UTF-8 и ловился как бинарник.
     */
    private static String decode(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF) {
            return new String(b, 3, b.length - 3, StandardCharsets.UTF_8);
        }
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE) {
            return new String(b, 2, b.length - 2, StandardCharsets.UTF_16LE);
        }
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF) {
            return new String(b, 2, b.length - 2, StandardCharsets.UTF_16BE);
        }
        String s = new String(b, StandardCharsets.UTF_8);
        if (s.contains("\uFFFD")) {
            s = new String(b, Charset.forName("windows-1251"));
        }
        return s;
    }

    /**
     * Эвристика: настоящий текст не содержит заметной доли control-символов.
     * Ловит .msg, .ole и прочие бинарники, попавшие в ветку default.
     */
    private static boolean isBinaryJunk(String s) {
        int sample = Math.min(s.length(), 10_000);
        if (sample == 0) {
            return false;
        }
        int junk = 0;
        for (int i = 0; i < sample; i++) {
            char c = s.charAt(i);
            if ((c < 32 && c != '\n' && c != '\r' && c != '\t') || c == '\uFFFD') {
                junk++;
            }
        }
        return junk > sample / 20;   // >5% мусора — это не текст
    }
}