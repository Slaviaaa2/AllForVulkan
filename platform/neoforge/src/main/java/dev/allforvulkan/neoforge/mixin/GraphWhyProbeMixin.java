package dev.allforvulkan.neoforge.mixin;

import dev.allforvulkan.neoforge.GraphWhyProbe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Explains why Sodium's graph search runs. Applied only with afv.graphWhy on Sodium 0.8.13. */
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager", remap = false)
public class GraphWhyProbeMixin {
    @Inject(method = "markGraphDirty()V", at = @At("HEAD"), remap = false, require = 1)
    private void afvDirty(CallbackInfo callback) { GraphWhyProbe.dirty(); }
    @Inject(method = "update(Lnet/minecraft/client/Camera;Lnet/caffeinemc/mods/sodium/client/render/viewport/Viewport;Z)V", at = @At("HEAD"), remap = false, require = 1)
    private void afvUpdate(CallbackInfo callback) { GraphWhyProbe.update(); }
    @Inject(method = "createTerrainRenderList(Lnet/minecraft/client/Camera;Lnet/caffeinemc/mods/sodium/client/render/viewport/Viewport;IZ)Z", at = @At("RETURN"), remap = false, require = 1)
    private void afvRevisit(CallbackInfoReturnable<Boolean> result) { GraphWhyProbe.revisit(result.getReturnValue()); }
    @Inject(method = "processChunkBuildResults(Ljava/util/ArrayList;)Z", at = @At("RETURN"), remap = false, require = 1)
    private void afvBuilt(CallbackInfoReturnable<Boolean> result) { GraphWhyProbe.built(result.getReturnValue()); }
}
