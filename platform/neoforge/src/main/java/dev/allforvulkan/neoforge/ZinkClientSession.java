package dev.allforvulkan.neoforge;

import dev.allforvulkan.bridge.ZinkContext;
import net.minecraft.client.Minecraft;
import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.interop.ZinkPresenter;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;
import static org.lwjgl.opengl.EXTMemoryObject.*;

/** Render-thread lifetime of the GL frontend and AFV's own Vulkan presentation device. */
public final class ZinkClientSession {
    private static ZinkContext context;
    private static ZinkPresenter presenter;
    private static boolean vsync = true;
    private static long window;
    private ZinkClientSession() {}
    public static void open(long handle) {
        if (context != null) throw new IllegalStateException("Zink client already initialized");
        window = handle;
        context = new ZinkContext(32, 32);
        var caps = GL.getCapabilities();
        if (!caps.GL_EXT_memory_object_fd || !caps.GL_EXT_semaphore_fd)
            throw new IllegalStateException("Zink client requires opaque FD memory and semaphore sharing");
        try (var stack = MemoryStack.stackPush()) {
            var uuid = stack.malloc(GL_UUID_SIZE_EXT);
            glGetUnsignedBytei_vEXT(GL_DEVICE_UUID_EXT, 0, uuid);
            byte[] id = new byte[GL_UUID_SIZE_EXT]; uuid.get(id);
            Vulkan.initPresentation(handle, id);
        }
        presenter = new ZinkPresenter(context, handle);
        presenter.setVsync(vsync);
        Initializer.LOGGER.info("AFV_ZINK_CLIENT_READY: frontend={}, Vulkan presentation device={}",
                org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER),
                net.vulkanmod.vulkan.device.DeviceManager.deviceProperties.deviceNameString());
    }
    public static void present(long handle) {
        if (handle != window || presenter == null) throw new IllegalStateException("Wrong Zink presentation window");
        dev.allforvulkan.validation.GlDebugTrace.installIfRequested();
        var minecraft = Minecraft.getInstance();
        if (minecraft != null && minecraft.getMainRenderTarget() != null)
            presenter.present(minecraft.getMainRenderTarget().getColorTextureId());
    }
    public static void setVsync(boolean enabled) { vsync = enabled; if (presenter != null) presenter.setVsync(enabled); }
    public static long presentedFrames() { return presenter == null ? 0 : presenter.presentedFrames(); }
    public static void close() {
        if (presenter != null) { presenter.close(); presenter = null; Vulkan.cleanUpPresentation(); }
        dev.allforvulkan.validation.GlDebugTrace.close();
        if (context != null) { context.close(); context = null; }
    }
}
