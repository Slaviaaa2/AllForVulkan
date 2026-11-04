package dev.allforvulkan.neoforge.mixin;

import dev.allforvulkan.neoforge.TerrainWorkProbe;
import dev.allforvulkan.neoforge.TerrainWorkProbe.Stage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Applied only when terrain work measurement is requested on an exact supported Sodium version.
 * Both supported versions share these descriptors. */
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager", remap = false)
public class SodiumSectionsProbeMixin {
    @Inject(method = "update(Lnet/minecraft/client/Camera;Lnet/caffeinemc/mods/sodium/client/render/viewport/Viewport;Z)V", at = @At("HEAD"), remap = false, require = 1)
    private void afvBeginGraph(CallbackInfo callback) { TerrainWorkProbe.enter(Stage.GRAPH); }
    @Inject(method = "update(Lnet/minecraft/client/Camera;Lnet/caffeinemc/mods/sodium/client/render/viewport/Viewport;Z)V", at = @At("RETURN"), remap = false, require = 1)
    private void afvEndGraph(CallbackInfo callback) { TerrainWorkProbe.exit(Stage.GRAPH); }
    @Inject(method = "updateChunks(Z)V", at = @At("HEAD"), remap = false, require = 1)
    private void afvBeginChunks(CallbackInfo callback) { TerrainWorkProbe.enter(Stage.CHUNK_UPDATE); }
    @Inject(method = "updateChunks(Z)V", at = @At("RETURN"), remap = false, require = 1)
    private void afvEndChunks(CallbackInfo callback) { TerrainWorkProbe.exit(Stage.CHUNK_UPDATE); }
    @Inject(method = "uploadChunks()V", at = @At("HEAD"), remap = false, require = 1)
    private void afvBeginUpload(CallbackInfo callback) { TerrainWorkProbe.enter(Stage.CHUNK_UPLOAD); }
    @Inject(method = "uploadChunks()V", at = @At("RETURN"), remap = false, require = 1)
    private void afvEndUpload(CallbackInfo callback) { TerrainWorkProbe.exit(Stage.CHUNK_UPLOAD); }
}
