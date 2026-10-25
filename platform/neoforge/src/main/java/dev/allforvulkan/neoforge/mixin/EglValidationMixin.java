package dev.allforvulkan.neoforge.mixin;

import com.mojang.blaze3d.platform.Window;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** EGL frontend for native Zink presentation and the explicit GL comparison. */
@Mixin(Window.class)
public abstract class EglValidationMixin {
    @Inject(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/neoforged/fml/loading/ImmediateWindowHandler;setupMinecraftWindow(Ljava/util/function/IntSupplier;Ljava/util/function/IntSupplier;Ljava/util/function/Supplier;Ljava/util/function/LongSupplier;)J"))
    private void afv$eglComparison(CallbackInfo ci) {
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_CREATION_API, GLFW.GLFW_EGL_CONTEXT_API);
    }

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL;createCapabilities()Lorg/lwjgl/opengl/GLCapabilities;"))
    private org.lwjgl.opengl.GLCapabilities afv$eglFunctions() {
        // GLX's default loader may resolve the other vendor's entrypoints.
        org.lwjgl.opengl.GL.create(org.lwjgl.egl.EGL10::eglGetProcAddress);
        var capabilities = org.lwjgl.opengl.GL.createCapabilities();
        if (dev.allforvulkan.neoforge.BackendBootstrap.zinkNative())
            dev.allforvulkan.neoforge.ZinkNativeSession.open(GLFW.glfwGetCurrentContext());
        return capabilities;
    }
    @Inject(method = "close", at = @At("HEAD"))
    private void afv$nativeClose(CallbackInfo ci) {
        if (dev.allforvulkan.neoforge.BackendBootstrap.zinkNative())
            dev.allforvulkan.neoforge.ZinkNativeSession.close();
    }

}
