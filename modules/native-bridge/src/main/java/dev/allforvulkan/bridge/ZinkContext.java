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

/** An independent, thread-confined Mesa desktop GL context on a hidden pbuffer. */
public final class ZinkContext implements AutoCloseable {
    private final Thread owner = Thread.currentThread();
    private long display, surface, context;
    private boolean glCreated, closed;
    private final int width, height;

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
                    EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_NONE), configs, count) && count.get(0) > 0, "eglChooseConfig");
            surface = eglCreatePbufferSurface(display, configs.get(0), stack.ints(EGL_WIDTH, width, EGL_HEIGHT, height, EGL_NONE));
            require(surface != EGL_NO_SURFACE, "eglCreatePbufferSurface");
            context = eglCreateContext(display, configs.get(0), EGL_NO_CONTEXT, stack.ints(
                    EGL_CONTEXT_MAJOR_VERSION, 4, EGL_CONTEXT_MINOR_VERSION, 6,
                    EGL_CONTEXT_OPENGL_PROFILE_MASK, EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT, EGL_NONE));
            require(context != EGL_NO_CONTEXT, "eglCreateContext(GL 4.6 core)");
            require(eglMakeCurrent(display, surface, surface, context), "eglMakeCurrent");
            Configuration.OPENGL_EXPLICIT_INIT.set(true);
            GL.create(org.lwjgl.egl.EGL10::eglGetProcAddress);
            glCreated = true;
            GL.createCapabilities();
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
