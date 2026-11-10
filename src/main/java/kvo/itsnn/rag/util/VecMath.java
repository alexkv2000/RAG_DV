package kvo.itsnn.rag.util;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class VecMath {
    public static float[] normalize(float[] v) {
        double n = 0; for (float x : v) n += (double) x * x;
        n = Math.sqrt(n); if (n == 0) return v;
        for (int i = 0; i < v.length; i++) v[i] = (float) (v[i] / n);
        return v;
    }
    public static byte[] toLittleEndian(float[] v) {
        ByteBuffer bb = ByteBuffer.allocate(v.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        bb.asFloatBuffer().put(v); return bb.array();
    }
    public static float[] fromLittleEndian(byte[] b) {
        float[] f = new float[b.length / 4];
        ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(f);
        return f;
    }
    public static float dot(float[] a, float[] b) {
        float s = 0; for (int i = 0; i < a.length; i++) s += a[i] * b[i]; return s;
    }
}