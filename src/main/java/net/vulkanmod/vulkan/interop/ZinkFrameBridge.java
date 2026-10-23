package net.vulkanmod.vulkan.interop;

import dev.allforvulkan.bridge.SharedImage;
import dev.allforvulkan.bridge.ZinkContext;
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

/** Opt-in Linux integration diagnostic. This is not yet an Iris render backend. */
public final class ZinkFrameBridge implements AutoCloseable {
    public static final boolean ENABLED = Boolean.getBoolean("afv.zinkInterop");
    private final ZinkContext context;
    private final SharedImage[] images;
    private final VkCommandBuffer[] releases;
    private final PatchReadback[] assertions;
    private long verifiedFrames;
    private TextureMirrorSmoke textureSmoke;
    private dev.allforvulkan.bridge.TextureSampler sampler;
    private final int family = Queue.getQueueFamilies().graphicsFamily;
    private long pool, submittedFrames;
    private int prepared = -1, validatedFormat = -1;

    public ZinkFrameBridge(ZinkContext context, int frames) {
        if (!System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("linux"))
            throw new IllegalStateException("The Zink opaque-FD bridge currently requires Linux");
        this.context = context;
        context.assertCurrentThread();
        images = new SharedImage[frames];
        releases = new VkCommandBuffer[frames];
        assertions = Boolean.getBoolean("afv.smokeTest") ? new PatchReadback[frames] : null;
        try (MemoryStack stack = MemoryStack.stackPush(); var state = new dev.allforvulkan.bridge.GlStateScope()) {
            var properties = VkFormatProperties.calloc(stack);
            vkGetPhysicalDeviceFormatProperties(DeviceManager.physicalDevice, VK_FORMAT_R8G8B8A8_UNORM, properties);
            if ((properties.optimalTilingFeatures() & VK_FORMAT_FEATURE_BLIT_SRC_BIT) == 0)
                throw new IllegalStateException("Shared RGBA8 image does not support blit source");
            var handle = stack.mallocLong(1);
            check(vkCreateCommandPool(Vulkan.getVkDevice(), VkCommandPoolCreateInfo.calloc(stack).sType$Default()
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT).queueFamilyIndex(family), null, handle), "bridge command pool");
            pool = handle.get(0);
            var pointers = stack.mallocPointer(frames);
            check(vkAllocateCommandBuffers(Vulkan.getVkDevice(), VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                    .commandPool(pool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(frames), pointers), "bridge command buffers");
            for (int i = 0; i < frames; i++) {
                releases[i] = new VkCommandBuffer(pointers.get(i), Vulkan.getVkDevice());
                images[i] = new SharedImage(DeviceManager.physicalDevice, Vulkan.getVkDevice(), 32, 32);
                if (assertions != null) assertions[i] = new PatchReadback();
            }
            if (assertions != null) {
                textureSmoke = new TextureMirrorSmoke();
                sampler = new dev.allforvulkan.bridge.TextureSampler();
            }
            Initializer.LOGGER.info("AFV_ZINK_BRIDGE_READY: actual game VkDevice, {} frame slots, renderer={}", frames, glGetString(GL_RENDERER));
        } catch (Throwable failure) { close(); throw failure; }
    }

    /** Called after the game's normal frame fence, and only after successful swapchain acquire. */
    public void prepare(int frame) {
        context.assertCurrentThread();
        if (prepared >= 0) throw new IllegalStateException("Previous shared frame was not submitted");
        try (MemoryStack stack = MemoryStack.stackPush(); var state = new dev.allforvulkan.bridge.GlStateScope()) {
            if (assertions != null && assertions[frame].verify()) verifiedFrames++;
            var command = releases[frame];
            check(vkResetCommandBuffer(command, 0), "reset shared release");
            check(vkBeginCommandBuffer(command, VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "begin shared release");
            images[frame].release(command, family);
            check(vkEndCommandBuffer(command), "end shared release");
            check(vkQueueSubmit(DeviceManager.getGraphicsQueue().queue(), VkSubmitInfo.calloc(stack).sType$Default()
                    .pCommandBuffers(stack.pointers(command)).pSignalSemaphores(stack.longs(images[frame].readyForGl())), VK_NULL_HANDLE), "submit shared release");
            images[frame].beginGlWrite();
            glDisable(GL_SCISSOR_TEST);
            glColorMask(true, true, true, true);
            // A bounded, recognizable diagnostic patch; no CPU image transport.
            glClearColor(1, 0, 1, 1);
            glClear(GL_COLOR_BUFFER_BIT);
            if (sampler != null) sampler.draw(textureSmoke.texture());
            images[frame].endGlWrite();
            prepared = frame;
        }
    }

    /** Outside the Minecraft render pass, before transition to PRESENT. */
    public void compose(VkCommandBuffer command, VulkanImage destination) {
        if (prepared < 0) throw new IllegalStateException("No shared frame prepared");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (validatedFormat != destination.format) {
                var properties = VkFormatProperties.calloc(stack);
                vkGetPhysicalDeviceFormatProperties(DeviceManager.physicalDevice, destination.format, properties);
                if ((properties.optimalTilingFeatures() & VK_FORMAT_FEATURE_BLIT_DST_BIT) == 0)
                    throw new IllegalStateException("Swapchain format does not support shared image blit");
                validatedFormat = destination.format;
            }
            var image = images[prepared];
            image.acquire(command, family);
            destination.transitionImageLayout(stack, command, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            var region = VkImageBlit.calloc(1, stack);
            region.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            region.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            region.srcOffsets(1).set(image.width(), image.height(), 1);
            region.dstOffsets(1).set(Math.min(image.width(), destination.width), Math.min(image.height(), destination.height), 1);
            vkCmdBlitImage(command, image.image(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, destination.getId(),
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region, VK_FILTER_NEAREST);
            if (assertions != null) assertions[prepared].record(command, destination);
        }
    }
    public long waitSemaphore() {
        if (prepared < 0) throw new IllegalStateException("No shared frame prepared");
        return images[prepared].readyForVulkan();
    }
    public void submitted() {
        if (assertions != null) assertions[prepared].submitted();
        prepared = -1;
        if (++submittedFrames == 120) Initializer.LOGGER.info("AFV_ZINK_BRIDGE_FRAMES: 120 game frames submitted with external image/semaphore composition");
    }
    public long submittedFrames() { return submittedFrames; }
    public long verifiedFrames() { return verifiedFrames; }
    /** Caller has already waited for the game's GPU work, e.g. resize or shutdown. */
    @Override public void close() {
        context.assertCurrentThread();
        glFinish(); // Teardown only. No extra CPU/GPU waits in prepare/compose.
        if (assertions != null) {
            for (var assertion : assertions) if (assertion != null) {
                if (assertion.verify()) verifiedFrames++;
                assertion.close();
            }
            Initializer.LOGGER.info("AFV_ZINK_PATCH_VERIFIED: {} completed swapchain patches (32x32 maximum)", verifiedFrames);
        }
        if (sampler != null) sampler.close();
        if (textureSmoke != null) textureSmoke.close();
        for (var image : images) if (image != null) image.close();
        if (pool != 0) vkDestroyCommandPool(Vulkan.getVkDevice(), pool, null);

    }
}
