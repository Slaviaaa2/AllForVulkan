package dev.allforvulkan.baseline.mixin;

import com.mojang.blaze3d.platform.Window;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Select EGL for the isolated Zink baseline, without changing AFV or normal GL. */
@Mixin(Window.class)
public abstract class BaselineWindowMixin {
    @Inject(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/neoforged/fml/loading/ImmediateWindowHandler;setupMinecraftWindow(Ljava/util/function/IntSupplier;Ljava/util/function/IntSupplier;Ljava/util/function/Supplier;Ljava/util/function/LongSupplier;)J"))
    private void afv$eglWindow(CallbackInfo ci) {
        if (Boolean.getBoolean("afv.expectZink"))
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_CREATION_API, GLFW.GLFW_EGL_CONTEXT_API);
    }

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL;createCapabilities()Lorg/lwjgl/opengl/GLCapabilities;"))
    private org.lwjgl.opengl.GLCapabilities afv$eglFunctions() {
        if (Boolean.getBoolean("afv.expectZink"))
            org.lwjgl.opengl.GL.create(org.lwjgl.egl.EGL10::eglGetProcAddress);
        return org.lwjgl.opengl.GL.createCapabilities();
    }
}
