package dev.allforvulkan.bridge;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL45.glBlitNamedFramebuffer;
import static org.lwjgl.vulkan.VK10.*;

/** The GL copy and Vulkan transfer share one vertical flip, with no extra pass.
 * The caller owns GL state isolation, Vulkan barriers and semaphore submission. */
public final class PresentationTransfer {
    private PresentationTransfer() {}

    public static void copyGl(int source, int destination, int sourceWidth, int sourceHeight,
                              int width, int height, boolean directCopy) {
        glBlitNamedFramebuffer(source, destination, 0, 0, sourceWidth, sourceHeight,
                0, directCopy ? height : 0, width, directCopy ? 0 : height, GL_COLOR_BUFFER_BIT, GL_NEAREST);
    }

    /** directCopy requires identical source/destination formats and image extents. */
    public static void record(VkCommandBuffer command, long source, long destination,
                              int width, int height, boolean directCopy) {
        try (var stack = MemoryStack.stackPush()) {
            if (directCopy) {
                var copy = VkImageCopy.calloc(1, stack);
                copy.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                copy.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                copy.extent().set(width, height, 1);
                vkCmdCopyImage(command, source, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        destination, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, copy);
            } else {
                var blit = VkImageBlit.calloc(1, stack);
                blit.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                blit.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                blit.srcOffsets(0).set(0, height, 0); blit.srcOffsets(1).set(width, 0, 1);
                blit.dstOffsets(1).set(width, height, 1);
                vkCmdBlitImage(command, source, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        destination, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, blit, VK_FILTER_NEAREST);
            }
        }
    }
}
