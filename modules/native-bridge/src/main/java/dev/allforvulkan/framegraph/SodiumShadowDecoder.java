package dev.allforvulkan.framegraph;

/** Expands one Sodium 0.6 / Iris 1.8.12 compact chunk mesh into subset vertices.
 * The first 20 bytes of each vertex match CompactChunkVertex. Extra Iris attributes stay unread.
 * Positions are camera-relative: decoded local position plus the region offset plus the section
 * translation stored in the draw id. UVs include the bias Iris applies with {@code u_TexCoordShrink}. */
public final class SodiumShadowDecoder {
    public static final int COMPACT_BYTES = 20;
    static final float POSITION_SCALE = 32f / 1048576f;
    static final float POSITION_OFFSET = -8f;
    static final int TEXTURE_MAX = 32767;
    static final float TEXTURE_COORD = 32768f;

    /** Nine floats per triangle vertex: position xyz, UV, RGBA tint. */
    public record Mesh(float[] vertices, int quads) {}

    private SodiumShadowDecoder() {}

    /** Null when the buffer cannot be a quad mesh of this stride. */
    public static Mesh decode(byte[] packed, int stride, int vertexCount, float regionX, float regionY, float regionZ,
                              float shrinkU, float shrinkV) {
        if (packed == null || stride < COMPACT_BYTES || vertexCount < 4) return null;
        long bytes = (long) vertexCount * stride;
        if (bytes > packed.length || bytes > Integer.MAX_VALUE) return null;
        int quads = vertexCount / 4;
        if (quads == 0) return null;
        float[] vertices = new float[quads * 6 * 9];
        int written = 0;
        for (int quad = 0; quad < quads; quad++) {
            int base = quad * 4;
            int[] order = {0, 1, 2, 2, 3, 0};
            for (int corner : order) {
                int offset = (base + corner) * stride;
                int hi = intAt(packed, offset);
                int lo = intAt(packed, offset + 4);
                int x = (((hi) & 1023) << 10) | (lo & 1023);
                int y = (((hi >> 10) & 1023) << 10) | ((lo >> 10) & 1023);
                int z = (((hi >> 20) & 1023) << 10) | ((lo >> 20) & 1023);
                int draw = (intAt(packed, offset + 16) >>> 24) & 0xff;
                float sectionX = ((draw >> 5) & 7) * 16f;
                float sectionY = (draw & 3) * 16f;
                float sectionZ = ((draw >> 2) & 7) * 16f;
                int texture = intAt(packed, offset + 12);
                int uEnc = texture & 0xffff;
                int vEnc = (texture >>> 16) & 0xffff;
                float u = (uEnc & TEXTURE_MAX) / TEXTURE_COORD + (((uEnc >>> 15) & 1) == 1 ? shrinkU : -shrinkU);
                float v = (vEnc & TEXTURE_MAX) / TEXTURE_COORD + (((vEnc >>> 15) & 1) == 1 ? shrinkV : -shrinkV);
                int color = offset + 8;
                vertices[written++] = x * POSITION_SCALE + POSITION_OFFSET + regionX + sectionX;
                vertices[written++] = y * POSITION_SCALE + POSITION_OFFSET + regionY + sectionY;
                vertices[written++] = z * POSITION_SCALE + POSITION_OFFSET + regionZ + sectionZ;
                vertices[written++] = u;
                vertices[written++] = v;
                vertices[written++] = (packed[color] & 0xff) / 255f;
                vertices[written++] = (packed[color + 1] & 0xff) / 255f;
                vertices[written++] = (packed[color + 2] & 0xff) / 255f;
                vertices[written++] = (packed[color + 3] & 0xff) / 255f;
            }
        }
        return new Mesh(vertices, quads);
    }

    static int quantize(float position) {
        return (int) (((position + 8f) / 32f) * 1048576f) & 1048575;
    }

    /** The 20-byte prefix Sodium writes for one vertex, for tests. */
    public static byte[] packVertex(float x, float y, float z, int red, int green, int blue, int alpha,
                                    float u, float v, boolean uPositiveBias, boolean vPositiveBias, int drawId) {
        byte[] packed = new byte[COMPACT_BYTES];
        putPosition(packed, 0, quantize(x), quantize(y), quantize(z));
        packed[8] = (byte) red;
        packed[9] = (byte) green;
        packed[10] = (byte) blue;
        packed[11] = (byte) alpha;
        int uEnc = Math.round(u * TEXTURE_COORD) & TEXTURE_MAX;
        int vEnc = Math.round(v * TEXTURE_COORD) & TEXTURE_MAX;
        if (uPositiveBias) uEnc |= 1 << 15;
        if (vPositiveBias) vEnc |= 1 << 15;
        putInt(packed, 12, uEnc | (vEnc << 16));
        putInt(packed, 16, (drawId & 0xff) << 24);
        return packed;
    }

    private static void putPosition(byte[] packed, int offset, int x, int y, int z) {
        int hi = (x >>> 10) & 1023 | (((y >>> 10) & 1023) << 10) | (((z >>> 10) & 1023) << 20);
        int lo = (x & 1023) | ((y & 1023) << 10) | ((z & 1023) << 20);
        putInt(packed, offset, hi);
        putInt(packed, offset + 4, lo);
    }

    private static void putInt(byte[] packed, int offset, int value) {
        packed[offset] = (byte) value;
        packed[offset + 1] = (byte) (value >>> 8);
        packed[offset + 2] = (byte) (value >>> 16);
        packed[offset + 3] = (byte) (value >>> 24);
    }

    private static int intAt(byte[] packed, int offset) {
        return (packed[offset] & 0xff)
                | ((packed[offset + 1] & 0xff) << 8)
                | ((packed[offset + 2] & 0xff) << 16)
                | ((packed[offset + 3] & 0xff) << 24);
    }
}
