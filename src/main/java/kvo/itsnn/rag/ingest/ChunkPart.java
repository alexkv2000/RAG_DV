package kvo.itsnn.rag.ingest;

import java.util.UUID;

public record ChunkPart(String text, UUID fileId, String fileName) {}