package dev.allforvulkan.neoforge.mixin;

import com.mojang.blaze3d.platform.Window;
import dev.allforvulkan.neoforge.NeoForgePlatformServices;
import dev.allforvulkan.neoforge.ZinkClientSession;
import net.vulkanmod.Initializer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Original Minecraft/Iris GL rendering on Zink, separate from the visible Vulkan window. */
@Mixin(Window.class)
public abstract class ZinkClientWindowMixin {
    @Shadow private boolean vsync;
    @Inject(method = "<init>", at = @At("HEAD"))
    private static void afv$initialize(CallbackInfo ci) { Initializer.initialize(new NeoForgePlatformServices()); }
    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwWindowHint(II)V"))
    private void afv$noGlWindowHints(int hint, int value) {}
    @Inject(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/neoforged/fml/loading/ImmediateWindowHandler;setupMinecraftWindow(Ljava/util/function/IntSupplier;Ljava/util/function/IntSupplier;Ljava/util/function/Supplier;Ljava/util/function/LongSupplier;)J"))
    private void afv$noApiWindow(CallbackInfo ci) { GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_NO_API); }
    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwMakeContextCurrent(J)V"))
    private void afv$open(long window) { ZinkClientSession.open(window); }
    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL;createCapabilities()Lorg/lwjgl/opengl/GLCapabilities;"))
    private GLCapabilities afv$capabilities() { return GL.getCapabilities(); }
    @Inject(method = "updateVsync", at = @At("HEAD"), cancellable = true)
    private void afv$vsync(boolean enabled, CallbackInfo ci) { vsync = enabled; ZinkClientSession.setVsync(enabled); ci.cancel(); }
    @Inject(method = "<init>", at = @At("TAIL"))
    private void afv$observeWindow(CallbackInfo ci) { ZinkClientSession.observeWindow(((Window)(Object)this).getWindow()); }
    @Inject(method = "close", at = @At("HEAD"))
    private void afv$close(CallbackInfo ci) { ZinkClientSession.close(); }
}
