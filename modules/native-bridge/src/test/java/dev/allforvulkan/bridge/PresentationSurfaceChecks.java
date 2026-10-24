package dev.allforvulkan.bridge;

import java.util.List;
import static dev.allforvulkan.bridge.PresentationSurface.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.KHRSurface.*;

/** Hardware-independent regressions for capability combinations absent from the current GPU. */
public final class PresentationSurfaceChecks {
    public static void main(String[] args) {
        expectSuspended(extent(960, 540, 0, 0, 0, 0, 16384, 16384));
        expectSuspended(extent(960, 540, -1, -1, 1, 1, 0, 0));
        expectSuspended(extent(0, 0, 1280, 720, 1, 1, 16384, 16384));
        if (!extent(960, 540, 1280, 720, 1, 1, 16384, 16384).equals(new Extent(1280, 720)))
            throw new AssertionError("Fixed WSI size must override GLFW's stale size");
        if (!extent(960, 540, -1, -1, 64, 64, 640, 480).equals(new Extent(640, 480)))
            throw new AssertionError("Variable surface must clamp to supported limits");
        if (!extent(960, 540, -1, -1, 1, 1, 16384, 16384).drawable()) throw new AssertionError("Restored surface stayed suspended");
        expectFailure(() -> extent(960, 540, -1, -1, 1024, 1024, 640, 480));

        var bgra = new OfferedFormat(VK_FORMAT_B8G8R8A8_UNORM, VK_COLOR_SPACE_SRGB_NONLINEAR_KHR);
        var rgba = new OfferedFormat(VK_FORMAT_R8G8B8A8_UNORM, VK_COLOR_SPACE_SRGB_NONLINEAR_KHR);
        var srgb = new OfferedFormat(VK_FORMAT_B8G8R8A8_SRGB, VK_COLOR_SPACE_SRGB_NONLINEAR_KHR);
        var fallback = colorFormat(List.of(bgra, rgba), f -> f == VK_FORMAT_R8G8B8A8_UNORM, f -> true);
        if (fallback.surface() != VK_FORMAT_R8G8B8A8_UNORM) throw new AssertionError("Unsupported first format prevented a valid fallback");
        var exportFallback = colorFormat(List.of(bgra, srgb), f -> true, f -> f == VK_FORMAT_R8G8B8A8_SRGB);
        if (exportFallback.surface() != VK_FORMAT_B8G8R8A8_SRGB || exportFallback.shared() != VK_FORMAT_R8G8B8A8_SRGB)
            throw new AssertionError("Must choose an exportable source with matching sRGB interpretation");
        if (colorFormat(List.of(srgb, rgba), f -> true, f -> true).surface() != VK_FORMAT_R8G8B8A8_UNORM)
            throw new AssertionError("UNORM preference must not depend on enumeration order");
        var undefined = new OfferedFormat(VK_FORMAT_UNDEFINED, VK_COLOR_SPACE_SRGB_NONLINEAR_KHR);
        if (colorFormat(List.of(undefined), f -> f == VK_FORMAT_R8G8B8A8_UNORM, f -> true).surface() != VK_FORMAT_R8G8B8A8_UNORM)
            throw new AssertionError("Undefined legacy surface format must still respect blit capabilities");
        expectFailure(() -> colorFormat(List.of(bgra, rgba, srgb), f -> false, f -> true));
        expectFailure(() -> colorFormat(List.of(bgra, rgba, srgb), f -> true, f -> false));
        expectFailure(() -> colorFormat(List.of(new OfferedFormat(VK_FORMAT_R8G8B8A8_UNORM, 12345)), f -> true, f -> true));
        System.out.println("AFV_PRESENTATION_SURFACE_PASS: zero/minimized/stale/variable extents, resume, invalid limits, blit/export format fallback, color-space preservation");
    }
    private static void expectSuspended(Extent extent) {
        if (extent.drawable()) throw new AssertionError("Cannot create a swapchain for a suspended surface");
    }
    private static void expectFailure(Runnable operation) {
        try { operation.run(); }
        catch (IllegalStateException expected) { return; }
        throw new AssertionError("Unsupported capabilities were accepted");
    }
}
