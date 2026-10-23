package dev.allforvulkan.bridge;

/** A render-thread GL pass between Vulkan release and reacquire. Names are real GL
 * objects borrowed ONLY for this invocation. Do not retain them across frames or resize.
 * Write all output pixels, queue work on this context, and restore any additional
 * GL state beyond GlStateScope's documented set. No CPU image transport is required. */
public interface GlFramePass extends AutoCloseable {
    record Frame(int colorTexture, int outputFramebuffer, int width, int height, boolean srgb) {}
    void render(Frame frame);
    @Override void close();
}
