package net.vulkanmod.vulkan.pass;

import net.vulkanmod.gl.VkGlTexture;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

public interface MainPass {

    void begin(VkCommandBuffer commandBuffer, MemoryStack stack);

    void end(VkCommandBuffer commandBuffer);

    default void resume(VkCommandBuffer commandBuffer, MemoryStack stack) {
        throw new UnsupportedOperationException("This main pass cannot resume after interop");
    }

    void cleanUp();

    void onResize();

    default void mainTargetBindWrite() {}

    default void mainTargetUnbindWrite() {}

    default void rebindMainTarget() {}

    default void bindAsTexture() {}

    default VkGlTexture getColorAttachment() {
        throw new UnsupportedOperationException();
    }
}
