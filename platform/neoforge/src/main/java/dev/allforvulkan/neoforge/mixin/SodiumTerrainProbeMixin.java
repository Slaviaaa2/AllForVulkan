package dev.allforvulkan.neoforge.mixin;

import dev.allforvulkan.neoforge.TerrainProbe;
import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Applied only when terrain measurement is requested on the pinned Sodium series. */
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer", remap = false)
public class SodiumTerrainProbeMixin {
    @Inject(method = "render", at = @At("HEAD"), remap = false, require = 1)
    private void afvBeginTerrain(ChunkRenderMatrices matrices, CommandList commands, ChunkRenderListIterable lists,
                                 TerrainRenderPass pass, CameraTransform camera, CallbackInfo callback) {
        TerrainProbe.beginPass(pass);
    }
    @Inject(method = "render", at = @At("RETURN"), remap = false, require = 1)
    private void afvEndTerrain(ChunkRenderMatrices matrices, CommandList commands, ChunkRenderListIterable lists,
                               TerrainRenderPass pass, CameraTransform camera, CallbackInfo callback) {
        TerrainProbe.endPass();
    }
}
