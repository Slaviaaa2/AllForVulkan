package dev.allforvulkan.bridge;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWVulkan;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;
import static dev.allforvulkan.bridge.SharedImage.check;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.*;
import static org.lwjgl.vulkan.VK12.VK_API_VERSION_1_2;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;

/** Child-process-only WSI probe on the same GPU as the real GL producer.
 * Its NO_API window is hidden; it never draws, presents or captures a screen. */
final class WsiSupportProbe {
    static void run(byte[] uuid) {
        VkInstance instance = null;
        VkDevice device = null;
        long window = 0, surface = 0, swapchain = 0;
        boolean initialized = false;
        try (var stack = MemoryStack.stackPush()) {
            if (!GLFW.glfwInit()) throw new IllegalStateException("GLFW cannot initialize the display for Vulkan presentation");
            initialized = true;
            GLFW.glfwDefaultWindowHints();
            GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_NO_API);
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_FOCUSED, GLFW.GLFW_FALSE);
            window = GLFW.glfwCreateWindow(32, 32, "All For Vulkan capability check", 0, 0);
            if (window == 0) throw new IllegalStateException("Cannot create a hidden Vulkan capability window");
            var extensions = GLFWVulkan.glfwGetRequiredInstanceExtensions();
            if (extensions == null) throw new IllegalStateException("GLFW has no Vulkan surface extensions");
            var create = VkInstanceCreateInfo.calloc(stack).sType$Default().ppEnabledExtensionNames(extensions)
                    .pApplicationInfo(VkApplicationInfo.calloc(stack).sType$Default().apiVersion(VK_API_VERSION_1_2));
            var pointer = stack.mallocPointer(1); var handle = stack.mallocLong(1);
            check(vkCreateInstance(create, null, pointer), "WSI probe instance");
            instance = new VkInstance(pointer.get(0), create);
            check(GLFWVulkan.nglfwCreateWindowSurface(instance.address(), window, 0, MemoryUtil.memAddress(handle)), "WSI probe surface");
            surface = handle.get(0);
            var count = stack.mallocInt(1);
            check(vkEnumeratePhysicalDevices(instance, count, null), "WSI probe device count");
            var devices = stack.mallocPointer(count.get(0));
            check(vkEnumeratePhysicalDevices(instance, count, devices), "WSI probe devices");
            VkPhysicalDevice physical = null;
            for (int i = 0; i < devices.remaining(); i++) {
                var candidate = new VkPhysicalDevice(devices.get(i), instance);
                var id = VkPhysicalDeviceIDProperties.calloc(stack).sType$Default();
                var properties = VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(id.address());
                vkGetPhysicalDeviceProperties2(candidate, properties);
                byte[] actual = new byte[VK_UUID_SIZE]; id.deviceUUID().get(actual);
                if (java.util.Arrays.equals(actual, uuid) && properties.properties().apiVersion() >= VK_API_VERSION_1_2) { physical = candidate; break; }
            }
            if (physical == null) throw new IllegalStateException("No Vulkan 1.2 WSI GPU matches Zink's UUID");
            vkGetPhysicalDeviceQueueFamilyProperties(physical, count, null);
            var families = VkQueueFamilyProperties.calloc(count.get(0), stack);
            vkGetPhysicalDeviceQueueFamilyProperties(physical, count, families);
            int graphics = -1, present = -1;
            var supported = stack.mallocInt(1);
            for (int i = 0; i < families.capacity(); i++) {
                if (families.get(i).queueCount() == 0) continue;
                boolean draws = (families.get(i).queueFlags() & VK_QUEUE_GRAPHICS_BIT) != 0;
                if (draws && graphics == -1) graphics = i;
                check(vkGetPhysicalDeviceSurfaceSupportKHR(physical, i, surface, supported), "WSI probe queue surface support");
                if (supported.get(0) != 0) {
                    present = i;
                    if (draws) { graphics = i; break; }
                }
            }
            if (graphics < 0 || present < 0) throw new IllegalStateException("Zink GPU cannot draw/present to this window system");
            var capabilities = VkSurfaceCapabilitiesKHR.calloc(stack);
            check(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physical, surface, capabilities), "WSI probe capabilities");
            if ((capabilities.supportedUsageFlags() & VK_IMAGE_USAGE_TRANSFER_DST_BIT) == 0)
                throw new IllegalStateException("Window surface cannot receive transferred GL output");
            check(vkGetPhysicalDeviceSurfaceFormatsKHR(physical, surface, count, null), "WSI probe format count");
            var formats = VkSurfaceFormatKHR.calloc(count.get(0), stack);
            check(vkGetPhysicalDeviceSurfaceFormatsKHR(physical, surface, count, formats), "WSI probe formats");
            int format = 0;
            for (var candidate : formats) {
                if (candidate.colorSpace() != VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) continue;
                int f = candidate.format();
                if (f == VK_FORMAT_UNDEFINED) { format = VK_FORMAT_B8G8R8A8_UNORM; break; }
                if (f == VK_FORMAT_B8G8R8A8_UNORM || f == VK_FORMAT_R8G8B8A8_UNORM) { format = f; break; }
                if (f == VK_FORMAT_B8G8R8A8_SRGB || f == VK_FORMAT_R8G8B8A8_SRGB) format = f;
            }
            if (format == 0) throw new IllegalStateException("WSI lacks an RGBA/BGRA 8-bit surface format");
            int sharedFormat = format == VK_FORMAT_B8G8R8A8_SRGB || format == VK_FORMAT_R8G8B8A8_SRGB ? VK_FORMAT_R8G8B8A8_SRGB : VK_FORMAT_R8G8B8A8_UNORM;
            var properties = VkFormatProperties.calloc(stack);
            vkGetPhysicalDeviceFormatProperties(physical, format, properties);
            if ((properties.optimalTilingFeatures() & VK_FORMAT_FEATURE_BLIT_DST_BIT) == 0) throw new IllegalStateException("WSI format cannot receive a blit");
            vkGetPhysicalDeviceFormatProperties(physical, sharedFormat, properties);
            if ((properties.optimalTilingFeatures() & VK_FORMAT_FEATURE_BLIT_SRC_BIT) == 0) throw new IllegalStateException("Shared color format cannot supply a blit");
            var queues = VkDeviceQueueCreateInfo.calloc(graphics == present ? 1 : 2, stack);
            queues.get(0).sType$Default().queueFamilyIndex(graphics).pQueuePriorities(stack.floats(1));
            if (graphics != present) queues.get(1).sType$Default().queueFamilyIndex(present).pQueuePriorities(stack.floats(1));
            var deviceInfo = VkDeviceCreateInfo.calloc(stack).sType$Default().pQueueCreateInfos(queues)
                    .ppEnabledExtensionNames(stack.pointers(stack.UTF8(VK_KHR_SWAPCHAIN_EXTENSION_NAME),
                            stack.UTF8(KHRExternalMemoryFd.VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME),
                            stack.UTF8(KHRExternalSemaphoreFd.VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME)));
            check(vkCreateDevice(physical, deviceInfo, null, pointer), "WSI probe logical device");
            device = new VkDevice(pointer.get(0), physical, deviceInfo);
            try (var state = new GlStateScope(); var shared = new SharedImage(physical, device, 8, 8, sharedFormat)) {
                org.lwjgl.opengl.GL11.glFinish();
            }
            int width = capabilities.currentExtent().width() == -1 ? Math.clamp(32, capabilities.minImageExtent().width(), capabilities.maxImageExtent().width()) : capabilities.currentExtent().width();
            int height = capabilities.currentExtent().height() == -1 ? Math.clamp(32, capabilities.minImageExtent().height(), capabilities.maxImageExtent().height()) : capabilities.currentExtent().height();
            int imageCount = capabilities.minImageCount() + 1;
            if (capabilities.maxImageCount() > 0) imageCount = Math.min(imageCount, capabilities.maxImageCount());
            var info = VkSwapchainCreateInfoKHR.calloc(stack).sType$Default().surface(surface).minImageCount(imageCount)
                    .imageFormat(format).imageColorSpace(VK_COLOR_SPACE_SRGB_NONLINEAR_KHR).imageArrayLayers(1)
                    .imageUsage(VK_IMAGE_USAGE_TRANSFER_DST_BIT).preTransform(capabilities.currentTransform())
                    .compositeAlpha(Integer.lowestOneBit(capabilities.supportedCompositeAlpha())).presentMode(VK_PRESENT_MODE_FIFO_KHR).clipped(true);
            info.imageExtent().set(width, height);
            if (graphics != present) info.imageSharingMode(VK_SHARING_MODE_CONCURRENT).pQueueFamilyIndices(stack.ints(graphics, present));
            else info.imageSharingMode(VK_SHARING_MODE_EXCLUSIVE);
            check(vkCreateSwapchainKHR(device, info, null, handle), "WSI probe swapchain");
            swapchain = handle.get(0);
            check(vkGetSwapchainImagesKHR(device, swapchain, count, null), "WSI probe swapchain images");
            if (count.get(0) == 0) throw new IllegalStateException("WSI returned no swapchain images");
            System.out.println("AFV_WSI_SUPPORT_PASS: same GPU, hidden NO_API surface, graphics/present queues, transfer/blit formats, shared-format FD import, logical device, " + count.get(0) + " swapchain images");
        } finally {
            if (device != null) {
                vkDeviceWaitIdle(device);
                if (swapchain != 0) vkDestroySwapchainKHR(device, swapchain, null);
                vkDestroyDevice(device, null);
            }
            if (surface != 0) vkDestroySurfaceKHR(instance, surface, null);
            if (instance != null) vkDestroyInstance(instance, null);
            if (window != 0) GLFW.glfwDestroyWindow(window);
            if (initialized) GLFW.glfwTerminate();
        }
    }
}
