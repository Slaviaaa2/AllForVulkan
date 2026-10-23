package net.vulkanmod.vulkan.interop;

import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;
import static org.lwjgl.vulkan.VK10.*;
import static dev.allforvulkan.bridge.SharedImage.check;

/** Smoke-test assertion of ONLY the 32x32 synthetic patch, never screen capture/transport.
 * The caller verifies after the existing frame fence. Nothing is saved or emitted as an image. */
final class PatchReadback implements AutoCloseable {
    private long buffer, memory;
    private int pixels;
    private boolean pending, submitted;

    PatchReadback() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var handle = stack.mallocLong(1);
            check(vkCreateBuffer(Vulkan.getVkDevice(), VkBufferCreateInfo.calloc(stack).sType$Default().size(32 * 32 * 4)
                    .usage(VK_BUFFER_USAGE_TRANSFER_DST_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE), null, handle), "patch assertion buffer");
            buffer = handle.get(0);
            var requirements = VkMemoryRequirements.calloc(stack);
            vkGetBufferMemoryRequirements(Vulkan.getVkDevice(), buffer, requirements);
            var properties = VkPhysicalDeviceMemoryProperties.calloc(stack);
            vkGetPhysicalDeviceMemoryProperties(DeviceManager.physicalDevice, properties);
            int type = -1;
            int flags = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
            for (int i = 0; i < properties.memoryTypeCount(); i++)
                if ((requirements.memoryTypeBits() & (1 << i)) != 0 && (properties.memoryTypes(i).propertyFlags() & flags) == flags) { type = i; break; }
            if (type < 0) throw new IllegalStateException("No coherent assertion memory type");
            check(vkAllocateMemory(Vulkan.getVkDevice(), VkMemoryAllocateInfo.calloc(stack).sType$Default()
                    .allocationSize(requirements.size()).memoryTypeIndex(type), null, handle), "patch assertion memory");
            memory = handle.get(0);
            check(vkBindBufferMemory(Vulkan.getVkDevice(), buffer, memory, 0), "bind patch assertion buffer");
        } catch (Throwable failure) { close(); throw failure; }
    }
    void record(VkCommandBuffer command, VulkanImage image) {
        if (pending) throw new IllegalStateException("Patch assertion reused before frame fence");
        if (image.format != VK_FORMAT_B8G8R8A8_UNORM && image.format != VK_FORMAT_B8G8R8A8_SRGB
                && image.format != VK_FORMAT_R8G8B8A8_UNORM && image.format != VK_FORMAT_R8G8B8A8_SRGB)
            throw new IllegalStateException("Unsupported patch assertion format: " + image.format);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            image.transitionImageLayout(stack, command, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
            var region = VkBufferImageCopy.calloc(1, stack);
            region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            int width = Math.min(32, image.width), height = Math.min(32, image.height);
            region.imageExtent().set(width, height, 1);
            pixels = width * height;
            vkCmdCopyImageToBuffer(command, image.getId(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, buffer, region);
            var barrier = VkBufferMemoryBarrier.calloc(1, stack).sType$Default().srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_HOST_READ_BIT).srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .buffer(buffer).offset(0).size(VK_WHOLE_SIZE);
            vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, null, barrier, null);
            pending = true;
        }
    }
    void submitted() {
        if (!pending) throw new IllegalStateException("Patch assertion submitted without a recorded copy");
        submitted = true;
    }
    boolean verify() {
        // A resize may discard a recorded frame before vkQueueSubmit.
        if (!pending || !submitted) return false;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var pointer = stack.mallocPointer(1);
            check(vkMapMemory(Vulkan.getVkDevice(), memory, 0, (long) pixels * 4, 0, pointer), "map patch assertion");
            try {
                var bytes = MemoryUtil.memByteBuffer(pointer.get(0), pixels * 4);
                for (int i = 0; i < pixels * 4; i += 4)
                    if ((bytes.get(i) & 255) != 255 || bytes.get(i + 1) != 0 || (bytes.get(i + 2) & 255) != 255 || (bytes.get(i + 3) & 255) != 255)
                        throw new IllegalStateException("AFV_ZINK_PATCH_FAIL: swapchain patch pixel " + i / 4);
            } finally { vkUnmapMemory(Vulkan.getVkDevice(), memory); }
        }
        pending = submitted = false;
        return true;
    }
    /** GPU work must have completed before close. */
    @Override public void close() {
        if (buffer != 0) { vkDestroyBuffer(Vulkan.getVkDevice(), buffer, null); buffer = 0; }
        if (memory != 0) { vkFreeMemory(Vulkan.getVkDevice(), memory, null); memory = 0; }
    }
}
