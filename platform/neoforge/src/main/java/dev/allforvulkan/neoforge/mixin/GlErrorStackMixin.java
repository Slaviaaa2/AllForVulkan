package dev.allforvulkan.neoforge.mixin;

import dev.allforvulkan.neoforge.GraphWhyProbe;
import com.mojang.blaze3d.platform.GlDebug;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prints the Java stack of the first GL API errors. Applied only with afv.graphWhy. */
@Mixin(GlDebug.class)
public class GlErrorStackMixin {
    @Inject(method = "printDebugLog", at = @At("HEAD"), require = 1)
    private static void afvError(int source, int type, int id, int severity, int length, long message, long user, CallbackInfo callback) {
        GraphWhyProbe.glError(type, id);
    }
}
