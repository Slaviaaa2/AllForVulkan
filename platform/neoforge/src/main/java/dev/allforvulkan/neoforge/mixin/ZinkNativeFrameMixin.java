package dev.allforvulkan.neoforge.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.allforvulkan.neoforge.ZinkNativeSession;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(RenderSystem.class)
public abstract class ZinkNativeFrameMixin {
    @Redirect(method = "flipFrame", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwSwapBuffers(J)V"))
    private static void afv$nativeSwap(long window) { ZinkNativeSession.swap(window); }
}
