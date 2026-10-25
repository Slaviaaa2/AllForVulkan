package dev.allforvulkan.neoforge.mixin;

import dev.allforvulkan.neoforge.IrisShadowHook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Iris calls this from LevelRenderer after the shadow pass. The handler does nothing when the flag is off.
 * It does not cancel the pass.
 */
@Mixin(targets = "net.irisshaders.iris.pipeline.IrisRenderingPipeline", remap = false)
public class IrisShadowPassMixin {
    @Inject(method = "renderShadows", at = @At("HEAD"), remap = false, require = 1)
    private void afvBeforeShadows(CallbackInfo callback) {
        dev.allforvulkan.neoforge.ShadowMeshCapture.beginShadow();
    }

    @Inject(method = "renderShadows", at = @At("RETURN"), remap = false, require = 1)
    private void afvAfterShadows(CallbackInfo callback) {
        IrisShadowHook.afterShadows();
    }
}
