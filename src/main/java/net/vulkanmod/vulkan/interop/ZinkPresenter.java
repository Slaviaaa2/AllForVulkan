package net.vulkanmod.vulkan.interop;

import dev.allforvulkan.bridge.GlStateScope;
import dev.allforvulkan.bridge.PresentationSurface;
import dev.allforvulkan.bridge.SharedImage;
import dev.allforvulkan.bridge.ZinkContext;
import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.queue.Queue;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import static dev.allforvulkan.bridge.SharedImage.check;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;

/** Real GL producer -> exported GPU image -> AFV Vulkan WSI. No frame readback.
 * Binary external semaphores transfer image ownership in both directions. */
public final class ZinkPresenter implements AutoCloseable {
    private static final int FRAMES = 2;
    private final ZinkContext context;
    private final long window;
    private final VkDevice device = Vulkan.getVkDevice();
    private final int family = Queue.getQueueFamilies().graphicsFamily;
    private final SharedImage[] frames = new SharedImage[FRAMES];
    private final VkCommandBuffer[] commands = new VkCommandBuffer[FRAMES];
    private final long[] available = new long[FRAMES], fences = new long[FRAMES];
    private final FinalOutputReadback[] outputChecks = Boolean.getBoolean("afv.smokeFinalOutput") ? new FinalOutputReadback[FRAMES] : null;
    private long swapchain;
    private long[] images, presentReady;
    private int width, height, frame;
    private int windowWidth, windowHeight;
    private boolean vsync = true, dirty = true, closed;
    private long presented;
    private long verifiedOutput;
    private int producerErrors;

    public ZinkPresenter(ZinkContext context, long window) {
        this.context = context;
        this.window = window;
        try (var stack = MemoryStack.stackPush()) {
            var pointers = stack.mallocPointer(FRAMES);
            check(vkAllocateCommandBuffers(device, VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                    .commandPool(Vulkan.getCommandPool()).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(FRAMES), pointers), "allocate presentation commands");
            var handle = stack.mallocLong(1);
            for (int i = 0; i < FRAMES; i++) {
                commands[i] = new VkCommandBuffer(pointers.get(i), device);
                available[i] = semaphore(stack);
                check(vkCreateFence(device, VkFenceCreateInfo.calloc(stack).sType$Default().flags(VK_FENCE_CREATE_SIGNALED_BIT), null, handle), "presentation fence");
                fences[i] = handle.get(0);
            }
        } catch (Throwable failure) { close(); throw failure; }
    }

    public void setVsync(boolean enabled) { if (vsync != enabled) { vsync = enabled; dirty = true; } }
    public long presentedFrames() { return presented; }
    public long verifiedFinalOutputFrames() { return verifiedOutput; }

