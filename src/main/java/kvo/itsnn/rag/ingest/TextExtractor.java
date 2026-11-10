package kvo.itsnn.rag.ingest;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

@Component
public class TextExtractor {

    public String extract(ContentLoader.LoadedFile f) throws IOException {
        return switch (f.ext()) {
            case "pdf"  -> pdf(f.bytes());
            case "docx" -> docx(f.bytes());
            case "html", "htm" -> Jsoup.parse(new String(f.bytes(), StandardCharsets.UTF_8)).text();
            case "tif", "tiff", "png", "jpg", "jpeg"
                    -> throw new IllegalArgumentException("скан без текстового слоя — нужен OCR");
            case "doc"  -> throw new IllegalArgumentException("старый .doc — конвертируйте в .docx");
            default     -> plain(f.bytes());
        };
    }

    private String pdf(byte[] b) throws IOException {
        try (PDDocument doc = Loader.loadPDF(b)) { return new PDFTextStripper().getText(doc); }
    }
    private String docx(byte[] b) throws IOException {
        try (XWPFWordExtractor ex = new XWPFWordExtractor(new XWPFDocument(new ByteArrayInputStream(b)))) {
            return ex.getText();
        }
    }
    private String plain(byte[] b) {
        String s = new String(b, StandardCharsets.UTF_8);
        return s.contains("\uFFFD") ? new String(b, Charset.forName("windows-1251")) : s;
    }
}