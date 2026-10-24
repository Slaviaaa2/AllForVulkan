package dev.allforvulkan.bridge;

/** Preserve the VkResult so WSI loss can be recovered without hiding other errors. */
public final class SurfaceFailure extends IllegalStateException {
    private final int result;
    public SurfaceFailure(int result, String operation) {
        super(operation + ": Vulkan result " + result);
        this.result = result;
    }
    public int result() { return result; }
    public static void check(int result, String operation) {
        if (result != org.lwjgl.vulkan.VK10.VK_SUCCESS) throw new SurfaceFailure(result, operation);
    }
}
