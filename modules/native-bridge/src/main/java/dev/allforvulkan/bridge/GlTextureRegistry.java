package dev.allforvulkan.bridge;

import java.util.HashSet;
import java.util.Set;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL21.*;
import static org.lwjgl.opengl.GL30.glGenerateMipmap;

/** Real GL names for CPU-uploaded Minecraft textures. Render-target contents are
 * deliberately not mirrored here: those require the external-image pass boundary.
 * Pixel bytes are uploaded from their original source, never downloaded from Vulkan. */
public final class GlTextureRegistry implements AutoCloseable {
    private static GlTextureRegistry active;
    private final Thread owner = Thread.currentThread();
    private final Set<Integer> names = new HashSet<>();
    private long allocations, uploads;
    private boolean closed;

    public GlTextureRegistry() {
        if (active != null) throw new IllegalStateException("A GL texture registry is already active");
        active = this;
    }
    public static GlTextureRegistry active() { return active; }
    private void assertOwner() {
        if (closed || Thread.currentThread() != owner) throw new IllegalStateException("GL texture registry used outside its render thread/lifetime");
    }
    public int create() {
        assertOwner();
        int name = glGenTextures();
        int previous = glGetInteger(GL_TEXTURE_BINDING_2D);
        glBindTexture(GL_TEXTURE_2D, name);
        glBindTexture(GL_TEXTURE_2D, previous);
        names.add(name);
        return name;
    }
    public boolean contains(int name) { assertOwner(); return names.contains(name); }
    public long allocations() { return allocations; }
    public long uploads() { return uploads; }
    public void delete(int name) {
        assertOwner();
        if (names.remove(name)) glDeleteTextures(name);
    }
    public void allocate(int name, int level, int internalFormat, int width, int height, int border, int format, int type) {
        assertName(name);
        int binding = glGetInteger(GL_TEXTURE_BINDING_2D);
        int unpack = glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING);
        try {
            glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
            glBindTexture(GL_TEXTURE_2D, name);
            glTexImage2D(GL_TEXTURE_2D, level, internalFormat, width, height, border, format, type, 0L);
            check("allocate Minecraft GL texture " + name);
            allocations++;
        } finally {
            glBindTexture(GL_TEXTURE_2D, binding);
            glBindBuffer(GL_PIXEL_UNPACK_BUFFER, unpack);
        }
    }
    public void upload(int name, int level, int x, int y, int width, int height, int format, int type,
                       long pixels, int alignment, int rowLength, int skipRows, int skipPixels) {
        assertName(name);
        int binding = glGetInteger(GL_TEXTURE_BINDING_2D);
        int unpack = glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING);
        int oldAlignment = glGetInteger(GL_UNPACK_ALIGNMENT), oldRowLength = glGetInteger(GL_UNPACK_ROW_LENGTH);
        int oldSkipRows = glGetInteger(GL_UNPACK_SKIP_ROWS), oldSkipPixels = glGetInteger(GL_UNPACK_SKIP_PIXELS);
        try {
            glBindTexture(GL_TEXTURE_2D, name);
            glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0); // VkGlBuffer has already resolved CPU-backed PBO offsets.
            glPixelStorei(GL_UNPACK_ALIGNMENT, alignment);
            glPixelStorei(GL_UNPACK_ROW_LENGTH, rowLength);
            glPixelStorei(GL_UNPACK_SKIP_ROWS, skipRows);
            glPixelStorei(GL_UNPACK_SKIP_PIXELS, skipPixels);
            glTexSubImage2D(GL_TEXTURE_2D, level, x, y, width, height, format, type, pixels);
            check("upload Minecraft GL texture " + name);
            uploads++;
        } finally {
            glPixelStorei(GL_UNPACK_ALIGNMENT, oldAlignment);
            glPixelStorei(GL_UNPACK_ROW_LENGTH, oldRowLength);
            glPixelStorei(GL_UNPACK_SKIP_ROWS, oldSkipRows);
            glPixelStorei(GL_UNPACK_SKIP_PIXELS, oldSkipPixels);
            glBindBuffer(GL_PIXEL_UNPACK_BUFFER, unpack);
            glBindTexture(GL_TEXTURE_2D, binding);
        }
    }
    public void parameter(int name, int parameter, int value) {
        assertName(name);
        int binding = glGetInteger(GL_TEXTURE_BINDING_2D);
        try {
            glBindTexture(GL_TEXTURE_2D, name);
            glTexParameteri(GL_TEXTURE_2D, parameter, value);
            check("Minecraft GL texture parameter");
        } finally { glBindTexture(GL_TEXTURE_2D, binding); }
    }
    public void parameter(int name, int parameter, float value) {
        assertName(name);
        int binding = glGetInteger(GL_TEXTURE_BINDING_2D);
        try {
            glBindTexture(GL_TEXTURE_2D, name);
            glTexParameterf(GL_TEXTURE_2D, parameter, value);
            check("Minecraft GL float texture parameter");
        } finally { glBindTexture(GL_TEXTURE_2D, binding); }
    }
    public void mipmap(int name) {
        assertName(name);
        int binding = glGetInteger(GL_TEXTURE_BINDING_2D);
        try {
            glBindTexture(GL_TEXTURE_2D, name);
            glGenerateMipmap(GL_TEXTURE_2D);
            check("Minecraft GL mipmaps");
        } finally { glBindTexture(GL_TEXTURE_2D, binding); }
    }
    public void verifyDimensions(int name, int width, int height) {
        assertName(name);
        int binding = glGetInteger(GL_TEXTURE_BINDING_2D);
        try {
            glBindTexture(GL_TEXTURE_2D, name);
            if (glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_WIDTH) != width
                    || glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_HEIGHT) != height)
                throw new IllegalStateException("Minecraft/Zink texture dimensions differ for " + name);
        } finally { glBindTexture(GL_TEXTURE_2D, binding); }
    }
    private void assertName(int name) {
        assertOwner();
        if (!names.contains(name)) throw new IllegalArgumentException("Unregistered Minecraft GL texture " + name);
    }
    public static void check(String operation) {
        int error = glGetError();
        if (error != GL_NO_ERROR) throw new IllegalStateException(operation + ": GL error 0x" + Integer.toHexString(error));
    }
    @Override public void close() {
        if (closed) return;
        assertOwner();
        for (int name : names) glDeleteTextures(name);
        names.clear();
        active = null;
        closed = true;
    }
}
