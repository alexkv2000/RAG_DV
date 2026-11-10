package kvo.itsnn.rag.refsync;

import java.util.UUID;

public record DocumentRef(UUID guid, String classifier, int retryCount) {}