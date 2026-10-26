package dev.allforvulkan.neoforge.mixin;

import dev.allforvulkan.neoforge.IrisShadowHook;
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

/** Records the section draws Sodium submits while Iris is rendering shadows.
 * After the texture×tint replay has matched twice with the same camera and mesh, the GL draw is skipped. */
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer", remap = false)
public class SodiumShadowMeshMixin {
    @Inject(method = "render", at = @At("HEAD"), remap = false, cancellable = true, require = 1)
    private void afvCaptureShadowMesh(ChunkRenderMatrices matrices, CommandList commands, ChunkRenderListIterable lists,
                                     TerrainRenderPass pass, CameraTransform camera, CallbackInfo callback) {
        if (ShadowMeshCapture.retainDraw(matrices, lists, pass, camera) && IrisShadowHook.skipShadowGl()) {
            IrisShadowHook.noteSkip();
            callback.cancel();
        }
    }
}
