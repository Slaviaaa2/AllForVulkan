package dev.allforvulkan.neoforge.mixin;

import com.mojang.blaze3d.platform.Window;
import dev.allforvulkan.neoforge.NeoForgePlatformServices;
import net.neoforged.fml.loading.FMLConfig;
import net.vulkanmod.Initializer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Run GLFW/config initialization on the render thread, before Window uses it. */
@Mixin(Window.class)
public abstract class NeoForgeWindowMixin {
    @Inject(method = "<init>", at = @At("HEAD"))
    private static void afv$initialize(CallbackInfo ci) {
        if (FMLConfig.getBoolConfigValue(FMLConfig.ConfigValue.EARLY_WINDOW_CONTROL)) {
            throw new IllegalStateException("All For Vulkan requires earlyWindowControl=false in config/fml.toml; the OpenGL early window cannot become a Vulkan surface.");
        }
        Initializer.initialize(new NeoForgePlatformServices());
    }
}
