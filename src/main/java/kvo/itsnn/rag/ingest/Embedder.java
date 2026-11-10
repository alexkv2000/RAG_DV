package kvo.itsnn.rag.ingest;
import java.util.List;
public interface Embedder {
    String model();
    String endpoint();
    List<float[]> embedAll(List<String> texts);
}