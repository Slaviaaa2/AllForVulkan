package net.vulkanmod.vulkan.interop;

import dev.allforvulkan.bridge.*;
import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.queue.Queue;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.vulkan.VK10.*;
import static dev.allforvulkan.bridge.SharedImage.check;

/** Vulkan scene -> real GL frame pass -> Vulkan presentation. Opt-in, not an Iris backend. */
public final class ZinkFrameBridge implements AutoCloseable {
    public static final boolean ENABLED = Boolean.getBoolean("afv.zinkInterop");
    public static final boolean INVERT = switch (System.getProperty("afv.zinkEffect", "identity")) {
        case "identity" -> false;
        case "invert" -> true;
        default -> throw new IllegalArgumentException("afv.zinkEffect must be identity or invert");
    };
    private final ZinkContext context;
    private final GlFramePass pass;
    private final SharedImage[] scenes, outputs;
    private final VkCommandBuffer[] compositions;
    private final PatchReadback[] assertions;
    private final SceneReadback[] sceneAssertions;
    private final int family = Queue.getQueueFamilies().graphicsFamily;
    private final int width, height;
    private final boolean srgb;
    private long pool, submittedFrames, verifiedFrames, verifiedScenes;
    private TextureMirrorSmoke textureSmoke;
    private TextureSampler sampler;
    private int prepared = -1;
    private VulkanImage destination;

