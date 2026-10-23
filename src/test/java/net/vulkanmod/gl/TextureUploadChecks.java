package net.vulkanmod.gl;

import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.*;
import static org.lwjgl.vulkan.VK10.*;

/** Deterministic source-layout regressions, no window or GL/Vulkan context needed. */
public final class TextureUploadChecks {
    public static void main(String[] args) {
        ByteBuffer source = MemoryUtil.memCalloc(96);
        try {
            source.put(0, (byte) 10).put(1, (byte) 20).put(2, (byte) 30).put(3, (byte) 40);
            check(source, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, 4, 0, 0, 0, VK_FORMAT_R8G8B8A8_UNORM, 10, 20, 30, 40);
            source.put(4, (byte) 50).put(5, (byte) 60).put(6, (byte) 70);
            check(source, 1, 2, GL_RGB, GL_UNSIGNED_BYTE, 4, 0, 0, 0, VK_FORMAT_R8G8B8A8_UNORM, 10, 20, 30, 255, 50, 60, 70, 255);
            source.put(16, (byte) 9).put(17, (byte) 8).put(18, (byte) 7).put(19, (byte) 6);
            source.put(28, (byte) 5).put(29, (byte) 4).put(30, (byte) 3).put(31, (byte) 2);
            check(source, 1, 2, GL_BGRA, GL_UNSIGNED_BYTE, 4, 3, 1, 1, VK_FORMAT_R8G8B8A8_UNORM, 7, 8, 9, 6, 3, 4, 5, 2);
            check(source, 1, 2, GL_RED, GL_UNSIGNED_BYTE, 4, 0, 0, 0, VK_FORMAT_R8_UNORM, 10, 50);
            source.putInt(0, 0x11223344);
            check(source, 1, 1, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, 4, 0, 0, 0, VK_FORMAT_R8G8B8A8_UNORM, 0x22, 0x33, 0x44, 0x11);
            check(source, 1, 1, GL_RGBA, GL_UNSIGNED_INT_8_8_8_8_REV, 4, 0, 0, 0, VK_FORMAT_R8G8B8A8_UNORM, 0x44, 0x33, 0x22, 0x11);
            check(source, 1, 1, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8, 4, 0, 0, 0, VK_FORMAT_R8G8B8A8_UNORM, 0x33, 0x22, 0x11, 0x44);
            check(source, 1, 1, GL_RGBA, GL_UNSIGNED_INT_8_8_8_8, 4, 0, 0, 0, VK_FORMAT_R8G8B8A8_UNORM, 0x11, 0x22, 0x33, 0x44);
            source.put(0, (byte) 127).put(1, (byte) -128).put(2, (byte) 0).put(3, (byte) 127);
            check(source, 1, 1, GL_RGBA, GL_BYTE, 4, 0, 0, 0, VK_FORMAT_R8G8B8A8_UNORM, 255, 0, 0, 255);
            if (TextureUpload.requiredBytes(2, 2, GL_RGB, GL_UNSIGNED_BYTE, 4, 6, 1, 1) != 49)
                throw new AssertionError("Unpack source bounds did not include aligned skipped rows");
            try { TextureUpload.requiredBytes(Integer.MAX_VALUE, Integer.MAX_VALUE, GL_RGBA, GL_UNSIGNED_BYTE, 4, 0, 0, 0); throw new AssertionError("Overflow accepted"); }
            catch (ArithmeticException expected) {}
            System.out.println("AFV_TEXTURE_UPLOAD_PASS: RGBA, RGB padding, BGRA/skips, R8 alignment, packed byte order, signed normalization, bounds/overflow");
        } finally { MemoryUtil.memFree(source); }
    }
    private static void check(ByteBuffer source, int width, int height, int format, int type, int alignment,
                              int rows, int skipRows, int skipPixels, int vkFormat, int... expected) {
        try (var upload = TextureUpload.decode(MemoryUtil.memAddress(source), width, height, format, type, alignment, rows, skipRows, skipPixels, vkFormat)) {
            if (upload.bytes.remaining() != expected.length) throw new AssertionError("Unexpected packed size");
            for (int i = 0; i < expected.length; i++) if ((upload.bytes.get(i) & 255) != expected[i])
                throw new AssertionError("Decoded component " + i + ": expected " + expected[i] + " got " + (upload.bytes.get(i) & 255));
        }
    }
}
