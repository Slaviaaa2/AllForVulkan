package dev.allforvulkan.bridge;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import java.util.HashSet;
import java.util.Set;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.VK_API_VERSION_1_2;

/** Window-independent checks before renderer mixins become irreversible. */
public final class VulkanSupport {
    public record Result(boolean supported, String detail) {}
    private VulkanSupport() {}

    public static Result probe(boolean externalImages) {
        return probe(externalImages, false);
    }
    public static Result probe(boolean externalImages, boolean presentationOnly) {
        try {
            return inspect(externalImages, presentationOnly);
        } catch (RuntimeException | LinkageError failure) {
            return new Result(false, failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
    }

    private static Result inspect(boolean externalImages, boolean presentationOnly) {
        if (VK.getInstanceVersionSupported() < VK_API_VERSION_1_2)
            return new Result(false, "Vulkan 1.2 loader is unavailable");
        if (externalImages && !System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("linux"))
            return new Result(false, "Zink external-image transport currently requires Linux");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkInstanceCreateInfo info = VkInstanceCreateInfo.calloc(stack).sType$Default()
                    .ppEnabledExtensionNames(stack.pointers(stack.UTF8(KHRSurface.VK_KHR_SURFACE_EXTENSION_NAME)))
                    .pApplicationInfo(VkApplicationInfo.calloc(stack).sType$Default().apiVersion(VK_API_VERSION_1_2));
            PointerBuffer pointer = stack.mallocPointer(1);
            int status = vkCreateInstance(info, null, pointer);
            if (status != VK_SUCCESS) return new Result(false, "vkCreateInstance: " + status);
            VkInstance instance = new VkInstance(pointer.get(0), info);
            try {
                var count = stack.ints(0);
                status = vkEnumeratePhysicalDevices(instance, count, null);
                if (status != VK_SUCCESS || count.get(0) == 0) return new Result(false, "No Vulkan device");
                PointerBuffer devices = stack.mallocPointer(count.get(0));
                if (vkEnumeratePhysicalDevices(instance, count, devices) != VK_SUCCESS)
                    return new Result(false, "Cannot enumerate Vulkan devices");
                Set<String> required = new HashSet<>(Set.of(KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME));
                if (externalImages) {
                    required.add(KHRExternalMemoryFd.VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME);
                    required.add(KHRExternalSemaphoreFd.VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME);
                }
                for (int d = 0; d < devices.capacity(); d++) {
                    // Reclaim per-device allocations when many ICDs are installed.
                    try (MemoryStack deviceStack = MemoryStack.stackPush()) {
                        VkPhysicalDevice device = new VkPhysicalDevice(devices.get(d), instance);
                        VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.malloc(deviceStack);
                        vkGetPhysicalDeviceProperties(device, props);
                        if (props.apiVersion() < VK_API_VERSION_1_2) continue;
                        VkPhysicalDeviceFeatures features = VkPhysicalDeviceFeatures.calloc(deviceStack);
                        vkGetPhysicalDeviceFeatures(device, features);
                        if (!presentationOnly && (!features.logicOp() || !features.samplerAnisotropy())) continue;
                        var n = deviceStack.ints(0);
                        if (vkEnumerateDeviceExtensionProperties(device, (String) null, n, null) != VK_SUCCESS) continue;
                        VkExtensionProperties.Buffer extensions = VkExtensionProperties.malloc(n.get(0), deviceStack);
                        if (vkEnumerateDeviceExtensionProperties(device, (String) null, n, extensions) != VK_SUCCESS) continue;
                        Set<String> available = new HashSet<>();
                        extensions.forEach(e -> available.add(e.extensionNameString()));
                        if (!available.containsAll(required)) continue;
                        vkGetPhysicalDeviceQueueFamilyProperties(device, n, null);
                        VkQueueFamilyProperties.Buffer queues = VkQueueFamilyProperties.malloc(n.get(0), deviceStack);
                        vkGetPhysicalDeviceQueueFamilyProperties(device, n, queues);
                        for (int q = 0; q < queues.capacity(); q++) {
                            if (queues.get(q).queueCount() == 0 || (queues.get(q).queueFlags() & VK_QUEUE_GRAPHICS_BIT) == 0) continue;
                            VkDeviceQueueCreateInfo.Buffer queue = VkDeviceQueueCreateInfo.calloc(1, deviceStack);
                            queue.get(0).sType$Default().queueFamilyIndex(q).pQueuePriorities(deviceStack.floats(1));
                            PointerBuffer names = deviceStack.mallocPointer(required.size());
                            required.forEach(name -> names.put(deviceStack.UTF8(name)));
                            names.flip();
                            VkDeviceCreateInfo create = VkDeviceCreateInfo.calloc(deviceStack).sType$Default()
                                    .pQueueCreateInfos(queue).ppEnabledExtensionNames(names)
                                    .pEnabledFeatures(VkPhysicalDeviceFeatures.calloc(deviceStack).logicOp(features.logicOp()).samplerAnisotropy(features.samplerAnisotropy()));
                            status = vkCreateDevice(device, create, null, pointer);
                            if (status == VK_SUCCESS) {
                                vkDestroyDevice(new VkDevice(pointer.get(0), device, create), null);
                                return new Result(true, props.deviceNameString() + " / Vulkan " + VK_VERSION_MAJOR(props.apiVersion()) + "." + VK_VERSION_MINOR(props.apiVersion()));
                            }
                        }
                    }
                }
                return new Result(false, "No Vulkan 1.2 graphics device with " + (presentationOnly ? "" : "logicOp, anisotropy, ")
                        + "required extensions and a creatable logical device");
            } finally {
                vkDestroyInstance(instance, null);
            }
        }
    }
}
