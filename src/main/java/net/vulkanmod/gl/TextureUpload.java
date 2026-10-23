package net.vulkanmod.gl;

import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.*;
import static org.lwjgl.opengl.GL30.GL_RG;
import static org.lwjgl.vulkan.VK10.*;

/** Decode GL unpack rows before Vulkan staging; GL receives the original source/layout. */
final class TextureUpload implements AutoCloseable {
    final ByteBuffer bytes;
    private final boolean allocated;
    private TextureUpload(ByteBuffer bytes, boolean allocated) { this.bytes = bytes; this.allocated = allocated; }
    private static int components(int format) {
        return switch (format) { case GL_RED -> 1; case GL_RG -> 2; case GL_RGB -> 3; case GL_RGBA, GL_BGRA -> 4;
            default -> throw new UnsupportedOperationException("Unsupported texture upload format 0x" + Integer.toHexString(format)); };
    }
    private static int pixelSize(int format, int type) {
        if (type == GL_UNSIGNED_INT_8_8_8_8 || type == GL_UNSIGNED_INT_8_8_8_8_REV) {
            if (components(format) != 4) throw new IllegalArgumentException("Packed RGBA upload requires four components");
            return 4;
        }
        if (type != GL_UNSIGNED_BYTE && type != GL_BYTE) throw new UnsupportedOperationException("Unsupported texture upload type 0x" + Integer.toHexString(type));
        return components(format);
    }
    static int requiredBytes(int width, int height, int format, int type, int alignment, int rowLength, int skipRows, int skipPixels) {
        long pixel = pixelSize(format, type), row = (rowLength == 0 ? width : rowLength) * pixel;
        long stride = (row + alignment - 1) / alignment * alignment;
        return Math.toIntExact(Math.addExact(Math.addExact(Math.multiplyExact(skipRows, stride), Math.multiplyExact(skipPixels, pixel)),
                Math.addExact(Math.multiplyExact(height - 1L, stride), Math.multiplyExact(width, pixel))));
    }
    static TextureUpload decode(long source, int width, int height, int format, int type, int alignment,
                                int rowLength, int skipRows, int skipPixels, int vkFormat) {
        int inputSize = pixelSize(format, type);
        int components = components(format);
        int outputSize = switch (vkFormat) { case VK_FORMAT_R8_UNORM -> 1; case VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_B8G8R8A8_UNORM -> 4;
            default -> throw new UnsupportedOperationException("Unsupported Vulkan CPU texture upload format " + vkFormat); };
        long row = (long) (rowLength == 0 ? width : rowLength) * inputSize;
        long stride = (row + alignment - 1) / alignment * alignment;
        long start = source + skipRows * stride + (long) skipPixels * inputSize;
        int outputBytes = Math.multiplyExact(Math.multiplyExact(width, height), outputSize);
        boolean channelsMatch = outputSize == inputSize && type == GL_UNSIGNED_BYTE
                && (vkFormat == VK_FORMAT_R8_UNORM && format == GL_RED
                || vkFormat == VK_FORMAT_R8G8B8A8_UNORM && format == GL_RGBA
                || vkFormat == VK_FORMAT_B8G8R8A8_UNORM && format == GL_BGRA);
        if (channelsMatch && stride == (long) width * inputSize)
            return new TextureUpload(MemoryUtil.memByteBuffer(start, outputBytes), false);
        ByteBuffer out = MemoryUtil.memAlloc(outputBytes);
        int[] channels = new int[4];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            long address = start + y * stride + (long) x * inputSize;
            channels[0] = channels[1] = channels[2] = 0; channels[3] = 255;
            if (type == GL_UNSIGNED_INT_8_8_8_8 || type == GL_UNSIGNED_INT_8_8_8_8_REV) {
                int packed = MemoryUtil.memGetInt(address);
                for (int c = 0; c < 4; c++) channels[c] = packed >>> (type == GL_UNSIGNED_INT_8_8_8_8_REV ? c * 8 : (3 - c) * 8) & 255;
            } else for (int c = 0; c < components; c++) {
                byte value = MemoryUtil.memGetByte(address + c);
                channels[c] = type == GL_BYTE ? Math.max(0, Math.round(value * 255.0f / 127)) : value & 255;
            }
            if (format == GL_BGRA) { int swap = channels[0]; channels[0] = channels[2]; channels[2] = swap; }
            if (vkFormat == VK_FORMAT_B8G8R8A8_UNORM) { int swap = channels[0]; channels[0] = channels[2]; channels[2] = swap; }
            int offset = (y * width + x) * outputSize;
            for (int c = 0; c < outputSize; c++) out.put(offset + c, (byte) channels[c]);
        }
        return new TextureUpload(out, true);
    }
    @Override public void close() { if (allocated) MemoryUtil.memFree(bytes); }
}
