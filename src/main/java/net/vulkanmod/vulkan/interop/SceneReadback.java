package net.vulkanmod.vulkan.interop;

import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;
import static org.lwjgl.vulkan.VK10.*;
import static dev.allforvulkan.bridge.SharedImage.check;

/** Numeric smoke assertions for ONLY a 32x32 scene region at (32,0), before/after GL.
 * No screenshot, file output, or frame transport. Caller waits the existing frame fence. */
final class SceneReadback implements AutoCloseable {
    private static final int BYTES = 32 * 32 * 4;
    private final long[] buffers = new long[2], memories = new long[2];
    private boolean recorded, submitted, srgb;
    private final boolean invert;
    SceneReadback(boolean invert) {
        this.invert = invert;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var handle = stack.mallocLong(1);
            var requirements = VkMemoryRequirements.calloc(stack);
            var properties = VkPhysicalDeviceMemoryProperties.calloc(stack);
            vkGetPhysicalDeviceMemoryProperties(DeviceManager.physicalDevice, properties);
            for (int index = 0; index < 2; index++) {
                check(vkCreateBuffer(Vulkan.getVkDevice(), VkBufferCreateInfo.calloc(stack).sType$Default().size(BYTES)
                        .usage(VK_BUFFER_USAGE_TRANSFER_DST_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE), null, handle), "scene assertion buffer");
                buffers[index] = handle.get(0);
                vkGetBufferMemoryRequirements(Vulkan.getVkDevice(), buffers[index], requirements);
                int type = -1, flags = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
                for (int i = 0; i < properties.memoryTypeCount(); i++)
                    if ((requirements.memoryTypeBits() & 1 << i) != 0 && (properties.memoryTypes(i).propertyFlags() & flags) == flags) { type = i; break; }
                if (type < 0) throw new IllegalStateException("No scene assertion host memory");
                check(vkAllocateMemory(Vulkan.getVkDevice(), VkMemoryAllocateInfo.calloc(stack).sType$Default()
                        .allocationSize(requirements.size()).memoryTypeIndex(type), null, handle), "scene assertion memory");
                memories[index] = handle.get(0);
                check(vkBindBufferMemory(Vulkan.getVkDevice(), buffers[index], memories[index], 0), "bind scene assertion");
            }
        } catch (Throwable failure) { close(); throw failure; }
    }
    void record(VkCommandBuffer command, VulkanImage image, boolean output) {
        if (image.width < 64 || image.height < 32) throw new IllegalStateException("Scene smoke assertion requires a 64x32 minimum viewport");
        if (!output && recorded) throw new IllegalStateException("Scene assertion reused before fence");
        srgb = image.format == VK_FORMAT_B8G8R8A8_SRGB || image.format == VK_FORMAT_R8G8B8A8_SRGB;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            image.transitionImageLayout(stack, command, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
            var region = VkBufferImageCopy.calloc(1, stack);
            region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            region.imageOffset().set(32, 0, 0);
            region.imageExtent().set(32, 32, 1);
            long buffer = buffers[output ? 1 : 0];
            vkCmdCopyImageToBuffer(command, image.getId(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, buffer, region);
            var barrier = VkBufferMemoryBarrier.calloc(1, stack).sType$Default().buffer(buffer).size(VK_WHOLE_SIZE)
                    .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
            vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, null, barrier, null);
            if (output) recorded = true;
        }
    }
    void submitted() { if (!recorded) throw new IllegalStateException("Missing scene copy"); submitted = true; }
    boolean verify() {
        if (!submitted) return false;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var pointers = stack.mallocPointer(2);
            check(vkMapMemory(Vulkan.getVkDevice(), memories[0], 0, BYTES, 0, pointers), "map scene input assertion");
            try {
                check(vkMapMemory(Vulkan.getVkDevice(), memories[1], 0, BYTES, 0, pointers.position(1)), "map scene output assertion");
                try {
                    var source = MemoryUtil.memByteBuffer(pointers.get(0), BYTES);
                    var result = MemoryUtil.memByteBuffer(pointers.get(1), BYTES);
                    for (int i = 0; i < BYTES; i++) {
                        int value = source.get(i) & 255;
                        int expected = invert && i % 4 != 3 ? invert(value, srgb) : value;
                        if (Math.abs((result.get(i) & 255) - expected) > (srgb ? 1 : 0))
                            throw new IllegalStateException("AFV_ZINK_SCENE_FAIL: component " + i + " expected " + expected + " got " + (result.get(i) & 255));
                    }
                } finally { vkUnmapMemory(Vulkan.getVkDevice(), memories[1]); }
            } finally { vkUnmapMemory(Vulkan.getVkDevice(), memories[0]); }
        }
        recorded = submitted = false;
        return true;
    }
    private static int invert(int value, boolean srgb) {
        if (!srgb) return 255 - value;
        double color = value / 255.0;
        color = color <= 0.04045 ? color / 12.92 : Math.pow((color + 0.055) / 1.055, 2.4);
        color = 1 - color;
        color = color <= 0.0031308 ? color * 12.92 : 1.055 * Math.pow(color, 1 / 2.4) - 0.055;
        return (int) Math.round(color * 255);
    }
    @Override public void close() {
        for (int i = 0; i < 2; i++) {
            if (buffers[i] != 0) { vkDestroyBuffer(Vulkan.getVkDevice(), buffers[i], null); buffers[i] = 0; }
            if (memories[i] != 0) { vkFreeMemory(Vulkan.getVkDevice(), memories[i], null); memories[i] = 0; }
        }
    }
}
