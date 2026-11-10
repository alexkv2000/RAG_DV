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
        for (String p : text.replace("\r\n", "\n").split("\n{2,}")) {
            if (buf.length() + p.length() > cfg.targetChars() && buf.length() > 0) {
                chunks.add(buf.toString().strip());
                buf = new StringBuilder(buf.substring(Math.max(0, buf.length() - cfg.overlapChars())));
            }
            if (!p.isBlank()) buf.append(p.strip()).append("\n\n");
        }
        if (!buf.toString().isBlank()) chunks.add(buf.toString().strip());
        return chunks;
    }
}