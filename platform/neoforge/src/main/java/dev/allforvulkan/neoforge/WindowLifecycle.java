package dev.allforvulkan.neoforge;

import net.minecraft.client.Minecraft;
import net.vulkanmod.Initializer;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.egl.EGL14.eglGetCurrentContext;

/** Explicit private-client diagnostic, requiring an actual window manager for iconification. */
final class WindowLifecycle {
    private final Minecraft client;
    private final long window, context;
    private final boolean vsync;
    private int step, changedTick;
    private long changedFrame;

    WindowLifecycle(Minecraft client, int tick) {
        if (!BackendBootstrap.zinkClient()) throw new IllegalStateException("Window cycle requires the real Zink/Vulkan presenter");
        this.client = client;
        window = client.getWindow().getWindow();
        context = eglGetCurrentContext();
        vsync = client.options.enableVsync().get();
        changedTick = tick;
        glfwIconifyWindow(window);
        Initializer.LOGGER.info("AFV_WINDOW_CYCLE_BEGIN: requested actual window iconification");
    }
    boolean tick(int tick) {
        long frames = ZinkClientSession.presentedFrames();
        if (context == 0 || eglGetCurrentContext() != context) throw new IllegalStateException("Window cycle replaced the existing GL context");
        if (step == 0) {
            if (glfwGetWindowAttrib(window, GLFW_ICONIFIED) == 0) {
                if (tick - changedTick > 100) throw new IllegalStateException("Window manager did not iconify the QA window");
                return false;
            }
            changedTick = tick; changedFrame = frames; step = 1;
            return false;
        }
        if (step == 1) {
            if (glfwGetWindowAttrib(window, GLFW_ICONIFIED) == 0) throw new IllegalStateException("QA window restored before its suspended interval");
            if (frames != changedFrame) throw new IllegalStateException("Vulkan continued presenting to an iconified window");
            if (tick - changedTick < 20) return false;
            glfwRestoreWindow(window);
            changedTick = tick; changedFrame = frames; step = 2;
            Initializer.LOGGER.info("AFV_WINDOW_SUSPEND_PASS: iconified window stayed responsive with no Vulkan presentations");
            return false;
        }
        if (glfwGetWindowAttrib(window, GLFW_ICONIFIED) != 0 || tick - changedTick < 60 || frames - changedFrame < 60) return false;
        dev.allforvulkan.validation.GlFrameChecks.sampleColor(client.getMainRenderTarget().getColorTextureId());
        if (Boolean.getBoolean("afv.smokeIris")) dev.allforvulkan.validation.IrisAssertions.requireActive();
        if (step == 2) {
            client.getWindow().updateVsync(!vsync);
            Initializer.LOGGER.info("AFV_WINDOW_RESTORE_PASS: same GL context, active world and Vulkan presentations resumed");
        } else if (step == 3) {
            client.getWindow().updateVsync(vsync);
            Initializer.LOGGER.info("AFV_WINDOW_VSYNC_PASS: alternate synchronization mode rendered");
        } else {
            Initializer.LOGGER.info("AFV_WINDOW_CYCLE_PASS: iconify, responsive suspension, restore, VSync switch and restore, same GL context");
            return true;
        }
        step++; changedTick = tick; changedFrame = frames;
        return false;
    }
}