    public ZinkFrameBridge(ZinkContext context, GlFramePass pass, int frames, int width, int height, int format) {
        if (!System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("linux"))
            throw new IllegalStateException("The Zink opaque-FD bridge currently requires Linux");
        if (format != VK_FORMAT_R8G8B8A8_UNORM && format != VK_FORMAT_B8G8R8A8_UNORM
                && format != VK_FORMAT_R8G8B8A8_SRGB && format != VK_FORMAT_B8G8R8A8_SRGB)
            throw new IllegalArgumentException("Unsupported scene color format " + format);
        this.context = context;
        this.pass = pass;
        this.width = width;
        this.height = height;
        srgb = format == VK_FORMAT_R8G8B8A8_SRGB || format == VK_FORMAT_B8G8R8A8_SRGB;
        int sharedFormat = srgb ? VK_FORMAT_R8G8B8A8_SRGB : VK_FORMAT_R8G8B8A8_UNORM;
        context.assertCurrentThread();
        scenes = new SharedImage[frames];
        outputs = new SharedImage[frames];
        compositions = new VkCommandBuffer[frames];
        assertions = Boolean.getBoolean("afv.smokeTest") ? new PatchReadback[frames] : null;
        sceneAssertions = assertions != null ? new SceneReadback[frames] : null;
        try (MemoryStack stack = MemoryStack.stackPush(); var state = new GlStateScope()) {
            requireBlit(format, stack);
            requireBlit(sharedFormat, stack);
            var handle = stack.mallocLong(1);
            check(vkCreateCommandPool(Vulkan.getVkDevice(), VkCommandPoolCreateInfo.calloc(stack).sType$Default()
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT).queueFamilyIndex(family), null, handle), "bridge command pool");
            pool = handle.get(0);
            var pointers = stack.mallocPointer(frames);
            check(vkAllocateCommandBuffers(Vulkan.getVkDevice(), VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                    .commandPool(pool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(frames), pointers), "bridge command buffers");
            for (int i = 0; i < frames; i++) {
                compositions[i] = new VkCommandBuffer(pointers.get(i), Vulkan.getVkDevice());
                scenes[i] = new SharedImage(DeviceManager.physicalDevice, Vulkan.getVkDevice(), width, height, sharedFormat);
                outputs[i] = new SharedImage(DeviceManager.physicalDevice, Vulkan.getVkDevice(), width, height, sharedFormat);
                if (assertions != null) {
                    assertions[i] = new PatchReadback();
                    sceneAssertions[i] = new SceneReadback(INVERT);
                }
            }
            if (assertions != null) { textureSmoke = new TextureMirrorSmoke(); sampler = new TextureSampler(); }
            Initializer.LOGGER.info("AFV_ZINK_BRIDGE_READY: actual game VkDevice, {} frame slots, scene {}x{}, srgb={}, effect={}, renderer={}",
                    frames, width, height, srgb, INVERT ? "invert" : "identity", glGetString(GL_RENDERER));
        } catch (Throwable failure) { close(); throw failure; }
    }
    private static void requireBlit(int format, MemoryStack stack) {
        var properties = VkFormatProperties.calloc(stack);
        vkGetPhysicalDeviceFormatProperties(DeviceManager.physicalDevice, format, properties);
        int flags = VK_FORMAT_FEATURE_BLIT_SRC_BIT | VK_FORMAT_FEATURE_BLIT_DST_BIT;
        if ((properties.optimalTilingFeatures() & flags) != flags) throw new IllegalStateException("Unsupported scene blit format " + format);
    }
    /** Called only after the normal frame fence and successful swapchain acquire. */
    public void prepare(int frame) {
        context.assertCurrentThread();
        if (prepared >= 0) throw new IllegalStateException("Previous shared frame was not submitted");
        if (assertions != null) {
            if (assertions[frame].verify()) verifiedFrames++;
            if (sceneAssertions[frame].verify()) verifiedScenes++;
        }
        prepared = frame;
        destination = null;
    }
    /** At the end of Minecraft's render pass, record the scene copy and two releases. */
    public void releaseScene(VkCommandBuffer command, VulkanImage image) {
        if (prepared < 0 || destination != null) throw new IllegalStateException("Invalid scene release boundary");
        if (image.width != width || image.height != height) throw new IllegalStateException("Scene dimensions changed without bridge recreation");
        destination = image;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            image.transitionImageLayout(stack, command, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
            if (sceneAssertions != null) sceneAssertions[prepared].record(command, image, false);
            scenes[prepared].prepareTransferDestination(command);
            blit(command, image.getId(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, scenes[prepared].image(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, stack);
            scenes[prepared].release(command, family);
            outputs[prepared].release(command, family);
        }
    }
    /** Submit Minecraft's finished commands before GL waits, then queue the real GL pass.
     * Returns a second command buffer; the caller submits it with the normal frame fence. */
    public VkCommandBuffer process(VkCommandBuffer minecraftCommands, long imageAvailable) {
        if (prepared < 0 || destination == null) throw new IllegalStateException("Missing scene release");
        context.assertCurrentThread();
        try (MemoryStack stack = MemoryStack.stackPush(); var state = new GlStateScope()) {
            var scene = scenes[prepared];
            var output = outputs[prepared];
            check(vkQueueSubmit(DeviceManager.getGraphicsQueue().queue(), VkSubmitInfo.calloc(stack).sType$Default()
                    .waitSemaphoreCount(1).pWaitSemaphores(stack.longs(imageAvailable))
                    .pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT))
                    .pCommandBuffers(stack.pointers(minecraftCommands))
                    .pSignalSemaphores(stack.longs(scene.readyForGl(), output.readyForGl())), VK_NULL_HANDLE), "submit Minecraft scene release");
            int color = scene.beginGlRead();
            int framebuffer = output.beginGlWrite();
            pass.render(new GlFramePass.Frame(color, framebuffer, width, height, srgb));
            if (sampler != null) {
                // Keep the existing CPU-asset sampling assertion in its own 32x32 corner.
                glEnable(GL_SCISSOR_TEST); glScissor(0, 0, 32, 32);
                sampler.draw(textureSmoke.texture());
            }
            scene.endGlRead();
            output.endGlWrite();
            var command = compositions[prepared];
            check(vkResetCommandBuffer(command, 0), "reset scene composition");
            check(vkBeginCommandBuffer(command, VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "begin scene composition");
            scene.acquire(command, family);
            output.acquire(command, family);
            destination.transitionImageLayout(stack, command, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            blit(command, output.image(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, destination.getId(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, stack);
            if (assertions != null) {
                assertions[prepared].record(command, destination);
                sceneAssertions[prepared].record(command, destination, true);
            }
            destination.transitionImageLayout(stack, command, org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
            check(vkEndCommandBuffer(command), "end scene composition");
            return command;
        }
    }
    private void blit(VkCommandBuffer command, long source, int sourceLayout, long target, int targetLayout, MemoryStack stack) {
        var region = VkImageBlit.calloc(1, stack);
        region.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
        region.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
        region.srcOffsets(1).set(width, height, 1);
        region.dstOffsets(1).set(width, height, 1);
        vkCmdBlitImage(command, source, sourceLayout, target, targetLayout, region, VK_FILTER_NEAREST);
    }
    public long sceneWaitSemaphore() { return scenes[prepared].readyForVulkan(); }
    public long outputWaitSemaphore() { return outputs[prepared].readyForVulkan(); }
    public void submitted() {
        if (assertions != null) { assertions[prepared].submitted(); sceneAssertions[prepared].submitted(); }
        prepared = -1;
        destination = null;
        if (++submittedFrames == 120) Initializer.LOGGER.info("AFV_ZINK_BRIDGE_FRAMES: 120 Minecraft -> GL -> Vulkan scene frames submitted");
    }
    public long submittedFrames() { return submittedFrames; }
    public long verifiedFrames() { return verifiedFrames; }
    public long verifiedScenes() { return verifiedScenes; }
    /** The caller has already drained game GPU work at resize/shutdown. GL context survives resize. */
    @Override public void close() {
        context.assertCurrentThread();
        glFinish(); // Teardown only.
        if (assertions != null) {
            for (int i = 0; i < assertions.length; i++) {
                if (assertions[i] != null) { if (assertions[i].verify()) verifiedFrames++; assertions[i].close(); }
                if (sceneAssertions[i] != null) { if (sceneAssertions[i].verify()) verifiedScenes++; sceneAssertions[i].close(); }
            }
            Initializer.LOGGER.info("AFV_ZINK_PATCH_VERIFIED: {} asset patches; AFV_ZINK_SCENE_VERIFIED: {} bounded scene regions", verifiedFrames, verifiedScenes);
        }
        if (sampler != null) sampler.close();
        if (textureSmoke != null) textureSmoke.close();
        for (var image : scenes) if (image != null) image.close();
        for (var image : outputs) if (image != null) image.close();
        if (pool != 0) vkDestroyCommandPool(Vulkan.getVkDevice(), pool, null);
    }
}
