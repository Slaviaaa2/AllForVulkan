package dev.allforvulkan.bridge;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.IntPredicate;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.*;
import static org.lwjgl.vulkan.KHRSurface.*;

/** Shared capability policy for the isolated preflight and the real GL/Vulkan presenter. */
public final class PresentationSurface {
    public record OfferedFormat(int format, int colorSpace) {}
    public record ColorFormat(int surface, int shared, int colorSpace) {
        /** Only identical formats can preserve channel order and encoding with a raw copy. */
        public boolean directCopy() { return surface == shared; }
    }
    public record Extent(int width, int height) {
        public boolean drawable() { return width > 0 && height > 0; }
    }
    private static final Extent SUSPENDED = new Extent(0, 0);
    private PresentationSurface() {}

    public static Extent extent(VkSurfaceCapabilitiesKHR caps, int requestedWidth, int requestedHeight) {
        return extent(requestedWidth, requestedHeight, caps.currentExtent().width(), caps.currentExtent().height(),
                caps.minImageExtent().width(), caps.minImageExtent().height(), caps.maxImageExtent().width(), caps.maxImageExtent().height());
    }

    public static Extent extent(int requestedWidth, int requestedHeight, int currentWidth, int currentHeight,
                                int minWidth, int minHeight, int maxWidth, int maxHeight) {
        // A minimized surface may report zero even while GLFW still has a nonzero framebuffer.
        // Do not clamp 0 up to 1 or call Vulkan with an invalid zero-sized swapchain.
        if (requestedWidth <= 0 || requestedHeight <= 0 || currentWidth == 0 || currentHeight == 0
                || maxWidth == 0 || maxHeight == 0) return SUSPENDED;
        if (minWidth < 0 || minHeight < 0 || maxWidth < Math.max(1, minWidth) || maxHeight < Math.max(1, minHeight))
            throw new IllegalStateException("Surface returned invalid image extent limits");
        int width = currentWidth == -1 ? Math.clamp(requestedWidth, Math.max(1, minWidth), maxWidth) : currentWidth;
        int height = currentHeight == -1 ? Math.clamp(requestedHeight, Math.max(1, minHeight), maxHeight) : currentHeight;
        if (width < Math.max(1, minWidth) || height < Math.max(1, minHeight) || width > maxWidth || height > maxHeight)
            throw new IllegalStateException("Surface returned an image extent outside its limits");
        return new Extent(width, height);
    }

    public static ColorFormat colorFormat(VkPhysicalDevice physical, VkSurfaceFormatKHR.Buffer formats) {
        var offered = new ArrayList<OfferedFormat>();
        for (var format : formats) offered.add(new OfferedFormat(format.format(), format.colorSpace()));
        var features = new HashMap<Integer, Integer>();
        var shared = new HashMap<Integer, Boolean>();
        int required = VK_FORMAT_FEATURE_BLIT_DST_BIT | VK_FORMAT_FEATURE_TRANSFER_DST_BIT;
        return colorFormat(offered, format -> (features.computeIfAbsent(format, f -> features(physical, f)) & required) == required,
                format -> shared.computeIfAbsent(format, f -> exportableSource(physical, f)));
    }

    /** Prefer byte-preserving UNORM output, but search every usable supported pair. */
    public static ColorFormat colorFormat(List<OfferedFormat> offered, IntPredicate destination, IntPredicate sharedSource) {
        // Prefer a byte-preserving UNORM pair; among equal encodings, an exact
        // RGBA match avoids the Vulkan blit/conversion after the GL color copy.
        int[] preferences = {VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_B8G8R8A8_UNORM, VK_FORMAT_R8G8B8A8_SRGB, VK_FORMAT_B8G8R8A8_SRGB};
        for (int format : preferences) {
            for (var candidate : offered) {
                if (candidate.colorSpace() != VK_COLOR_SPACE_SRGB_NONLINEAR_KHR
                        || (candidate.format() != format && candidate.format() != VK_FORMAT_UNDEFINED)) continue;
                int shared = format == VK_FORMAT_B8G8R8A8_SRGB || format == VK_FORMAT_R8G8B8A8_SRGB
                        ? VK_FORMAT_R8G8B8A8_SRGB : VK_FORMAT_R8G8B8A8_UNORM;
                if (destination.test(format) && sharedSource.test(shared)) return new ColorFormat(format, shared, candidate.colorSpace());
            }
        }
        throw new IllegalStateException("Surface lacks an exportable RGBA/BGRA 8-bit blit pair");
    }

    private static int features(VkPhysicalDevice physical, int format) {
        try (var stack = MemoryStack.stackPush()) {
            var properties = VkFormatProperties.calloc(stack);
            vkGetPhysicalDeviceFormatProperties(physical, format, properties);
            return properties.optimalTilingFeatures();
        }
    }

    private static boolean exportableSource(VkPhysicalDevice physical, int format) {
        int required = VK_FORMAT_FEATURE_BLIT_SRC_BIT | VK_FORMAT_FEATURE_TRANSFER_SRC_BIT;
        if ((features(physical, format) & required) != required) return false;
        try (var stack = MemoryStack.stackPush()) {
            var external = VkPhysicalDeviceExternalImageFormatInfo.calloc(stack).sType$Default()
                    .handleType(VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT);
            var properties = VkExternalImageFormatProperties.calloc(stack).sType$Default();
            var output = VkImageFormatProperties2.calloc(stack).sType$Default().pNext(properties.address());
            int result = vkGetPhysicalDeviceImageFormatProperties2(physical, VkPhysicalDeviceImageFormatInfo2.calloc(stack).sType$Default()
                    .pNext(external.address()).format(format).type(VK_IMAGE_TYPE_2D).tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT), output);
            if (result == VK_ERROR_FORMAT_NOT_SUPPORTED) return false;
            SharedImage.check(result, "presentation shared format " + format);
            return (properties.externalMemoryProperties().externalMemoryFeatures() & VK_EXTERNAL_MEMORY_FEATURE_EXPORTABLE_BIT) != 0;
        }
    }
}
