package net.vulkanmod.vulkan.interop;

import dev.allforvulkan.bridge.GlStateScope;
import dev.allforvulkan.bridge.GlBlitScope;
import dev.allforvulkan.bridge.PresentationSurface;
import dev.allforvulkan.bridge.PresentationTransfer;
import dev.allforvulkan.bridge.FramePresentDispatcher;
import dev.allforvulkan.bridge.SharedImage;
import dev.allforvulkan.bridge.SurfaceFailure;
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
    private final boolean framePipeline = Boolean.getBoolean("afv.experimentalFramePipeline");
    private final int frameSlots = framePipeline ? 3 : 2;
    private final int pipelineDepth = framePipeline ? Math.max(1, Math.min(frameSlots, Integer.getInteger("afv.experimentalPipelineDepth", 1))) : 0;
    private final ZinkContext context;
    private final long window;
    private final VkDevice device = Vulkan.getVkDevice();
    private final int family = Queue.getQueueFamilies().graphicsFamily;
    private final SharedImage[] frames = new SharedImage[frameSlots];
    private final FramePresentDispatcher presentation = new FramePresentDispatcher(framePipeline || Boolean.getBoolean("afv.experimentalAsyncPresent"), framePipeline ? pipelineDepth : 1);
    private VkCommandBuffer setupCommand;
    private VkCommandBuffer[] transferCommands, returnCommands;
    private boolean[] commandSubmitted;
    private int commandVariants;
    private final long[] available = new long[frameSlots], fences = new long[frameSlots];
    private final FinalOutputReadback[] outputChecks = Boolean.getBoolean("afv.smokeFinalOutput") ? new FinalOutputReadback[frameSlots] : null;
    private long swapchain;
    private long[] images, presentReady;
    private int width, height, frame;
    private int windowWidth, windowHeight;
    private boolean vsync = true, dirty = true, closed;
    private boolean directCopy;
    private long presented;
    private final java.util.concurrent.atomic.AtomicLong verifiedOutput = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong reusedTransfers = new java.util.concurrent.atomic.AtomicLong();
    private long recordedTransfers, producedFrames, droppedFrames;
    private int transferGenerations;
    private int producerErrors;
    private boolean surfaceLost;
    private int surfaceAttempts, recoveredSurfaces;

    public ZinkPresenter(ZinkContext context, long window) {
        this.context = context;
        this.window = window;
        try (var stack = MemoryStack.stackPush()) {
            var pointers = stack.mallocPointer(1);
            check(vkAllocateCommandBuffers(device, VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                    .commandPool(Vulkan.getCommandPool()).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1), pointers), "allocate presentation setup command");
            setupCommand = new VkCommandBuffer(pointers.get(0), device);
            var handle = stack.mallocLong(1);
            for (int i = 0; i < frameSlots; i++) {
                available[i] = semaphore(stack);
                check(vkCreateFence(device, VkFenceCreateInfo.calloc(stack).sType$Default().flags(VK_FENCE_CREATE_SIGNALED_BIT), null, handle), "presentation fence");
                fences[i] = handle.get(0);
            }
        } catch (Throwable failure) { close(); throw failure; }
    }

    public void setVsync(boolean enabled) { if (vsync != enabled) { vsync = enabled; dirty = true; } }
    public long presentedFrames() { if (framePipeline) collectCompleted(); return presented; }
    public long verifiedFinalOutputFrames() { return verifiedOutput.get(); }
    public long reusedTransferFrames() { return reusedTransfers.get(); }
    public long recordedTransferCommands() { return recordedTransfers; }
    public int recoveredSurfaces() { return recoveredSurfaces; }
    public boolean asynchronousPresentation() { return presentation.asynchronous(); }
    public boolean framePipeline() { return framePipeline; }
    public int pipelineDepth() { return pipelineDepth; }
    public long droppedFrames() { if (framePipeline) collectCompleted(); return droppedFrames; }
    public int pendingFrames() { return presentation.pendingCount(); }
    /** Explicit lifecycle barrier; normal rendering drains at the next present. */
    public void awaitPresentation() {
        context.assertCurrentThread();
        while (presentation.pendingCount() != 0) completeOne();
    }
    private void collectCompleted() {
        while (presentation.completionReady()) completeOne();
    }
    private void completeOne() {
        Integer result = presentation.complete();
        if (result == null) return;
        if (result == VK_TIMEOUT || result == VK_NOT_READY) { droppedFrames++; return; }
        if (result == VK_ERROR_SURFACE_LOST_KHR) { if (framePipeline) droppedFrames++; surfaceLost = dirty = true; return; }
        if (result == VK_ERROR_OUT_OF_DATE_KHR) { if (framePipeline) droppedFrames++; dirty = true; return; }
        if (result == VK_SUBOPTIMAL_KHR) dirty = true;
        else check(result, "present client Vulkan image");
        surfaceAttempts = 0;
        if (++presented == 120) Initializer.LOGGER.info("AFV_ZINK_CLIENT_PRESENT_PASS: 120 real GL -> shared GPU image -> AFV Vulkan WSI frames, {}x{}", width, height);
    }
    /** Explicit QA request exercises the same real surface replacement as loss. */
    public void requestSurfaceRecreation() {
        context.assertCurrentThread();
        surfaceLost = dirty = true;
    }

    public void present() {
        context.assertCurrentThread();
        // The previous native call may overlap the GL rendering that just
        // finished, but never another AFV queue operation or swapchain access.
        if (framePipeline) {
            collectCompleted();
            // Bound CPU look-ahead independently of the GPU image ring. A deep
            // queue can increase throughput while bunching producer frames.
            if (presentation.pendingCount() == pipelineDepth) completeOne();
        } else awaitPresentation();
        // A producer's sticky GL error must not be attributed to FD import or
        // semaphore submission. Report it separately, with bounded logging.
        for (int error, n = 0; n < 16 && (error = glGetError()) != GL_NO_ERROR; n++) {
            if (++producerErrors <= 8) Initializer.LOGGER.warn("AFV_ZINK_CLIENT_PRODUCER_ERROR: GL 0x{} before presentation", Integer.toHexString(error));
        }
        try (var stack = MemoryStack.stackPush()) {
            var w = stack.mallocInt(1); var h = stack.mallocInt(1);
            org.lwjgl.glfw.GLFW.glfwGetFramebufferSize(window, w, h);
            if (w.get(0) == 0 || h.get(0) == 0 || org.lwjgl.glfw.GLFW.glfwGetWindowAttrib(window, org.lwjgl.glfw.GLFW.GLFW_ICONIFIED) != 0) {
                if (framePipeline) awaitPresentation();
                dirty = true; return;
            }
            try {
                if (framePipeline && (surfaceLost || dirty || windowWidth != w.get(0) || windowHeight != h.get(0))) awaitPresentation();
                if (surfaceLost) recoverSurface();
                if ((dirty || windowWidth != w.get(0) || windowHeight != h.get(0)) && !recreate(w.get(0), h.get(0))) return;
            } catch (SurfaceFailure failure) {
                if (failure.result() == VK_ERROR_SURFACE_LOST_KHR) { surfaceLost = dirty = true; return; }
                if (failure.result() == VK_ERROR_OUT_OF_DATE_KHR || failure.result() == VK_INCOMPLETE) { dirty = true; return; }
                throw failure;
            }
            if (framePipeline) {
                int slot = frame;
                var shared = frames[slot];
                boolean checkOutput = shouldCheckOutput(producedFrames);
                copyClientOutput(slot, checkOutput);
                // SharedImage is GL-thread confined. Capture native values here;
                // the consumer never calls its GL/ownership-mutating methods.
                long toVulkan = shared.readyForVulkan(), toGl = shared.readyForGl(), target = swapchain;
                var graphics = DeviceManager.getGraphicsQueue().queue();
                var present = DeviceManager.getPresentQueue().queue();
                presentation.submit(() -> transferAndPresent(slot, checkOutput, toVulkan, toGl, graphics, present, target));
                producedFrames++;
                frame = (frame + 1) % frameSlots;
                return;
            }
            check(vkWaitForFences(device, fences[frame], true, -1L), "wait presentation slot");
            if (outputChecks != null && outputChecks[frame].verify()) verifiedOutput.incrementAndGet();
            var index = stack.mallocInt(1);
            // Keep event processing alive if an occluded/minimized surface stops making progress.
            int acquired = vkAcquireNextImageKHR(device, swapchain, 100_000_000L, available[frame], 0, index);
            if (acquired == VK_TIMEOUT || acquired == VK_NOT_READY) return;
            if (acquired == VK_ERROR_SURFACE_LOST_KHR) { surfaceLost = dirty = true; return; }
            if (acquired == VK_ERROR_OUT_OF_DATE_KHR) { dirty = true; return; }
            if (acquired != VK_SUBOPTIMAL_KHR) check(acquired, "acquire presentation image");
            else dirty = true;
            int imageIndex = index.get(0);
            var shared = frames[frame];
            // Alternate whole slot batches in the opt-in diagnostic, so every
            // shared image exercises both normal and readback commands.
            boolean checkOutput = shouldCheckOutput(presented);
            copyClientOutput(frame, checkOutput);

            // The slot fence above covers every command variant referencing this
            // shared image, so an executable command is never resubmitted pending.
            int commandIndex = (frame * images.length + imageIndex) * commandVariants + (checkOutput ? 1 : 0);
            var command = transferCommands[commandIndex];
            check(vkResetFences(device, fences[frame]), "reset presentation fence");
            check(vkQueueSubmit(DeviceManager.getGraphicsQueue().queue(), VkSubmitInfo.calloc(stack).sType$Default()
                    .waitSemaphoreCount(2).pWaitSemaphores(stack.longs(available[frame], shared.readyForVulkan()))
                    .pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT))
                    .pCommandBuffers(stack.pointers(command))
                    .pSignalSemaphores(stack.longs(presentReady[imageIndex], shared.readyForGl())), fences[frame]), "submit client Vulkan presentation");
            if (commandSubmitted[commandIndex]) reusedTransfers.incrementAndGet();
            commandSubmitted[commandIndex] = true;
            if (checkOutput) outputChecks[frame].submitted();
            var queue = DeviceManager.getPresentQueue().queue();
            long target = swapchain, ready = presentReady[imageIndex];
            presentation.submit(() -> presentNative(queue, target, ready, imageIndex));
            if (!presentation.asynchronous()) awaitPresentation();
            frame = (frame + 1) % frameSlots;
        }
    }

    private boolean shouldCheckOutput(long sequence) {
        return outputChecks != null && ((sequence / frameSlots) & 1) == 0
                && width == context.width() && height == context.height() && width >= 32 && height >= 32;
    }
    private void copyClientOutput(int slot, boolean checkOutput) {
        if (checkOutput) drawFinalMarker();
        try (var state = new GlBlitScope(context.defaultReadBuffer())) {
            int destination = frames[slot].beginGlCopy();
            PresentationTransfer.copyGl(0, destination, context.width(), context.height(), width, height, directCopy);
            int error = glGetError();
            if (error != GL_NO_ERROR) throw new IllegalStateException("Client color transfer: GL error 0x" + Integer.toHexString(error));
            frames[slot].endGlWrite();
        }
    }

    /** Serialized consumer: acquire, transfer and present all run on this worker. */
    private int transferAndPresent(int slot, boolean checkOutput, long toVulkan, long toGl,
                                   VkQueue graphics, VkQueue present, long target) {
        boolean consumed = false;
        try (var stack = MemoryStack.stackPush()) {
            check(vkWaitForFences(device, fences[slot], true, -1L), "wait transfer-worker slot");
            if (outputChecks != null && outputChecks[slot].verify()) verifiedOutput.incrementAndGet();
            var index = stack.mallocInt(1);
            int acquired = vkAcquireNextImageKHR(device, target, 100_000_000L, available[slot], 0, index);
            if (acquired == VK_TIMEOUT || acquired == VK_NOT_READY || acquired == VK_ERROR_OUT_OF_DATE_KHR || acquired == VK_ERROR_SURFACE_LOST_KHR) {
                returnSlot(slot, toVulkan, toGl, graphics, stack);
                consumed = true;
                return acquired;
            }
            if (acquired != VK_SUBOPTIMAL_KHR) check(acquired, "transfer-worker acquire");
            int imageIndex = index.get(0);
            int commandIndex = (slot * images.length + imageIndex) * commandVariants + (checkOutput ? 1 : 0);
            check(vkResetFences(device, fences[slot]), "reset transfer-worker fence");
            check(vkQueueSubmit(graphics, VkSubmitInfo.calloc(stack).sType$Default().waitSemaphoreCount(2)
                    .pWaitSemaphores(stack.longs(available[slot], toVulkan))
                    .pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT))
                    .pCommandBuffers(stack.pointers(transferCommands[commandIndex]))
                    .pSignalSemaphores(stack.longs(presentReady[imageIndex], toGl)), fences[slot]), "submit transfer-worker frame");
            consumed = true;
            if (commandSubmitted[commandIndex]) reusedTransfers.incrementAndGet();
            commandSubmitted[commandIndex] = true;
            if (checkOutput) outputChecks[slot].submitted();
            int result = presentNative(present, target, presentReady[imageIndex], imageIndex);
            return result == VK_SUCCESS && acquired == VK_SUBOPTIMAL_KHR ? VK_SUBOPTIMAL_KHR : result;
        } catch (RuntimeException | LinkageError failure) {
            if (!consumed) try (var stack = MemoryStack.stackPush()) {
                returnSlot(slot, toVulkan, toGl, graphics, stack);
            } catch (RuntimeException | LinkageError cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    /** Even an unavailable WSI image must consume the GL signal and return the
     * shared slot, otherwise the next GL ring traversal cannot make progress. */
    private void returnSlot(int slot, long toVulkan, long toGl, VkQueue graphics, MemoryStack stack) {
        check(vkResetFences(device, fences[slot]), "reset unpresented slot fence");
        check(vkQueueSubmit(graphics, VkSubmitInfo.calloc(stack).sType$Default().waitSemaphoreCount(1)
                .pWaitSemaphores(stack.longs(toVulkan)).pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_TRANSFER_BIT))
                .pCommandBuffers(stack.pointers(returnCommands[slot])).pSignalSemaphores(stack.longs(toGl)), fences[slot]), "return unpresented GL slot");
    }

    private static int presentNative(VkQueue queue, long swapchain, long ready, int imageIndex) {
        // Every pointer belongs to the executing thread's stack; no render-thread
        // IntBuffer or VkPresentInfo escapes into the asynchronous request.
        try (var stack = MemoryStack.stackPush()) {
            return vkQueuePresentKHR(queue, VkPresentInfoKHR.calloc(stack).sType$Default()
                    .pWaitSemaphores(stack.longs(ready)).swapchainCount(1)
                    .pSwapchains(stack.longs(swapchain)).pImageIndices(stack.ints(imageIndex)));
        }
    }

    private boolean recreate(int requestedWidth, int requestedHeight) {
        try (var stack = MemoryStack.stackPush(); var state = new GlStateScope()) {
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
            directCopy = color.directCopy();
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
            if (created == VK_ERROR_SURFACE_LOST_KHR) { surfaceLost = dirty = true; return false; }
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
            for (int i = 0; i < frameSlots; i++) {
                frames[i] = SharedImage.forGlCopy(DeviceManager.physicalDevice, device, width, height, sharedFormat);
                if (outputChecks != null) outputChecks[i] = new FinalOutputReadback();
            }
            var command = setupCommand;
            check(vkResetCommandBuffer(command, 0), "reset initial ownership command");
            check(vkBeginCommandBuffer(command, VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "begin initial ownership");
            for (var image : frames) image.release(command, family);
            check(vkEndCommandBuffer(command), "end initial ownership");
            var initialSignals = stack.mallocLong(frameSlots);
            for (var image : frames) initialSignals.put(image.readyForGl());
            initialSignals.flip();
            check(vkQueueSubmit(DeviceManager.getGraphicsQueue().queue(), VkSubmitInfo.calloc(stack).sType$Default()
                    .pCommandBuffers(stack.pointers(command)).pSignalSemaphores(initialSignals), 0), "release initial client images");
            check(vkQueueWaitIdle(DeviceManager.getGraphicsQueue().queue()), "complete initial ownership");
            recordTransferCommands(stack);
            dirty = false;
            windowWidth = requestedWidth; windowHeight = requestedHeight;
            Initializer.LOGGER.info("AFV_ZINK_CLIENT_SWAPCHAIN: {}x{}, format={}, images={}, vsync={}, sharedLayout=transfer, transfer={}, asyncPresent={}", width, height, format, images.length, vsync, directCopy ? "copy" : "blit", presentation.asynchronous());
            if (framePipeline) Initializer.LOGGER.info("AFV_FRAME_PIPELINE: slots={}, depth={}, acquire/transfer/present worker, bounded FIFO", frameSlots, pipelineDepth);
            return true;
        }
    }

    private void recoverSurface() {
        if (++surfaceAttempts > 3) throw new IllegalStateException("Vulkan surface recovery failed after 3 attempts");
        try (var stack = MemoryStack.stackPush(); var state = new GlStateScope()) {
            glFinish();
            check(vkDeviceWaitIdle(device), "drain presentation before surface replacement");
            destroySwapchain();
            Vulkan.recreatePresentationSurface(window);
            var supported = stack.mallocInt(1);
            SurfaceFailure.check(vkGetPhysicalDeviceSurfaceSupportKHR(DeviceManager.physicalDevice,
                    Queue.getQueueFamilies().presentFamily, Vulkan.getSurface(), supported), "check replacement surface queue");
            if (supported.get(0) != VK_TRUE) throw new IllegalStateException("Existing Vulkan device cannot present to the replacement surface");
            // Acquisition semaphores belong to the old WSI generation too.
            for (int i = 0; i < frameSlots; i++) {
                if (available[i] != 0) vkDestroySemaphore(device, available[i], null);
                available[i] = 0;
                available[i] = semaphore(stack);
            }
            frame = 0;
            surfaceLost = false;
            dirty = true;
            Initializer.LOGGER.info("AFV_ZINK_SURFACE_RECREATED: generation={}, same Vulkan device and GL context", ++recoveredSurfaces);
        }
    }

    /** Record immutable transfers once per swapchain generation, including an
     * optional readback variant so diagnostics exercise the same reusable path. */
    private void recordTransferCommands(MemoryStack stack) {
        commandVariants = outputChecks != null && width >= 32 && height >= 32 ? 2 : 1;
        int count = frameSlots * images.length * commandVariants;
        var pointers = stack.mallocPointer(count);
        check(vkAllocateCommandBuffers(device, VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                .commandPool(Vulkan.getCommandPool()).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(count), pointers), "allocate reusable presentation commands");
        transferCommands = new VkCommandBuffer[count];
        commandSubmitted = new boolean[count];
        for (int i = 0; i < count; i++) transferCommands[i] = new VkCommandBuffer(pointers.get(i), device);
        for (int slot = 0; slot < frameSlots; slot++) for (int image = 0; image < images.length; image++) {
            for (int variant = 0; variant < commandVariants; variant++) {
                recordTransferCommand(transferCommands[(slot * images.length + image) * commandVariants + variant], slot, image, variant != 0);
            }
        }
        recordedTransfers += count;
        if (framePipeline) {
            var returned = stack.mallocPointer(frameSlots);
            check(vkAllocateCommandBuffers(device, VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                    .commandPool(Vulkan.getCommandPool()).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(frameSlots), returned), "allocate slot return commands");
            returnCommands = new VkCommandBuffer[frameSlots];
            for (int slot = 0; slot < frameSlots; slot++) {
                var command = returnCommands[slot] = new VkCommandBuffer(returned.get(slot), device);
                check(vkBeginCommandBuffer(command, VkCommandBufferBeginInfo.calloc(stack).sType$Default()), "begin slot return");
                frames[slot].acquire(command, family);
                frames[slot].release(command, family);
                check(vkEndCommandBuffer(command), "end slot return");
            }
            recordedTransfers += frameSlots;
        }
        Initializer.LOGGER.info("AFV_ZINK_TRANSFER_COMMANDS: generation={}, recorded={}, variants={}, imageDependencyCommands=2", ++transferGenerations, count, commandVariants);
    }

    private void recordTransferCommand(VkCommandBuffer command, int slot, int imageIndex, boolean checkOutput) {
        try (var stack = MemoryStack.stackPush()) {
            // No ONE_TIME_SUBMIT flag: the slot fence permits sequential reuse.
            check(vkBeginCommandBuffer(command, VkCommandBufferBeginInfo.calloc(stack).sType$Default()), "begin reusable presentation command");
            var shared = frames[slot];
            // Both images enter TRANSFER from TOP_OF_PIPE. Preserve each image's
            // access/layout/ownership contract in one dependency command.
            shared.acquire(command, family, imageBarrier(images[imageIndex], VK_IMAGE_LAYOUT_UNDEFINED,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 0, VK_ACCESS_TRANSFER_WRITE_BIT, stack));
            if (checkOutput) outputChecks[slot].record(command, shared.image(), width, height, directCopy, stack);
            PresentationTransfer.record(command, shared.image(), images[imageIndex], width, height, directCopy);
            shared.release(command, family, imageBarrier(images[imageIndex], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    VK_IMAGE_LAYOUT_PRESENT_SRC_KHR, VK_ACCESS_TRANSFER_WRITE_BIT, 0, stack));
            check(vkEndCommandBuffer(command), "end reusable presentation command");
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
    private static VkImageMemoryBarrier imageBarrier(long image, int oldLayout, int newLayout,
                                                     int srcAccess, int dstAccess, MemoryStack stack) {
        var barrier = VkImageMemoryBarrier.calloc(stack).sType$Default().image(image).oldLayout(oldLayout).newLayout(newLayout)
                .srcAccessMask(srcAccess).dstAccessMask(dstAccess).srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
        barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
        return barrier;
    }
    private void destroySwapchain() {
        // Caller has drained the device. Free executable references before their
        // images/readback buffers, and never retain a command across recreation.
        if (transferCommands != null) {
            try (var stack = MemoryStack.stackPush()) {
                var pointers = stack.mallocPointer(transferCommands.length);
                for (var command : transferCommands) pointers.put(command);
                pointers.flip();
                vkFreeCommandBuffers(device, Vulkan.getCommandPool(), pointers);
            }
            transferCommands = null;
            commandSubmitted = null;
        }
        if (returnCommands != null) {
            try (var stack = MemoryStack.stackPush()) {
                var pointers = stack.mallocPointer(returnCommands.length);
                for (var command : returnCommands) pointers.put(command);
                pointers.flip();
                vkFreeCommandBuffers(device, Vulkan.getCommandPool(), pointers);
            }
            returnCommands = null;
        }
        if (outputChecks != null) for (int i = 0; i < frameSlots; i++) if (outputChecks[i] != null) {
            if (outputChecks[i].verify()) verifiedOutput.incrementAndGet();
            outputChecks[i].close(); outputChecks[i] = null;
        }
        for (int i = 0; i < frameSlots; i++) if (frames[i] != null) { frames[i].close(); frames[i] = null; }
        if (presentReady != null) for (long semaphore : presentReady) if (semaphore != 0) vkDestroySemaphore(device, semaphore, null);
        presentReady = null;
        if (swapchain != 0) vkDestroySwapchainKHR(device, swapchain, null);
        swapchain = 0;
    }
    @Override public void close() {
        if (closed) return;
        context.assertCurrentThread();
        Throwable pendingFailure = null;
        try { awaitPresentation(); }
        catch (RuntimeException | LinkageError failure) { pendingFailure = failure; }
        finally { presentation.close(); }
        glFinish();
        vkDeviceWaitIdle(device);
        closed = true;
        destroySwapchain();
        for (int i = 0; i < frameSlots; i++) {
            if (available[i] != 0) vkDestroySemaphore(device, available[i], null);
            if (fences[i] != 0) vkDestroyFence(device, fences[i], null);
        }
        if (setupCommand != null) {
            vkFreeCommandBuffers(device, Vulkan.getCommandPool(), setupCommand);
            setupCommand = null;
        }
        Initializer.LOGGER.info("AFV_PRESENT_DISPATCH_STATS: {}", presentation.statistics());
        if (framePipeline) Initializer.LOGGER.info("AFV_FRAME_PIPELINE_STATS: produced={}, presented={}, dropped={}", producedFrames, presented, droppedFrames);
        if (pendingFailure instanceof RuntimeException runtime) throw runtime;
        if (pendingFailure instanceof LinkageError linkage) throw linkage;
    }
}
