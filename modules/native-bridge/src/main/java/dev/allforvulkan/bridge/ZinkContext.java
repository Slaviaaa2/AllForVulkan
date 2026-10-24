package dev.allforvulkan.bridge;

import org.lwjgl.PointerBuffer;
import org.lwjgl.egl.EGL;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.Configuration;
import org.lwjgl.system.MemoryStack;
import static org.lwjgl.egl.EGL10.*;
import static org.lwjgl.egl.EGL12.*;
import static org.lwjgl.egl.EGL14.*;
import static org.lwjgl.egl.EGL15.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL45.glCheckNamedFramebufferStatus;

/** An independent, thread-confined Mesa desktop GL context on a hidden pbuffer. */
public final class ZinkContext implements AutoCloseable {
    private final Thread owner = Thread.currentThread();
    private long display, config, surface, context;
    private boolean glCreated, closed;
    private int width, height;
    private int defaultReadBuffer;

    public ZinkContext(int width, int height) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid pbuffer size");
        this.width = width;
        this.height = height;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long getPlatformDisplay = EGL.getCapabilities().eglGetPlatformDisplayEXT;
            if (getPlatformDisplay == 0) throw new IllegalStateException("EGL_EXT_platform_base is unavailable");
            // LWJGL 3.3.3 marks native_display non-null, but surfaceless Mesa
            // requires EGL_DEFAULT_DISPLAY (0). Call that verified entrypoint directly.
            display = org.lwjgl.system.JNI.callPPP(0x31DD, EGL_DEFAULT_DISPLAY, 0L, getPlatformDisplay);
            require(display != EGL_NO_DISPLAY, "eglGetPlatformDisplay(surfaceless)");
            var major = stack.mallocInt(1);
            var minor = stack.mallocInt(1);
            require(eglInitialize(display, major, minor), "eglInitialize");
            EGL.createDisplayCapabilities(display, major.get(0), minor.get(0));
            require(eglBindAPI(EGL_OPENGL_API), "eglBindAPI(desktop GL)");
            PointerBuffer configs = stack.mallocPointer(1);
            var count = stack.mallocInt(1);
            require(eglChooseConfig(display, stack.ints(EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
                    EGL_RENDERABLE_TYPE, EGL_OPENGL_BIT, EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8,
                    EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_DEPTH_SIZE, 24, EGL_STENCIL_SIZE, 8,
                    EGL_SAMPLE_BUFFERS, 0, EGL_NONE), configs, count) && count.get(0) > 0, "eglChooseConfig");
            config = configs.get(0);
            surface = eglCreatePbufferSurface(display, config, stack.ints(EGL_WIDTH, width, EGL_HEIGHT, height, EGL_NONE));
            require(surface != EGL_NO_SURFACE, "eglCreatePbufferSurface");
            context = eglCreateContext(display, config, EGL_NO_CONTEXT, stack.ints(
                    EGL_CONTEXT_MAJOR_VERSION, 4, EGL_CONTEXT_MINOR_VERSION, 6,
                    EGL_CONTEXT_OPENGL_PROFILE_MASK, EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT, EGL_NONE));
            require(context != EGL_NO_CONTEXT, "eglCreateContext(GL 4.6 core)");
            require(eglMakeCurrent(display, surface, surface, context), "eglMakeCurrent");
            Configuration.OPENGL_EXPLICIT_INIT.set(true);
            GL.create(org.lwjgl.egl.EGL10::eglGetProcAddress);
            glCreated = true;
            GL.createCapabilities();
            defaultReadBuffer = glGetBoolean(GL_DOUBLEBUFFER) ? GL_BACK : GL_FRONT;
            validateDefaultFramebuffer();
            String renderer = glGetString(GL_RENDERER);
            if (renderer == null || !renderer.toLowerCase(java.util.Locale.ROOT).contains("zink"))
                throw new IllegalStateException("Expected real Zink, got " + renderer);
        } catch (Throwable failure) {
            close();
            throw failure;
        }
    }

    public int width() { return width; }
    public int height() { return height; }
    /** The same EGL config is retained for every drawable in this context. */
    public int defaultReadBuffer() { return defaultReadBuffer; }
    /** Replace only the drawable, retaining the context, objects and driver state.
     * Call before drawing a new game frame, never after its default-buffer output. */
    public void resize(int width, int height) {
        assertCurrentThread();
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid pbuffer size");
        if (this.width == width && this.height == height) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long next = eglCreatePbufferSurface(display, config, stack.ints(EGL_WIDTH, width, EGL_HEIGHT, height, EGL_NONE));
            require(next != EGL_NO_SURFACE, "resize eglCreatePbufferSurface");
            boolean adopted = false;
            try {
                var actualWidth = stack.mallocInt(1); var actualHeight = stack.mallocInt(1);
                require(eglQuerySurface(display, next, EGL_WIDTH, actualWidth), "query resized pbuffer width");
                require(eglQuerySurface(display, next, EGL_HEIGHT, actualHeight), "query resized pbuffer height");
                if (actualWidth.get(0) != width || actualHeight.get(0) != height)
                    throw new IllegalStateException("EGL did not create the requested drawable size");
                try (var state = new GlStateScope()) {
                    glFinish(); // Drawable replacement only, not the normal frame path.
                    require(eglMakeCurrent(display, next, next, context), "resize eglMakeCurrent");
                    long previous = surface;
                    surface = next;
                    this.width = width; this.height = height;
                    adopted = true;
                    require(eglDestroySurface(display, previous), "destroy previous pbuffer");
                    validateDefaultFramebuffer();
                }
            } finally { if (!adopted) eglDestroySurface(display, next); }
        }
    }
    private static void validateDefaultFramebuffer() {
        if (glCheckNamedFramebufferStatus(0, GL_READ_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
            throw new IllegalStateException("Zink default framebuffer is incomplete");
    }
    public void assertCurrentThread() {
        if (closed || Thread.currentThread() != owner) throw new IllegalStateException("Zink context is closed or used from another thread");
    }
    private static void require(boolean result, String operation) {
        if (!result) throw new IllegalStateException(operation + " failed: EGL error 0x" + Integer.toHexString(eglGetError()));
    }
    @Override public void close() {
        if (closed) return;
        if (Thread.currentThread() != owner) throw new IllegalStateException("Close Zink on its owning thread");
        closed = true;
        if (glCreated) { GL.setCapabilities(null); GL.destroy(); }
        if (display != EGL_NO_DISPLAY) {
            eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            if (context != EGL_NO_CONTEXT) eglDestroyContext(display, context);
            if (surface != EGL_NO_SURFACE) eglDestroySurface(display, surface);
            eglTerminate(display);
        }
        context = surface = display = 0;
    }
}
