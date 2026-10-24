package dev.allforvulkan.bridge;

import java.util.ArrayList;
import java.util.List;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkQueueFamilyProperties;
import static org.lwjgl.vulkan.KHRSurface.vkGetPhysicalDeviceSurfaceSupportKHR;
import static org.lwjgl.vulkan.VK10.*;

/** Shared queue policy for the game and its isolated surface preflight.
 * Unsupported families produce an unsuitable selection, never a guessed queue. */
public final class QueueSelection {
    public record Family(int flags, int count, boolean present) {}
    public record Selection(int graphics, int present, int transfer, int compute) {
        public boolean suitable(boolean presentationOnly) {
            return graphics >= 0 && present >= 0 && (presentationOnly || (transfer >= 0 && compute >= 0));
        }
    }
    private QueueSelection() {}

    public static Selection query(VkPhysicalDevice device, long surface, boolean presentationOnly) {
        try (var stack = MemoryStack.stackPush()) {
            var count = stack.mallocInt(1);
            vkGetPhysicalDeviceQueueFamilyProperties(device, count, null);
            var properties = VkQueueFamilyProperties.malloc(count.get(0), stack);
            vkGetPhysicalDeviceQueueFamilyProperties(device, count, properties);
            var families = new ArrayList<Family>(count.get(0));
            var supported = stack.mallocInt(1);
            for (int i = 0; i < count.get(0); i++) {
                var family = properties.get(i);
                boolean present = false;
                if (family.queueCount() > 0) {
                    int status = vkGetPhysicalDeviceSurfaceSupportKHR(device, i, surface, supported);
                    if (status != VK_SUCCESS) throw new IllegalStateException("Queue surface support: Vulkan result " + status);
                    present = supported.get(0) == VK_TRUE;
                }
                families.add(new Family(family.queueFlags(), family.queueCount(), present));
            }
            return select(families, presentationOnly);
        }
    }

    public static Selection select(List<Family> families, boolean presentationOnly) {
        int graphics = -1, present = -1, combined = -1;
        int compute = -1, dedicatedCompute = -1, dedicatedTransfer = -1;
        for (int i = 0; i < families.size(); i++) {
            var family = families.get(i);
            if (family.count() <= 0) continue;
            boolean draws = (family.flags() & VK_QUEUE_GRAPHICS_BIT) != 0;
            boolean computes = (family.flags() & VK_QUEUE_COMPUTE_BIT) != 0;
            if (draws && graphics < 0) graphics = i;
            if (family.present() && present < 0) present = i;
            if (draws && family.present() && combined < 0) combined = i;
            if (computes && compute < 0) compute = i;
            if (computes && !draws && dedicatedCompute < 0) dedicatedCompute = i;
            if ((family.flags() & VK_QUEUE_TRANSFER_BIT) != 0 && !draws && !computes && dedicatedTransfer < 0)
                dedicatedTransfer = i;
        }
        if (combined >= 0) graphics = present = combined;
        if (presentationOnly) return new Selection(graphics, present, -1, -1);
        if (dedicatedCompute >= 0) compute = dedicatedCompute;
        // Graphics and compute queues implicitly support transfer commands even
        // when a driver omits VK_QUEUE_TRANSFER_BIT. Prefer a transfer-only queue.
        int transfer = dedicatedTransfer >= 0 ? dedicatedTransfer
                : dedicatedCompute >= 0 ? dedicatedCompute : graphics >= 0 ? graphics : compute;
        return new Selection(graphics, present, transfer, compute);
    }
}
