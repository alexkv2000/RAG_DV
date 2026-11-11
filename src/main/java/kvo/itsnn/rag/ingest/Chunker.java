package kvo.itsnn.rag.ingest;

import kvo.itsnn.rag.config.RagProperties;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class Chunker {

    private final RagProperties.Chunk cfg;
    public Chunker(RagProperties p) { this.cfg = p.chunk(); }

    public List<String> split(String text) {
        List<String> chunks = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        for (String p : text.replace("\r\n", "\n").replace('\r', '\n').split("\n{2,}")) {
            for (String piece : cutLong(p)) {
                if (buf.length() + piece.length() > cfg.targetChars() && buf.length() > 0) {
                    chunks.add(buf.toString().strip());
                    buf = new StringBuilder(overlapTail(buf.toString()));
                }
                if (!piece.isBlank()) buf.append(piece.strip()).append("\n\n");
            }
        }
        if (!buf.toString().isBlank()) chunks.add(buf.toString().strip());
        return chunks;
    }

    /** Длинный абзац (таблица xlsx, сплошной текст без пустых строк) режем на
     *  НЕпересекающиеся куски по границам слов. Перекрытие между чанками даёт
     *  только overlapTail — одно, на границе чанков, как и задумано. */
    private List<String> cutLong(String p) {
        if (p.length() <= cfg.targetChars()) return List.of(p);
        List<String> out = new ArrayList<>();
        int start = 0;
        while (start < p.length()) {
            int end = Math.min(p.length(), start + cfg.targetChars());
            if (end < p.length()) {
                int cut = Math.max(p.lastIndexOf(' ', end), p.lastIndexOf('\n', end));
                if (cut > start + cfg.targetChars() / 2) end = cut;
            }
            out.add(p.substring(start, end));
            start = end;                       // без внутреннего перекрытия
        }
        return out;
    }

    /** Хвост-перекрытие для следующего чанка; начало сдвигаем к границе строки,
     *  чтобы чанк не начинался с обрубка слова. */
    private String overlapTail(String s) {
        int from = Math.max(0, s.length() - cfg.overlapChars());
        int nl = s.indexOf('\n', from);
        if (nl >= 0 && nl + 1 < s.length()) return s.substring(nl + 1);
        int sp = s.indexOf(' ', from);
        if (sp >= 0 && sp + 1 < s.length()) return s.substring(sp + 1);
        return s.substring(from);
    }
}