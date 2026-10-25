package dev.allforvulkan.bridge;

import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.egl.EGL14.eglGetCurrentContext;
import static org.lwjgl.opengl.GL11.*;

/** Isolated native-window route: no AFV VkDevice, FD imports or interop ring. */
final class ZinkWindowProbe {
    static void run() {
        long window = 0;
        boolean initialized = false, glCreated = false;
        try {
            if (!glfwInit()) throw new IllegalStateException("GLFW cannot initialize the native Zink display");
            initialized = true;
            glfwDefaultWindowHints();
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
            glfwWindowHint(GLFW_FOCUSED, GLFW_FALSE);
            glfwWindowHint(GLFW_CONTEXT_CREATION_API, GLFW_EGL_CONTEXT_API);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 6);
            glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
            glfwWindowHint(GLFW_DEPTH_BITS, 24);
            glfwWindowHint(GLFW_STENCIL_BITS, 8);
            window = glfwCreateWindow(32, 32, "AFV native Zink capability check", 0, 0);
            if (window == 0) throw new IllegalStateException("Cannot create a native Zink EGL window");
            glfwMakeContextCurrent(window);
            if (eglGetCurrentContext() == 0) throw new IllegalStateException("No current native EGL context");
            org.lwjgl.system.Configuration.OPENGL_EXPLICIT_INIT.set(true);
            GL.create(org.lwjgl.egl.EGL10::eglGetProcAddress);
            glCreated = true;
            var caps = GL.createCapabilities();
            String renderer = glGetString(GL_RENDERER);
            if (!caps.OpenGL46 || renderer == null || !renderer.toLowerCase(java.util.Locale.ROOT).contains("zink"))
                throw new IllegalStateException("Expected Zink GL 4.6, got " + renderer);
            glfwSwapInterval(0);
            try (var stack = MemoryStack.stackPush()) {
                var pixel = stack.malloc(4);
                for (int i = 0; i < 8; i++) {
                    int r = (i * 31 + 17) & 255, g = (i * 19 + 29) & 255, b = (i * 13 + 43) & 255;
                    glClearColor(r / 255f, g / 255f, b / 255f, 1);
                    glClear(GL_COLOR_BUFFER_BIT);
                    glReadBuffer(glGetBoolean(GL_DOUBLEBUFFER) ? GL_BACK : GL_FRONT);
                    glReadPixels(0, 0, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
                    if ((pixel.get(0) & 255) != r || (pixel.get(1) & 255) != g || (pixel.get(2) & 255) != b)
                        throw new IllegalStateException("Native window color round trip failed");
                    glfwSwapBuffers(window);
                }
            }
            ZinkProbe.computeRoundTrip();
            if (glGetError() != GL_NO_ERROR) throw new IllegalStateException("Native window probe GL error");
            System.out.println("AFV_ZINK_WINDOW_PROBE_PASS: renderer=" + renderer + ", EGL window, GL4.6, compute, 8 color/swap rounds, no AFV presentation bridge; kopperDri2=" + System.getenv("LIBGL_KOPPER_DRI2"));
        } finally {
            if (glCreated) { GL.setCapabilities(null); GL.destroy(); }
            if (window != 0) glfwDestroyWindow(window);
            if (initialized) glfwTerminate();
        }
    }
}
