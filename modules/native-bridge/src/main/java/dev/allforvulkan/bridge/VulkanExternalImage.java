package dev.allforvulkan.bridge;

import java.nio.ByteBuffer;
import java.util.HexFormat;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.*;
import static org.lwjgl.vulkan.KHRExternalMemoryFd.*;
import static org.lwjgl.vulkan.KHRExternalSemaphoreFd.*;

/** Linux opaque-FD sharing, with dedicated allocation and explicit queue ownership. */
public final class VulkanExternalImage implements AutoCloseable {
    private final Thread owner = Thread.currentThread();
    private VkInstance instance;
    private VkPhysicalDevice physical;
    private VkDevice device;
    private VkQueue queue;
    private VkCommandBuffer command;
    private int queueFamily;
    private long image, imageMemory, readback, readbackMemory, pool, toGl, fromGl, fence;
    private long allocationSize;
    private int currentLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    private boolean closed;
    private final int width, height;

    public VulkanExternalImage(byte[] glDeviceUuid, int width, int height) {
        if (glDeviceUuid.length != VK_UUID_SIZE || width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid image/device identity");
        this.width = width;
        this.height = height;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var app = VkApplicationInfo.calloc(stack).sType$Default().pApplicationName(stack.UTF8("AFV interop probe")).apiVersion(VK_API_VERSION_1_1);
            var info = VkInstanceCreateInfo.calloc(stack).sType$Default().pApplicationInfo(app);
            PointerBuffer pointer = stack.mallocPointer(1);
            check(vkCreateInstance(info, null, pointer), "vkCreateInstance");
            instance = new VkInstance(pointer.get(0), info);
            var count = stack.ints(0);
            check(vkEnumeratePhysicalDevices(instance, count, null), "enumerate Vulkan GPUs");
            var devices = stack.mallocPointer(count.get(0));
            check(vkEnumeratePhysicalDevices(instance, count, devices), "read Vulkan GPUs");
            for (int i = 0; i < devices.remaining(); ++i) {
                var candidate = new VkPhysicalDevice(devices.get(i), instance);
                var id = VkPhysicalDeviceIDProperties.calloc(stack).sType$Default();
                var properties = VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(id.address());
                vkGetPhysicalDeviceProperties2(candidate, properties);
                byte[] uuid = new byte[VK_UUID_SIZE];
                id.deviceUUID().get(uuid);
                if (java.util.Arrays.equals(uuid, glDeviceUuid)) {
                    physical = candidate;
                    System.out.println("VULKAN_MATCHED_DEVICE=" + properties.properties().deviceNameString());
                    System.out.println("VULKAN_MATCHED_UUID=" + HexFormat.of().formatHex(uuid));
                    break;
                }
            }
            if (physical == null) throw new IllegalStateException("Zink and Vulkan physical-device UUIDs do not match");
            probeExternalSupport(stack);
            vkGetPhysicalDeviceQueueFamilyProperties(physical, count, null);
            var families = VkQueueFamilyProperties.calloc(count.get(0), stack);
            vkGetPhysicalDeviceQueueFamilyProperties(physical, count, families);
            queueFamily = -1;
            for (int i = 0; i < families.remaining(); i++) if ((families.get(i).queueFlags() & VK_QUEUE_GRAPHICS_BIT) != 0) { queueFamily = i; break; }
            if (queueFamily < 0) throw new IllegalStateException("No graphics queue");
            var queues = VkDeviceQueueCreateInfo.calloc(1, stack).sType$Default().queueFamilyIndex(queueFamily).pQueuePriorities(stack.floats(1));
            var createDevice = VkDeviceCreateInfo.calloc(stack).sType$Default().pQueueCreateInfos(queues)
                    .ppEnabledExtensionNames(stack.pointers(stack.UTF8(VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME), stack.UTF8(VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME)));
            check(vkCreateDevice(physical, createDevice, null, pointer), "vkCreateDevice");
            device = new VkDevice(pointer.get(0), physical, createDevice);
            vkGetDeviceQueue(device, queueFamily, 0, pointer);
            queue = new VkQueue(pointer.get(0), device);
            var handle = stack.mallocLong(1);
            check(vkCreateCommandPool(device, VkCommandPoolCreateInfo.calloc(stack).sType$Default()
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT).queueFamilyIndex(queueFamily), null, handle), "command pool");
            pool = handle.get(0);
            check(vkAllocateCommandBuffers(device, VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                    .commandPool(pool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1), pointer), "command buffer");
            command = new VkCommandBuffer(pointer.get(0), device);
            createImages(stack);
            toGl = createSemaphore(stack);
            fromGl = createSemaphore(stack);
            check(vkCreateFence(device, VkFenceCreateInfo.calloc(stack).sType$Default(), null, handle), "fence");
            fence = handle.get(0);
        } catch (Throwable failure) {
            close();
            throw failure;
        }
    }

    private void probeExternalSupport(MemoryStack stack) {
        var external = VkPhysicalDeviceExternalImageFormatInfo.calloc(stack).sType$Default().handleType(VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT);
        var format = VkPhysicalDeviceImageFormatInfo2.calloc(stack).sType$Default().pNext(external.address())
                .format(VK_FORMAT_R8G8B8A8_UNORM).type(VK_IMAGE_TYPE_2D).tiling(VK_IMAGE_TILING_OPTIMAL).usage(imageUsage());
        var output = VkExternalImageFormatProperties.calloc(stack).sType$Default();
        var properties = VkImageFormatProperties2.calloc(stack).sType$Default().pNext(output.address());
        check(vkGetPhysicalDeviceImageFormatProperties2(physical, format, properties), "external RGBA8 optimal image support");
        int flags = output.externalMemoryProperties().externalMemoryFeatures();
        int required = VK_EXTERNAL_MEMORY_FEATURE_EXPORTABLE_BIT | VK_EXTERNAL_MEMORY_FEATURE_IMPORTABLE_BIT;
        if ((flags & required) != required) throw new IllegalStateException("RGBA8 opaque FD export/import is unsupported");
        if (width > properties.imageFormatProperties().maxExtent().width() || height > properties.imageFormatProperties().maxExtent().height())
            throw new IllegalArgumentException("Shared image exceeds device limit");
        var semInfo = VkPhysicalDeviceExternalSemaphoreInfo.calloc(stack).sType$Default().handleType(VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_FD_BIT);
        var semProperties = VkExternalSemaphoreProperties.calloc(stack).sType$Default();
        vkGetPhysicalDeviceExternalSemaphoreProperties(physical, semInfo, semProperties);
        int semFlags = VK_EXTERNAL_SEMAPHORE_FEATURE_EXPORTABLE_BIT | VK_EXTERNAL_SEMAPHORE_FEATURE_IMPORTABLE_BIT;
        if ((semProperties.externalSemaphoreFeatures() & semFlags) != semFlags) throw new IllegalStateException("Opaque FD semaphore sharing is unsupported");
        System.out.println("VULKAN_RGBA8_EXTERNAL_FEATURES=" + flags);
    }

    private static int imageUsage() {
        return VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    }
    private int memoryType(int bits, int flags, MemoryStack stack) {
        var properties = VkPhysicalDeviceMemoryProperties.calloc(stack);
        vkGetPhysicalDeviceMemoryProperties(physical, properties);
        for (int i = 0; i < properties.memoryTypeCount(); ++i)
            if ((bits & (1 << i)) != 0 && (properties.memoryTypes(i).propertyFlags() & flags) == flags) return i;
        throw new IllegalStateException("No compatible memory type for flags " + flags);
    }
    private void createImages(MemoryStack stack) {
        var handle = stack.mallocLong(1);
        var external = VkExternalMemoryImageCreateInfo.calloc(stack).sType$Default().handleTypes(VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT);
        var info = VkImageCreateInfo.calloc(stack).sType$Default().pNext(external.address()).imageType(VK_IMAGE_TYPE_2D)
                .format(VK_FORMAT_R8G8B8A8_UNORM).mipLevels(1).arrayLayers(1).samples(VK_SAMPLE_COUNT_1_BIT)
                .tiling(VK_IMAGE_TILING_OPTIMAL).usage(imageUsage()).sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
        info.extent().set(width, height, 1);
        check(vkCreateImage(device, info, null, handle), "external image");
        image = handle.get(0);
        var requirements = VkMemoryRequirements.calloc(stack);
        vkGetImageMemoryRequirements(device, image, requirements);
        allocationSize = requirements.size();
        var dedicated = VkMemoryDedicatedAllocateInfo.calloc(stack).sType$Default().image(image);
        var export = VkExportMemoryAllocateInfo.calloc(stack).sType$Default().pNext(dedicated.address()).handleTypes(VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT);
        var allocate = VkMemoryAllocateInfo.calloc(stack).sType$Default().pNext(export.address()).allocationSize(allocationSize)
                .memoryTypeIndex(memoryType(requirements.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, stack));
        check(vkAllocateMemory(device, allocate, null, handle), "exportable dedicated memory");
        imageMemory = handle.get(0);
        check(vkBindImageMemory(device, image, imageMemory, 0), "bind external image");
        var buffer = VkBufferCreateInfo.calloc(stack).sType$Default().size((long) width * height * 4)
                .usage(VK_BUFFER_USAGE_TRANSFER_DST_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
        check(vkCreateBuffer(device, buffer, null, handle), "diagnostic readback buffer");
        readback = handle.get(0);
        vkGetBufferMemoryRequirements(device, readback, requirements);
        var hostAllocate = VkMemoryAllocateInfo.calloc(stack).sType$Default().allocationSize(requirements.size())
                .memoryTypeIndex(memoryType(requirements.memoryTypeBits(), VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, stack));
        check(vkAllocateMemory(device, hostAllocate, null, handle), "readback memory");
        readbackMemory = handle.get(0);
        check(vkBindBufferMemory(device, readback, readbackMemory, 0), "bind readback memory");
    }
    private long createSemaphore(MemoryStack stack) {
        var export = VkExportSemaphoreCreateInfo.calloc(stack).sType$Default().handleTypes(VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_FD_BIT);
        var handle = stack.mallocLong(1);
        check(vkCreateSemaphore(device, VkSemaphoreCreateInfo.calloc(stack).sType$Default().pNext(export.address()), null, handle), "exportable semaphore");
        return handle.get(0);
    }
    public long allocationSize() { return allocationSize; }
    public int exportMemoryFd() {
        assertUsable();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var fd = stack.mallocInt(1);
            check(vkGetMemoryFdKHR(device, VkMemoryGetFdInfoKHR.calloc(stack).sType$Default().memory(imageMemory)
                    .handleType(VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT), fd), "export memory FD");
            return fd.get(0);
        }
    }
    public int exportSemaphoreFd(boolean toGlDirection) {
        assertUsable();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var fd = stack.mallocInt(1);
            check(vkGetSemaphoreFdKHR(device, VkSemaphoreGetFdInfoKHR.calloc(stack).sType$Default().semaphore(toGlDirection ? toGl : fromGl)
                    .handleType(VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_FD_BIT), fd), "export semaphore FD");
            return fd.get(0);
        }
    }
    private void begin(MemoryStack stack) {
        check(vkResetCommandBuffer(command, 0), "reset interop command buffer");
        check(vkBeginCommandBuffer(command, VkCommandBufferBeginInfo.calloc(stack).sType$Default().flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "begin interop commands");
    }
    private void barrier(MemoryStack stack, int oldLayout, int newLayout, int srcAccess, int dstAccess,
            int srcQueue, int dstQueue, int srcStage, int dstStage) {
        var barriers = VkImageMemoryBarrier.calloc(1, stack).sType$Default().oldLayout(oldLayout).newLayout(newLayout)
                .srcAccessMask(srcAccess).dstAccessMask(dstAccess).srcQueueFamilyIndex(srcQueue).dstQueueFamilyIndex(dstQueue).image(image);
        barriers.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
        vkCmdPipelineBarrier(command, srcStage, dstStage, 0, null, null, barriers);
    }

    /** Vk queue -> external queue: green test image, then signal the GL acquire semaphore. */
    public void releaseToGl() {
        assertUsable();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            begin(stack);
            barrier(stack, currentLayout, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    currentLayout == VK_IMAGE_LAYOUT_UNDEFINED ? 0 : VK_ACCESS_TRANSFER_READ_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED,
                    currentLayout == VK_IMAGE_LAYOUT_UNDEFINED ? VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT : VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
            var range = VkImageSubresourceRange.calloc(1, stack).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            var green = VkClearColorValue.calloc(stack).float32(0, 0).float32(1, 1).float32(2, 0).float32(3, 1);
            vkCmdClearColorImage(command, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, green, range);
            barrier(stack, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL, VK_ACCESS_TRANSFER_WRITE_BIT, 0,
                    queueFamily, VK_QUEUE_FAMILY_EXTERNAL, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
            check(vkEndCommandBuffer(command), "end release commands");
            var submit = VkSubmitInfo.calloc(stack).sType$Default().pCommandBuffers(stack.pointers(command)).pSignalSemaphores(stack.longs(toGl));
            check(vkResetFences(device, fence), "reset release fence");
            check(vkQueueSubmit(queue, submit, fence), "submit release");
            // This probe serializes reuse of its one command buffer. Cross-device
            // image visibility is guaranteed by external semaphores, not this fence.
            awaitFence();
            currentLayout = VK_IMAGE_LAYOUT_GENERAL;
        }
    }
    /** External queue -> Vk queue: wait for GL, acquire ownership, verify diagnostic copy. */
    public void acquireFromGlAndVerify() {
        assertUsable();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            begin(stack);
            barrier(stack, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, 0, VK_ACCESS_TRANSFER_READ_BIT,
                    VK_QUEUE_FAMILY_EXTERNAL, queueFamily, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
            recordReadback(stack, image);
            check(vkEndCommandBuffer(command), "end acquire commands");
            var submit = VkSubmitInfo.calloc(stack).sType$Default().pCommandBuffers(stack.pointers(command))
                    .waitSemaphoreCount(1).pWaitSemaphores(stack.longs(fromGl)).pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_TRANSFER_BIT));
            check(vkResetFences(device, fence), "reset acquire fence");
            check(vkQueueSubmit(queue, submit, fence), "submit acquire");
            awaitFence();
            currentLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
            verifyReadback(255, 0, 255);
        }
    }
    private void recordReadback(MemoryStack stack, long sourceImage) {
        var copy = VkBufferImageCopy.calloc(1, stack);
        copy.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
        copy.imageExtent().set(width, height, 1);
        vkCmdCopyImageToBuffer(command, sourceImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, readback, copy);
        var hostBarrier = VkBufferMemoryBarrier.calloc(1, stack).sType$Default().srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                .dstAccessMask(VK_ACCESS_HOST_READ_BIT).srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .buffer(readback).offset(0).size(VK_WHOLE_SIZE);
        vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, null, hostBarrier, null);
    }
    private void verifyReadback(int red, int green, int blue) {
        try (var stack = MemoryStack.stackPush()) {
            var mapped = stack.mallocPointer(1);
            check(vkMapMemory(device, readbackMemory, 0, (long) width * height * 4, 0, mapped), "map diagnostic readback");
            try {
                ByteBuffer bytes = org.lwjgl.system.MemoryUtil.memByteBuffer(mapped.get(0), width * height * 4);
                for (int i = 0; i < bytes.remaining(); i += 4)
                    if ((bytes.get(i) & 255) != red || (bytes.get(i + 1) & 255) != green
                            || (bytes.get(i + 2) & 255) != blue || (bytes.get(i + 3) & 255) != 255)
                        throw new IllegalStateException("Vulkan did not see the expected Zink color at pixel " + i / 4);
            } finally { vkUnmapMemory(device, readbackMemory); }
        }
    }
    /** Exercise the production copy-only image, including implicit signal flush,
     * external ownership, changing bytes and reuse across multiple submissions. */
    void verifySharedCopy() {
        verifySharedCopy(false);
        verifySharedCopy(true);
    }
    private void verifySharedCopy(boolean copyOnly) {
        assertUsable();
        try (var state = new GlStateScope(); var stack = MemoryStack.stackPush()) {
            var shared = copyOnly ? SharedImage.forGlCopy(physical, device, width, height, VK_FORMAT_R8G8B8A8_UNORM)
                    : new SharedImage(physical, device, width, height, VK_FORMAT_R8G8B8A8_UNORM);
            try {
                org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_SCISSOR_TEST);
                org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_SRGB);
                org.lwjgl.opengl.GL11.glColorMask(true, true, true, true);
                begin(stack);
                shared.release(command, queueFamily);
                check(vkEndCommandBuffer(command), "end copy initial release");
                check(vkResetFences(device, fence), "reset copy initial release fence");
                check(vkQueueSubmit(queue, VkSubmitInfo.calloc(stack).sType$Default()
                        .pCommandBuffers(stack.pointers(command)).pSignalSemaphores(stack.longs(shared.readyForGl())), fence), "submit copy initial release");
                awaitFence();
                for (int frame = 0; frame < 8; frame++) {
                    int red = frame * 17, green = 255 - red, blue = (frame ^ 1) * 11;
                    int framebuffer = shared.beginGlCopy();
                    org.lwjgl.opengl.GL45.glClearNamedFramebufferfv(framebuffer, org.lwjgl.opengl.GL11.GL_COLOR, 0,
                            stack.floats(red / 255f, green / 255f, blue / 255f, 1f));
                    shared.endGlWrite(); // No explicit glFlush here or in SharedImage.
                    begin(stack);
                    shared.acquire(command, queueFamily);
                    recordReadback(stack, shared.image());
                    shared.release(command, queueFamily);
                    check(vkEndCommandBuffer(command), "end copy acquire/release");
                    check(vkResetFences(device, fence), "reset copy acquire/release fence");
                    check(vkQueueSubmit(queue, VkSubmitInfo.calloc(stack).sType$Default()
                            .pCommandBuffers(stack.pointers(command)).waitSemaphoreCount(1)
                            .pWaitSemaphores(stack.longs(shared.readyForVulkan()))
                            .pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_TRANSFER_BIT))
                            .pSignalSemaphores(stack.longs(shared.readyForGl())), fence), "submit copy acquire/release");
                    awaitFence();
                    verifyReadback(red, green, blue);
                }
                System.out.println((copyOnly ? "AFV_SHARED_COPY_LAYOUT_PASS: TRANSFER_DST -> GL -> TRANSFER_SRC"
                        : "AFV_SHARED_GENERAL_PASS: GENERAL -> GL -> GENERAL")
                        + ", 8 changing frames, implicit signal flush, size=" + width + "x" + height);
            } finally {
                org.lwjgl.opengl.GL11.glFinish(); // Diagnostic teardown only.
                vkDeviceWaitIdle(device);
                shared.close();
            }
        }
    }
    /** Exercise both production transfer strategies even when WSI offers only BGRA.
     * The existing owned RGBA image is the private destination, never a screen. */
    void verifyPresentationTransfer() {
        verifyPresentationTransfer(true);
        verifyPresentationTransfer(false);
    }
    private void verifyPresentationTransfer(boolean directCopy) {
        assertUsable();
        int sourceTexture = 0, sourceFramebuffer = 0;
        try (var state = new GlStateScope(); var stack = MemoryStack.stackPush();
             var shared = SharedImage.forGlCopy(physical, device, width, height, VK_FORMAT_R8G8B8A8_UNORM)) {
            try {
                sourceTexture = org.lwjgl.opengl.GL11.glGenTextures();
                org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, sourceTexture);
                org.lwjgl.opengl.GL11.glTexImage2D(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL11.GL_RGBA8,
                        width, height, 0, org.lwjgl.opengl.GL11.GL_RGBA, org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
                sourceFramebuffer = org.lwjgl.opengl.GL30.glGenFramebuffers();
                org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, sourceFramebuffer);
                org.lwjgl.opengl.GL30.glFramebufferTexture2D(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER,
                        org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0, org.lwjgl.opengl.GL11.GL_TEXTURE_2D, sourceTexture, 0);
                if (org.lwjgl.opengl.GL30.glCheckFramebufferStatus(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER) != org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_COMPLETE)
                    throw new IllegalStateException("Presentation transfer source FBO incomplete");
                org.lwjgl.opengl.GL30.glDisablei(org.lwjgl.opengl.GL11.GL_SCISSOR_TEST, 0);
                org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_SRGB);
                var input = stack.malloc(width * height * 4);
                begin(stack);
                shared.release(command, queueFamily);
                check(vkEndCommandBuffer(command), "end presentation initial release");
                check(vkResetFences(device, fence), "reset presentation initial release fence");
                check(vkQueueSubmit(queue, VkSubmitInfo.calloc(stack).sType$Default()
                        .pCommandBuffers(stack.pointers(command)).pSignalSemaphores(stack.longs(shared.readyForGl())), fence), "submit presentation initial release");
                awaitFence();
                for (int frame = 0; frame < 8; frame++) {
                    for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                        int offset = (y * width + x) * 4;
                        input.put(offset, (byte) (x * 3 + frame * 17));
                        input.put(offset + 1, (byte) (y * 5 + frame * 11));
                        input.put(offset + 2, (byte) (x + y * 7 + frame));
                        input.put(offset + 3, (byte) 255);
                    }
                    org.lwjgl.opengl.GL45.glTextureSubImage2D(sourceTexture, 0, 0, 0, width, height,
                            org.lwjgl.opengl.GL11.GL_RGBA, org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE, input);
                    PresentationTransfer.copyGl(sourceFramebuffer, shared.beginGlCopy(), width, height, width, height, directCopy);
                    shared.endGlWrite();
                    begin(stack);
                    var destination = VkImageMemoryBarrier.calloc(stack).sType$Default().image(image)
                            .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                            .srcAccessMask(0).dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                            .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
                    destination.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
                    shared.acquire(command, queueFamily, destination);
                    PresentationTransfer.record(command, shared.image(), image, width, height, directCopy);
                    barrier(stack, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                            VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT, VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED,
                            VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
                    recordReadback(stack, image);
                    shared.release(command, queueFamily);
                    check(vkEndCommandBuffer(command), "end presentation transfer");
                    check(vkResetFences(device, fence), "reset presentation transfer fence");
                    check(vkQueueSubmit(queue, VkSubmitInfo.calloc(stack).sType$Default().pCommandBuffers(stack.pointers(command))
                            .waitSemaphoreCount(1).pWaitSemaphores(stack.longs(shared.readyForVulkan()))
                            .pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_TRANSFER_BIT))
                            .pSignalSemaphores(stack.longs(shared.readyForGl())), fence), "submit presentation transfer");
                    awaitFence();
                    currentLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
                    verifyPresentationPixels(frame, stack);
                }
                System.out.println("AFV_PRESENTATION_TRANSFER_PASS: " + (directCopy ? "copy" : "blit")
                        + ", vertical flip, nonuniform RGBA, 8 changing frames, size=" + width + "x" + height);
            } finally {
                org.lwjgl.opengl.GL11.glFinish();
                vkDeviceWaitIdle(device);
                if (sourceFramebuffer != 0) org.lwjgl.opengl.GL30.glDeleteFramebuffers(sourceFramebuffer);
                if (sourceTexture != 0) org.lwjgl.opengl.GL11.glDeleteTextures(sourceTexture);
            }
        }
    }
    private void verifyPresentationPixels(int frame, MemoryStack stack) {
        var pointer = stack.mallocPointer(1);
        check(vkMapMemory(device, readbackMemory, 0, (long) width * height * 4, 0, pointer), "map presentation pixels");
        try {
            var pixels = org.lwjgl.system.MemoryUtil.memByteBuffer(pointer.get(0), width * height * 4);
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                int sourceY = height - 1 - y, offset = (y * width + x) * 4;
                if ((pixels.get(offset) & 255) != ((x * 3 + frame * 17) & 255)
                        || (pixels.get(offset + 1) & 255) != ((sourceY * 5 + frame * 11) & 255)
                        || (pixels.get(offset + 2) & 255) != ((x + sourceY * 7 + frame) & 255)
                        || (pixels.get(offset + 3) & 255) != 255)
                    throw new IllegalStateException("Presentation transfer changed orientation/channel/bytes at " + x + "," + y + ", frame=" + frame);
            }
        } finally { vkUnmapMemory(device, readbackMemory); }
    }
    private void awaitFence() {
        check(vkWaitForFences(device, fence, true, 10_000_000_000L), "interop fence (10 second deadline)");
    }
    private static void check(int result, String operation) {
        if (result != VK_SUCCESS) throw new IllegalStateException(operation + " failed: VkResult " + result);
    }
    private void assertUsable() {
        if (closed || Thread.currentThread() != owner)
            throw new IllegalStateException("External image is closed or used outside its owning thread");
    }
    @Override public void close() {
        if (closed) return;
        if (Thread.currentThread() != owner) throw new IllegalStateException("Close external image on its owning thread");
        closed = true;
        if (device != null) {
            vkDeviceWaitIdle(device);
            if (fence != 0) vkDestroyFence(device, fence, null);
            if (toGl != 0) vkDestroySemaphore(device, toGl, null);
            if (fromGl != 0) vkDestroySemaphore(device, fromGl, null);
            if (pool != 0) vkDestroyCommandPool(device, pool, null);
            if (image != 0) vkDestroyImage(device, image, null);
            if (imageMemory != 0) vkFreeMemory(device, imageMemory, null);
            if (readback != 0) vkDestroyBuffer(device, readback, null);
            if (readbackMemory != 0) vkFreeMemory(device, readbackMemory, null);
            vkDestroyDevice(device, null);
        }
        if (instance != null) vkDestroyInstance(instance, null);
    }
}
