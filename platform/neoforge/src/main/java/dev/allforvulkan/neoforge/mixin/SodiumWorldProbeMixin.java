package dev.allforvulkan.neoforge.mixin;

import dev.allforvulkan.neoforge.TerrainWorkProbe;
import dev.allforvulkan.neoforge.TerrainWorkProbe.Stage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Applied only when terrain work measurement is requested on an exact supported Sodium version.
 * Both supported versions share these descriptors. */
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer", remap = false)
public class SodiumWorldProbeMixin {
    @Inject(method = "setupTerrain(Lnet/minecraft/client/Camera;Lnet/caffeinemc/mods/sodium/client/render/viewport/Viewport;ZZ)V", at = @At("HEAD"), remap = false, require = 1)
    private void afvBeginSetup(CallbackInfo callback) { TerrainWorkProbe.enter(Stage.SETUP); }
    @Inject(method = "setupTerrain(Lnet/minecraft/client/Camera;Lnet/caffeinemc/mods/sodium/client/render/viewport/Viewport;ZZ)V", at = @At("RETURN"), remap = false, require = 1)
    private void afvEndSetup(CallbackInfo callback) { TerrainWorkProbe.exit(Stage.SETUP); }
    @Inject(method = "drawChunkLayer(Lnet/minecraft/client/renderer/RenderType;Lnet/caffeinemc/mods/sodium/client/render/chunk/ChunkRenderMatrices;DDD)V", at = @At("HEAD"), remap = false, require = 1)
    private void afvBeginLayer(CallbackInfo callback) { TerrainWorkProbe.enter(Stage.LAYER); }
    @Inject(method = "drawChunkLayer(Lnet/minecraft/client/renderer/RenderType;Lnet/caffeinemc/mods/sodium/client/render/chunk/ChunkRenderMatrices;DDD)V", at = @At("RETURN"), remap = false, require = 1)
    private void afvEndLayer(CallbackInfo callback) { TerrainWorkProbe.exit(Stage.LAYER); }
}
