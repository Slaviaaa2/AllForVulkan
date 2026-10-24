package net.vulkanmod.vulkan.interop;

import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.DeviceManager;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;
import java.nio.ByteBuffer;
import static dev.allforvulkan.bridge.SharedImage.check;
import static org.lwjgl.vulkan.VK10.*;

/** Opt-in diagnostic: a private 8x8 last-output marker, never product frame transport.
 * Caller verifies only after the owning frame fence/device-idle has completed. */
final class FinalOutputReadback implements AutoCloseable {
    private static final int BYTES = 8 * 8 * 4;
    private final VkDevice device = Vulkan.getVkDevice();
    private long buffer, memory, mapped;
    private ByteBuffer pixels;
    private boolean coherent, pending;

    FinalOutputReadback() {
        try (var stack = MemoryStack.stackPush()) {
            var handle = stack.mallocLong(1);
            check(vkCreateBuffer(device, VkBufferCreateInfo.calloc(stack).sType$Default().size(BYTES)
                    .usage(VK_BUFFER_USAGE_TRANSFER_DST_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE), null, handle), "final-output diagnostic buffer");
            buffer = handle.get(0);
            var requirements = VkMemoryRequirements.calloc(stack);
            vkGetBufferMemoryRequirements(device, buffer, requirements);
            var properties = VkPhysicalDeviceMemoryProperties.calloc(stack);
            vkGetPhysicalDeviceMemoryProperties(DeviceManager.physicalDevice, properties);
            int type = -1;
            for (int i = 0; i < properties.memoryTypeCount(); i++) {
                int flags = properties.memoryTypes(i).propertyFlags();
                if ((requirements.memoryTypeBits() & (1 << i)) == 0 || (flags & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) == 0) continue;
                type = i;
                coherent = (flags & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) != 0;
                if (coherent) break;
            }
            if (type < 0) throw new IllegalStateException("No host-visible final-output diagnostic memory");
            check(vkAllocateMemory(device, VkMemoryAllocateInfo.calloc(stack).sType$Default()
                    .allocationSize(requirements.size()).memoryTypeIndex(type), null, handle), "final-output diagnostic memory");
            memory = handle.get(0);
            check(vkBindBufferMemory(device, buffer, memory, 0), "bind final-output diagnostic");
            var pointer = stack.mallocPointer(1);
            check(vkMapMemory(device, memory, 0, VK_WHOLE_SIZE, 0, pointer), "map final-output diagnostic");
            mapped = pointer.get(0);
            pixels = MemoryUtil.memByteBuffer(mapped, BYTES);
        } catch (Throwable failure) { close(); throw failure; }
    }
    void record(VkCommandBuffer command, long image, int width, int height, MemoryStack stack) {
        if (pending) throw new IllegalStateException("Unverified final-output diagnostic reused");
        var copy = VkBufferImageCopy.calloc(1, stack);
        copy.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
        copy.imageOffset().set(width - 24, height - 24, 0);
        copy.imageExtent().set(8, 8, 1);
        vkCmdCopyImageToBuffer(command, image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, buffer, copy);
        var barrier = VkBufferMemoryBarrier.calloc(1, stack).sType$Default().buffer(buffer).offset(0).size(VK_WHOLE_SIZE)
                .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
        vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, null, barrier, null);
    }
    void submitted() { pending = true; }
    boolean verify() {
        if (!pending) return false;
        if (!coherent) try (var stack = MemoryStack.stackPush()) {
            check(vkInvalidateMappedMemoryRanges(device, VkMappedMemoryRange.calloc(1, stack).sType$Default()
                    .memory(memory).offset(0).size(VK_WHOLE_SIZE)), "invalidate final-output diagnostic");
        }
        for (int i = 0; i < BYTES; i += 4) {
            if ((pixels.get(i) & 255) != 255 || pixels.get(i + 1) != 0 || (pixels.get(i + 2) & 255) != 255 || (pixels.get(i + 3) & 255) != 255)
                throw new IllegalStateException("AFV_FINAL_OUTPUT_FAIL: Vulkan did not see the last default-framebuffer draw at pixel " + i / 4
                        + ": " + (pixels.get(i) & 255) + "," + (pixels.get(i + 1) & 255) + "," + (pixels.get(i + 2) & 255) + "," + (pixels.get(i + 3) & 255));
        }
        pending = false;
        return true;
    }
    @Override public void close() {
        if (mapped != 0) vkUnmapMemory(device, memory);
        if (buffer != 0) vkDestroyBuffer(device, buffer, null);
        if (memory != 0) vkFreeMemory(device, memory, null);
        mapped = buffer = memory = 0;
    }
}
