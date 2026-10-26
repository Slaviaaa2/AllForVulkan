package dev.allforvulkan.neoforge.mixin;

import dev.allforvulkan.neoforge.ShadowMeshCapture;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Records the section draws Sodium submits while Iris is rendering shadows. It does not cancel the draw. */
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer", remap = false)
public class SodiumShadowMeshMixin {
    @Inject(method = "render", at = @At("HEAD"), remap = false, require = 1)
    private void afvCaptureShadowMesh(ChunkRenderMatrices matrices, CommandList commands, ChunkRenderListIterable lists,
                                     TerrainRenderPass pass, CameraTransform camera, CallbackInfo callback) {
        ShadowMeshCapture.retainDraw(matrices, lists, pass, camera);
    }
}