    public void present() {
        context.assertCurrentThread();
        // A producer's sticky GL error must not be attributed to FD import or
        // semaphore submission. Report it separately, with bounded logging.
        for (int error, n = 0; n < 16 && (error = glGetError()) != GL_NO_ERROR; n++) {
            if (++producerErrors <= 8) Initializer.LOGGER.warn("AFV_ZINK_CLIENT_PRODUCER_ERROR: GL 0x{} before presentation", Integer.toHexString(error));
        }
        try (var stack = MemoryStack.stackPush(); var state = new GlStateScope()) {
            var w = stack.mallocInt(1); var h = stack.mallocInt(1);
            org.lwjgl.glfw.GLFW.glfwGetFramebufferSize(window, w, h);
            if (w.get(0) == 0 || h.get(0) == 0 || org.lwjgl.glfw.GLFW.glfwGetWindowAttrib(window, org.lwjgl.glfw.GLFW.GLFW_ICONIFIED) != 0) {
                dirty = true; return;
            }
            if ((dirty || windowWidth != w.get(0) || windowHeight != h.get(0)) && !recreate(w.get(0), h.get(0))) return;
            check(vkWaitForFences(device, fences[frame], true, -1L), "wait presentation slot");
            if (outputChecks != null && outputChecks[frame].verify()) verifiedOutput++;
            var index = stack.mallocInt(1);
            // Keep event processing alive if an occluded/minimized surface stops making progress.
            int acquired = vkAcquireNextImageKHR(device, swapchain, 100_000_000L, available[frame], 0, index);
            if (acquired == VK_TIMEOUT || acquired == VK_NOT_READY) return;
            if (acquired == VK_ERROR_OUT_OF_DATE_KHR) { dirty = true; return; }
            if (acquired != VK_SUBOPTIMAL_KHR) check(acquired, "acquire presentation image");
            else dirty = true;
            int imageIndex = index.get(0);
            var shared = frames[frame];
            boolean checkOutput = outputChecks != null && width == context.width() && height == context.height() && width >= 32 && height >= 32;
            if (checkOutput) drawFinalMarker();
            glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
            int previousReadBuffer = glGetInteger(GL_READ_BUFFER);
            shared.beginGlWrite();
            glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
            glReadBuffer(glGetBoolean(GL_DOUBLEBUFFER) ? GL_BACK : GL_FRONT);
            if (glCheckFramebufferStatus(GL_READ_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
                throw new IllegalStateException("Client default framebuffer is incomplete");
            glDisable(GL_SCISSOR_TEST); glDisable(GL_FRAMEBUFFER_SRGB);
            glBlitFramebuffer(0, 0, context.width(), context.height(), 0, 0, width, height, GL_COLOR_BUFFER_BIT, GL_NEAREST);
            glReadBuffer(previousReadBuffer);
            int blitError = glGetError();
            if (blitError != GL_NO_ERROR) throw new IllegalStateException("Client color transfer: GL error 0x" + Integer.toHexString(blitError));
            shared.endGlWrite();

            var command = commands[frame];
            check(vkResetCommandBuffer(command, 0), "reset presentation command");
            check(vkBeginCommandBuffer(command, VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "begin presentation command");
            shared.acquire(command, family);
            if (checkOutput) outputChecks[frame].record(command, shared.image(), width, height, stack);
            imageBarrier(command, images[imageIndex], VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    0, VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, stack);
            var blit = VkImageBlit.calloc(1, stack);
            blit.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            blit.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            // GL texture rows start at the bottom; Vulkan WSI rows start at the top.
            blit.srcOffsets(0).set(0, height, 0); blit.srcOffsets(1).set(width, 0, 1);
            blit.dstOffsets(1).set(width, height, 1);
            vkCmdBlitImage(command, shared.image(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    images[imageIndex], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, blit, VK_FILTER_NEAREST);
            imageBarrier(command, images[imageIndex], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                    VK_ACCESS_TRANSFER_WRITE_BIT, 0, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, stack);
            shared.release(command, family);
            check(vkEndCommandBuffer(command), "end presentation command");
            check(vkResetFences(device, fences[frame]), "reset presentation fence");
            check(vkQueueSubmit(DeviceManager.getGraphicsQueue().queue(), VkSubmitInfo.calloc(stack).sType$Default()
                    .waitSemaphoreCount(2).pWaitSemaphores(stack.longs(available[frame], shared.readyForVulkan()))
                    .pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT))
                    .pCommandBuffers(stack.pointers(command))
                    .pSignalSemaphores(stack.longs(presentReady[imageIndex], shared.readyForGl())), fences[frame]), "submit client Vulkan presentation");
            if (checkOutput) outputChecks[frame].submitted();
            int result = vkQueuePresentKHR(DeviceManager.getPresentQueue().queue(), VkPresentInfoKHR.calloc(stack).sType$Default()
                    .pWaitSemaphores(stack.longs(presentReady[imageIndex])).swapchainCount(1)
                    .pSwapchains(stack.longs(swapchain)).pImageIndices(index));
            if (result == VK_ERROR_OUT_OF_DATE_KHR || result == VK_SUBOPTIMAL_KHR) dirty = true;
            else check(result, "present client Vulkan image");
            if (++presented == 120) Initializer.LOGGER.info("AFV_ZINK_CLIENT_PRESENT_PASS: 120 real GL -> shared GPU image -> AFV Vulkan WSI frames, {}x{}", width, height);
            frame = (frame + 1) % FRAMES;
        }
    }

    private boolean recreate(int requestedWidth, int requestedHeight) {
        try (var stack = MemoryStack.stackPush()) {
            var surface = DeviceManager.querySurfaceProperties(DeviceManager.physicalDevice, stack);
            var caps = surface.capabilities;
            var extent = PresentationSurface.extent(caps, requestedWidth, requestedHeight);
            if (!extent.drawable()) { dirty = true; return false; }
            if ((caps.supportedUsageFlags() & VK_IMAGE_USAGE_TRANSFER_DST_BIT) == 0)
                throw new IllegalStateException("Surface cannot receive the shared client image");
            var color = PresentationSurface.colorFormat(DeviceManager.physicalDevice, surface.formats);
            int format = color.surface(), sharedFormat = color.shared();
            glFinish(); // Resize/teardown only; no work or destruction for a suspended surface.
            check(vkDeviceWaitIdle(device), "drain presentation before resize");
            destroySwapchain();
            width = extent.width(); height = extent.height();
            int count = caps.minImageCount() + 1;
            if (caps.maxImageCount() > 0) count = Math.min(count, caps.maxImageCount());
            int mode = VK_PRESENT_MODE_FIFO_KHR;
            if (!vsync) for (int i = 0; i < surface.presentModes.remaining(); i++) {
                int candidate = surface.presentModes.get(i);
                if (candidate == VK_PRESENT_MODE_MAILBOX_KHR) { mode = candidate; break; }
                if (candidate == VK_PRESENT_MODE_IMMEDIATE_KHR) mode = candidate;
            }
            int alpha = Integer.lowestOneBit(caps.supportedCompositeAlpha());
            var info = VkSwapchainCreateInfoKHR.calloc(stack).sType$Default().surface(Vulkan.getSurface())
                    .minImageCount(count).imageFormat(format).imageColorSpace(color.colorSpace()).imageArrayLayers(1)
                    .imageUsage(VK_IMAGE_USAGE_TRANSFER_DST_BIT).preTransform(caps.currentTransform())
                    .compositeAlpha(alpha).presentMode(mode).clipped(true);
            info.imageExtent().set(width, height);
            var families = Queue.getQueueFamilies();
            if (families.graphicsFamily != families.presentFamily)
                info.imageSharingMode(VK_SHARING_MODE_CONCURRENT).pQueueFamilyIndices(stack.ints(families.graphicsFamily, families.presentFamily));
            else info.imageSharingMode(VK_SHARING_MODE_EXCLUSIVE);
            var handle = stack.mallocLong(1);
            int created = vkCreateSwapchainKHR(device, info, null, handle);
            if (created == VK_ERROR_OUT_OF_DATE_KHR) { dirty = true; return false; }
            check(created, "create client swapchain");
            swapchain = handle.get(0);
            var n = stack.mallocInt(1);
            check(vkGetSwapchainImagesKHR(device, swapchain, n, null), "count client swapchain images");
            var handles = stack.mallocLong(n.get(0));
            check(vkGetSwapchainImagesKHR(device, swapchain, n, handles), "get client swapchain images");
            images = new long[n.get(0)]; handles.get(images);
            // Per-image semaphores are reused only after WSI reacquires that image.
            presentReady = new long[images.length];
            for (int i = 0; i < images.length; i++) presentReady[i] = semaphore(stack);
            for (int i = 0; i < FRAMES; i++) {
                frames[i] = new SharedImage(DeviceManager.physicalDevice, device, width, height, sharedFormat);
                if (outputChecks != null) outputChecks[i] = new FinalOutputReadback();
            }
            var command = commands[0];
            check(vkResetCommandBuffer(command, 0), "reset initial ownership command");
            check(vkBeginCommandBuffer(command, VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "begin initial ownership");
            for (var image : frames) image.release(command, family);
            check(vkEndCommandBuffer(command), "end initial ownership");
            check(vkQueueSubmit(DeviceManager.getGraphicsQueue().queue(), VkSubmitInfo.calloc(stack).sType$Default()
                    .pCommandBuffers(stack.pointers(command)).pSignalSemaphores(stack.longs(frames[0].readyForGl(), frames[1].readyForGl())), 0), "release initial client images");
            check(vkQueueWaitIdle(DeviceManager.getGraphicsQueue().queue()), "complete initial ownership");
            dirty = false;
            windowWidth = requestedWidth; windowHeight = requestedHeight;
            Initializer.LOGGER.info("AFV_ZINK_CLIENT_SWAPCHAIN: {}x{}, format={}, images={}, vsync={}", width, height, format, images.length, vsync);
            return true;
        }
    }

    private long semaphore(MemoryStack stack) {
        var handle = stack.mallocLong(1);
        check(vkCreateSemaphore(device, VkSemaphoreCreateInfo.calloc(stack).sType$Default(), null, handle), "client presentation semaphore");
        return handle.get(0);
    }
    private void drawFinalMarker() {
        try (var state = new GlStateScope()) {
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
            glDisable(GL_FRAMEBUFFER_SRGB);
            glColorMask(true, true, true, true);
            glEnable(GL_SCISSOR_TEST);
            glScissor(context.width() - 24, context.height() - 24, 8, 8);
            glClearColor(1, 0, 1, 1);
            glClear(GL_COLOR_BUFFER_BIT);
        }
    }
    private static void imageBarrier(VkCommandBuffer command, long image, int oldLayout, int newLayout,
                                     int srcAccess, int dstAccess, int srcStage, int dstStage, MemoryStack stack) {
        var barrier = VkImageMemoryBarrier.calloc(1, stack).sType$Default().image(image).oldLayout(oldLayout).newLayout(newLayout)
                .srcAccessMask(srcAccess).dstAccessMask(dstAccess).srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
        barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
        vkCmdPipelineBarrier(command, srcStage, dstStage, 0, null, null, barrier);
    }
    private void destroySwapchain() {
        if (outputChecks != null) for (int i = 0; i < FRAMES; i++) if (outputChecks[i] != null) {
            if (outputChecks[i].verify()) verifiedOutput++;
            outputChecks[i].close(); outputChecks[i] = null;
        }
        for (int i = 0; i < FRAMES; i++) if (frames[i] != null) { frames[i].close(); frames[i] = null; }
        if (presentReady != null) for (long semaphore : presentReady) if (semaphore != 0) vkDestroySemaphore(device, semaphore, null);
        presentReady = null;
        if (swapchain != 0) vkDestroySwapchainKHR(device, swapchain, null);
        swapchain = 0;
    }
    @Override public void close() {
        if (closed) return;
        context.assertCurrentThread();
        glFinish();
        vkDeviceWaitIdle(device);
        closed = true;
        destroySwapchain();
        for (int i = 0; i < FRAMES; i++) {
            if (available[i] != 0) vkDestroySemaphore(device, available[i], null);
            if (fences[i] != 0) vkDestroyFence(device, fences[i], null);
        }
        // Commands belong to Vulkan's presentation pool and are freed with it.
    }
}
