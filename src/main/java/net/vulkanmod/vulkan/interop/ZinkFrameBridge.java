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
    public static final int EFFECT = switch (System.getProperty("afv.zinkEffect", "identity")) {
        case "identity" -> 0;
        case "invert" -> 1;
        case "depth" -> 2;
        default -> throw new IllegalArgumentException("afv.zinkEffect must be identity, invert or depth");
    };
    public static final boolean INVERT = EFFECT == 1;
    private final ZinkContext context;
    private final GlFramePass pass;
    private final SharedImage[] scenes, outputs, depths;
    private final VkCommandBuffer[] compositions, continuations;
    private final PatchReadback[] assertions;
    private final SceneReadback[] sceneAssertions;
    private final int family = Queue.getQueueFamilies().graphicsFamily;
    private final int width, height;
    private final boolean srgb;
    private long pool, submittedFrames, verifiedFrames, verifiedScenes;
    private TextureMirrorSmoke textureSmoke;
    private TextureSampler sampler;
    private int prepared = -1;
    private boolean worldStage;
    private long worldFrames;
    private VulkanImage destination;

    public ZinkFrameBridge(ZinkContext context, GlFramePass pass, int frames, int width, int height, int format, int depthFormat) {
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
        depths = new SharedImage[frames];
        compositions = new VkCommandBuffer[frames];
        continuations = new VkCommandBuffer[frames];
        assertions = Boolean.getBoolean("afv.smokeTest") ? new PatchReadback[frames] : null;
        sceneAssertions = assertions != null ? new SceneReadback[frames] : null;
        try (MemoryStack stack = MemoryStack.stackPush(); var state = new GlStateScope()) {
            requireBlit(format, stack);
            requireBlit(sharedFormat, stack);
            var handle = stack.mallocLong(1);
            check(vkCreateCommandPool(Vulkan.getVkDevice(), VkCommandPoolCreateInfo.calloc(stack).sType$Default()
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT).queueFamilyIndex(family), null, handle), "bridge command pool");
            pool = handle.get(0);
            var pointers = stack.mallocPointer(frames * 2);
            check(vkAllocateCommandBuffers(Vulkan.getVkDevice(), VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                    .commandPool(pool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(frames * 2), pointers), "bridge command buffers");
            for (int i = 0; i < frames; i++) {
                compositions[i] = new VkCommandBuffer(pointers.get(i), Vulkan.getVkDevice());
                continuations[i] = new VkCommandBuffer(pointers.get(frames + i), Vulkan.getVkDevice());
                scenes[i] = new SharedImage(DeviceManager.physicalDevice, Vulkan.getVkDevice(), width, height, sharedFormat);
                outputs[i] = new SharedImage(DeviceManager.physicalDevice, Vulkan.getVkDevice(), width, height, sharedFormat);
                depths[i] = new SharedImage(DeviceManager.physicalDevice, Vulkan.getVkDevice(), width, height, depthFormat);
                if (assertions != null) {
                    assertions[i] = new PatchReadback();
                    sceneAssertions[i] = new SceneReadback(EFFECT);
                }
            }
            if (assertions != null) { textureSmoke = new TextureMirrorSmoke(); sampler = new TextureSampler(); }
            Initializer.LOGGER.info("AFV_ZINK_BRIDGE_READY: actual game VkDevice, {} frame slots, scene {}x{}, srgb={}, effect={}, depthFormat={}, renderer={}",
                    frames, width, height, srgb, effectName(), depthFormat, glGetString(GL_RENDERER));
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
    public void releaseScene(VkCommandBuffer command, VulkanImage image, VulkanImage depth, boolean world) {
        if (prepared < 0 || destination != null) throw new IllegalStateException("Invalid scene release boundary");
        if (image.width != width || image.height != height) throw new IllegalStateException("Scene dimensions changed without bridge recreation");
        destination = image;
        worldStage = world;
        if (sceneAssertions != null) sceneAssertions[prepared].world(world);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            image.transitionImageLayout(stack, command, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
            if (sceneAssertions != null) sceneAssertions[prepared].record(command, image, false);
            scenes[prepared].prepareTransferDestination(command);
            blit(command, image.getId(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, scenes[prepared].image(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, stack);
            depth.transitionImageLayout(stack, command, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
            if (sceneAssertions != null) sceneAssertions[prepared].recordDepth(command, depth);
            depths[prepared].prepareTransferDestination(command);
            int count = (depth.aspect & VK_IMAGE_ASPECT_STENCIL_BIT) != 0 ? 2 : 1;
            var copies = VkImageCopy.calloc(count, stack);
            for (int i = 0; i < count; i++) {
                int aspect = i == 0 ? VK_IMAGE_ASPECT_DEPTH_BIT : VK_IMAGE_ASPECT_STENCIL_BIT;
                copies.get(i).srcSubresource().aspectMask(aspect).layerCount(1);
                copies.get(i).dstSubresource().aspectMask(aspect).layerCount(1);
                copies.get(i).extent().set(width, height, 1);
            }
            copies.position(0);
            vkCmdCopyImage(command, depth.getId(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    depths[prepared].image(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, copies);
            depth.transitionImageLayout(stack, command, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
            depths[prepared].release(command, family);
            scenes[prepared].release(command, family);
            outputs[prepared].release(command, family);
        }
    }
    /** Submit Minecraft's finished commands before GL waits, then queue the real GL pass.
     * Returns a second command buffer; the caller submits it with the normal frame fence. */
    public VkCommandBuffer process(VkCommandBuffer minecraftCommands, long imageAvailable, boolean present) {
        if (prepared < 0 || destination == null) throw new IllegalStateException("Missing scene release");
        context.assertCurrentThread();
        try (MemoryStack stack = MemoryStack.stackPush(); var state = new GlStateScope()) {
            var scene = scenes[prepared];
            var output = outputs[prepared];
            check(vkQueueSubmit(DeviceManager.getGraphicsQueue().queue(), VkSubmitInfo.calloc(stack).sType$Default()
                    .waitSemaphoreCount(1).pWaitSemaphores(stack.longs(imageAvailable))
                    .pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT))
                    .pCommandBuffers(stack.pointers(minecraftCommands))
                    .pSignalSemaphores(stack.longs(scene.readyForGl(), output.readyForGl(), depths[prepared].readyForGl())), VK_NULL_HANDLE), "submit Minecraft scene release");
            int color = scene.beginGlRead();
            int depth = depths[prepared].beginGlRead();
            int framebuffer = output.beginGlWrite();
            pass.render(new GlFramePass.Frame(color, depth, framebuffer, width, height, srgb, worldStage ? GlFramePass.Stage.WORLD_END : GlFramePass.Stage.FRAME_END));
            if (sampler != null) {
                // Keep the existing CPU-asset sampling assertion in its own 32x32 corner.
                glEnable(GL_SCISSOR_TEST); glScissor(0, 0, 32, 32);
                sampler.draw(textureSmoke.texture());
            }
            depths[prepared].endGlRead();
            scene.endGlRead();
            output.endGlWrite();
            var command = compositions[prepared];
            check(vkResetCommandBuffer(command, 0), "reset scene composition");
            check(vkBeginCommandBuffer(command, VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "begin scene composition");
            depths[prepared].acquire(command, family);
            scene.acquire(command, family);
            output.acquire(command, family);
            destination.transitionImageLayout(stack, command, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            blit(command, output.image(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, destination.getId(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, stack);
            if (assertions != null) {
                assertions[prepared].record(command, destination);
                sceneAssertions[prepared].record(command, destination, true);
            }
            destination.transitionImageLayout(stack, command, present ? org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR : VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
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
    public VkCommandBuffer continuation(int frame) { return continuations[frame]; }
    public long depthWaitSemaphore() { return depths[prepared].readyForVulkan(); }
    public static String effectName() { return new String[] {"identity", "invert", "depth"}[EFFECT]; }
    public long worldFrames() { return worldFrames; }
    public long sceneWaitSemaphore() { return scenes[prepared].readyForVulkan(); }
    public long outputWaitSemaphore() { return outputs[prepared].readyForVulkan(); }
    public void submitted() {
        if (assertions != null) { assertions[prepared].submitted(); sceneAssertions[prepared].submitted(); }
        if (worldStage) worldFrames++;
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
        for (var image : depths) if (image != null) image.close();
        for (var image : outputs) if (image != null) image.close();
        if (pool != 0) vkDestroyCommandPool(Vulkan.getVkDevice(), pool, null);
    }
}
