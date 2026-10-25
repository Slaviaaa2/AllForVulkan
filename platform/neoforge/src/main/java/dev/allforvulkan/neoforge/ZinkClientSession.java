package dev.allforvulkan.neoforge;

import dev.allforvulkan.bridge.ZinkContext;
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
        try (var stack = MemoryStack.stackPush()) {
            var width = stack.mallocInt(1); var height = stack.mallocInt(1);
            org.lwjgl.glfw.GLFW.glfwGetFramebufferSize(handle, width, height);
            context = new ZinkContext(Math.max(1, width.get(0)), Math.max(1, height.get(0)));
        }
        var caps = GL.getCapabilities();
        if (dev.allforvulkan.bridge.DirectDefaultFramebuffer.ENABLED)
            dev.allforvulkan.bridge.DirectDefaultFramebufferProbe.run();
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
        try { presenter.present(); }
        catch (RuntimeException | LinkageError failure) { BackendBootstrap.presentationFailed(failure); throw failure; }
    }
    public static void prepareFrame() {
        if (context == null) return;
        try (var stack = MemoryStack.stackPush()) {
            var width = stack.mallocInt(1); var height = stack.mallocInt(1);
            org.lwjgl.glfw.GLFW.glfwGetFramebufferSize(window, width, height);
            if (width.get(0) > 0 && height.get(0) > 0 && (context.width() != width.get(0) || context.height() != height.get(0))) {
                context.resize(width.get(0), height.get(0));
                Initializer.LOGGER.info("AFV_ZINK_CLIENT_DRAWABLE: {}x{}, same GL context", context.width(), context.height());
            }
        }
        if (presenter != null) presenter.prepareDirectFrame();
    }
    public static void setVsync(boolean enabled) { vsync = enabled; if (presenter != null) presenter.setVsync(enabled); }
    public static long presentedFrames() { return presenter == null ? 0 : presenter.presentedFrames(); }
    public static boolean asynchronousPresentation() { return presenter != null && presenter.asynchronousPresentation(); }
    public static boolean framePipeline() { return presenter != null && presenter.framePipeline(); }
    public static int pipelineDepth() { return presenter == null ? 0 : presenter.pipelineDepth(); }
    public static boolean directOutput() { return presenter != null && presenter.directOutput(); }
    public static long directOutputFrames() { return presenter == null ? 0 : presenter.directOutputFrames(); }
    public static long droppedFrames() { return presenter == null ? 0 : presenter.droppedFrames(); }
    public static int pendingFrames() { return presenter == null ? 0 : presenter.pendingFrames(); }
    public static void awaitPresentation() { if (presenter != null) presenter.awaitPresentation(); }
    public static long verifiedFinalOutputFrames() { return presenter == null ? 0 : presenter.verifiedFinalOutputFrames(); }
    public static long reusedTransferFrames() { return presenter == null ? 0 : presenter.reusedTransferFrames(); }
    public static long recordedTransferCommands() { return presenter == null ? 0 : presenter.recordedTransferCommands(); }
    public static int recoveredSurfaces() { return presenter == null ? 0 : presenter.recoveredSurfaces(); }
    public static void requestSurfaceRecreation() {
        if (presenter == null) throw new IllegalStateException("Zink presenter is not initialized");
        presenter.requestSurfaceRecreation();
    }
    public static void close() {
        if (presenter != null) { presenter.close(); presenter = null; Vulkan.cleanUpPresentation(); }
        dev.allforvulkan.validation.GlDebugTrace.close();
        if (context != null) { context.close(); context = null; }
    }
}
