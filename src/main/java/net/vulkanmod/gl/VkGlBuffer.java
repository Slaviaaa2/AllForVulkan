package net.vulkanmod.gl;

import it.unimi.dsi.fastutil.ints.Int2ReferenceOpenHashMap;
import org.lwjgl.opengl.GL32;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

// TODO: This class is only used to emulate a CPU buffer for texture copying purposes
//  any other use is not supported
public class VkGlBuffer {
    private static int ID_COUNTER = 1;
    private static final Int2ReferenceOpenHashMap<VkGlBuffer> map = new Int2ReferenceOpenHashMap<>();

    private static VkGlBuffer pixelPackBufferBound;
    private static VkGlBuffer pixelUnpackBufferBound;

    public static int glGenBuffers() {
        int id = ID_COUNTER;
        map.put(id, new VkGlBuffer(id));
        ID_COUNTER++;
        return id;
    }

    public static void glBindBuffer(int target, int buffer) {
        VkGlBuffer glBuffer = map.get(buffer);

        if (buffer > 0 && glBuffer == null)
            throw new NullPointerException("bound texture is null");

        if (glBuffer != null) {
            glBuffer.target = target;
        }

        switch (target) {
            case GL32.GL_PIXEL_PACK_BUFFER -> pixelPackBufferBound = glBuffer;
            case GL32.GL_PIXEL_UNPACK_BUFFER -> pixelUnpackBufferBound = glBuffer;
            default -> throw new IllegalStateException("Unexpected value: " + target);
        }
    }

    public static void glBufferData(int target, ByteBuffer byteBuffer, int usage) {
        VkGlBuffer buffer = requireBound(target);
        buffer.allocate(byteBuffer.remaining());
        buffer.data.put(byteBuffer.duplicate()).flip();
    }

    public static void glBufferData(int target, long size, int usage) {
        if (size < 0 || size > Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid CPU PBO size " + size);
        requireBound(target).allocate((int) size);
    }

    public static ByteBuffer glMapBuffer(int target, int access) {
        VkGlBuffer buffer = requireBound(target);
        ByteBuffer mappedBuffer = buffer.data;
        mappedBuffer.position(0);
        return mappedBuffer;
    }

    public static boolean glUnmapBuffer(int i) {
        return true;
    }

    public static void glDeleteBuffers(IntBuffer intBuffer) {
        for (int i = intBuffer.position(); i < intBuffer.limit(); i++) {
            glDeleteBuffers(intBuffer.get(i));
        }
    }

    public static void glDeleteBuffers(int id) {
        var buffer = map.remove(id);

        if (buffer != null) {
            if (pixelPackBufferBound == buffer) pixelPackBufferBound = null;
            if (pixelUnpackBufferBound == buffer) pixelUnpackBufferBound = null;
            buffer.freeData();
        }
    }

    public static VkGlBuffer getPixelUnpackBufferBound() {
        return pixelUnpackBufferBound;
    }

    public static VkGlBuffer getPixelPackBufferBound() {
        return pixelPackBufferBound;
    }

    private static VkGlBuffer requireBound(int target) {
        checkTarget(target);
        VkGlBuffer buffer = target == GL32.GL_PIXEL_PACK_BUFFER ? pixelPackBufferBound : pixelUnpackBufferBound;
        if (buffer == null) throw new IllegalStateException("No pixel buffer bound for " + target);
        return buffer;
    }

    private static void checkTarget(int target) {
        if (target != GL32.GL_PIXEL_UNPACK_BUFFER && target != GL32.GL_PIXEL_PACK_BUFFER)
            throw new IllegalArgumentException("target %d not supported".formatted(target));
    }

    int id;
    int target;

    ByteBuffer data;

    public VkGlBuffer(int id) {
        this.id = id;
    }

    private void allocate(int size) {
        if (this.data != null)
            this.freeData();

        this.data = MemoryUtil.memAlloc(size);
    }

    private ByteBuffer getData() {
        return this.data;
    }

    private void freeData() {
        MemoryUtil.memFree(data);
        data = null;
    }

}
