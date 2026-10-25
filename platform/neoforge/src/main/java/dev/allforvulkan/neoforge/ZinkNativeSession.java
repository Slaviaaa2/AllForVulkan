package dev.allforvulkan.neoforge;

import dev.allforvulkan.bridge.GlStateScope;
import net.vulkanmod.Initializer;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL21.*;
import static org.lwjgl.opengl.GL30.*;

/** The game GL frontend and Mesa/Zink share the window's native presentation path.
 * No AFV Vulkan device, shared-image transport, extra copy or queue worker exists. */
public final class ZinkNativeSession {
    private static long window, swaps, verifiedOutput;
    private static Thread owner;
    private ZinkNativeSession() {}

    public static void open(long handle) {
        if (window != 0 || handle == 0 || glfwGetCurrentContext() != handle)
            throw new IllegalStateException("Invalid native Zink window lifetime");
        String renderer = glGetString(GL_RENDERER);
        if (!GL.getCapabilities().OpenGL46 || renderer == null || !renderer.toLowerCase(java.util.Locale.ROOT).contains("zink"))
            throw new IllegalStateException("Native Zink route requires real Zink GL 4.6, got " + renderer);
        if (glfwGetWindowAttrib(handle, GLFW_CONTEXT_CREATION_API) != GLFW_EGL_CONTEXT_API)
            throw new IllegalStateException("Native Zink route requires an EGL window");
        owner = Thread.currentThread();
        window = handle;
        Initializer.LOGGER.info("AFV_ZINK_NATIVE_READY: renderer={}, no AFV VkDevice, shared ring or presentation transfer", renderer);
    }
    public static void swap(long handle) {
        if (handle != window || Thread.currentThread() != owner) throw new IllegalStateException("Wrong native Zink window/thread");
        try (var stack = MemoryStack.stackPush()) {
            var width = stack.mallocInt(1); var height = stack.mallocInt(1);
            glfwGetFramebufferSize(window, width, height);
            if (width.get(0) <= 0 || height.get(0) <= 0 || glfwGetWindowAttrib(window, GLFW_ICONIFIED) != 0) return;
            if (Boolean.getBoolean("afv.smokeNativeOutput") && swaps % 60 == 0
                    && net.minecraft.client.Minecraft.getInstance().level != null)
                verifyOutput(width.get(0), height.get(0));
            glfwSwapBuffers(window);
            if (++swaps == 120) Initializer.LOGGER.info("AFV_ZINK_NATIVE_SWAP_PASS: 120 completed window swap calls; Zink owns presentation");
        } catch (RuntimeException | LinkageError failure) { BackendBootstrap.presentationFailed(failure); throw failure; }
    }
    /** Optional bounded GL-window readback, before swap. This is not a VkImage readback. */
    private static void verifyOutput(int width, int height) {
        try (var state = new GlStateScope(); var stack = MemoryStack.stackPush()) {
            int pbo = glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING);
            int[] keys = {GL_PACK_ALIGNMENT, GL_PACK_ROW_LENGTH, GL_PACK_SKIP_PIXELS, GL_PACK_SKIP_ROWS};
            int[] values = new int[keys.length];
            for (int i = 0; i < keys.length; i++) values[i] = glGetInteger(keys[i]);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
            int read = glGetInteger(GL_READ_BUFFER);
            try {
                glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
                for (int key : keys) glPixelStorei(key, key == GL_PACK_ALIGNMENT ? 1 : 0);
                glReadBuffer(glGetBoolean(GL_DOUBLEBUFFER) ? GL_BACK : GL_FRONT);
                int w = Math.min(width, 32), h = Math.min(height, 32);
                var pixels = stack.malloc(w * h * 4);
                glReadPixels((width - w) / 2, (height - h) / 2, w, h, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
                boolean nonblack = false;
                for (int i = 0; i < pixels.capacity(); i += 4)
                    nonblack |= (pixels.get(i) | pixels.get(i + 1) | pixels.get(i + 2)) != 0;
                if (!nonblack) throw new IllegalStateException("Native Zink window output was entirely black in the diagnostic region");
                if (++verifiedOutput == 1) Initializer.LOGGER.info("AFV_ZINK_NATIVE_OUTPUT_PASS: nonblack bounded default-framebuffer pixels before native swap");
            } finally {
                glReadBuffer(read);
                glBindBuffer(GL_PIXEL_PACK_BUFFER, pbo);
                for (int i = 0; i < keys.length; i++) glPixelStorei(keys[i], values[i]);
            }
        }
    }
    public static long completedSwaps() { return swaps; }
    public static long verifiedOutput() { return verifiedOutput; }
    public static void awaitRendering() { if (window != 0) glFinish(); }
    public static void close() {
        if (window != 0 && Thread.currentThread() != owner) throw new IllegalStateException("Close native Zink on its render thread");
        if (window != 0) Initializer.LOGGER.info("AFV_ZINK_NATIVE_STATS: completedGlSwaps={}, verifiedWindowRegions={}; swap calls are not measured scanouts", swaps, verifiedOutput);
        dev.allforvulkan.validation.GlDebugTrace.close();
        window = 0;
        owner = null;
    }
}
