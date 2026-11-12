package kvo.itsnn.rag.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class Sha256 {
    private Sha256() {
    }
    public static byte[] of(String s) {
        try { return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}