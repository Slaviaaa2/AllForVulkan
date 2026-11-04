package dev.allforvulkan.neoforge.mixin;

import dev.allforvulkan.neoforge.TerrainProbe;
import dev.allforvulkan.neoforge.TerrainWorkProbe;
import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
import net.caffeinemc.mods.sodium.client.gl.device.DrawCommandList;
import net.caffeinemc.mods.sodium.client.gl.device.MultiDrawBatch;
import net.caffeinemc.mods.sodium.client.gl.tessellation.GlIndexType;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Applied only when terrain measurement is requested on exactly Sodium 0.6.13. */
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer", remap = false)
public class SodiumTerrainProbeMixin {
    @Inject(method = "render(Lnet/caffeinemc/mods/sodium/client/render/chunk/ChunkRenderMatrices;Lnet/caffeinemc/mods/sodium/client/gl/device/CommandList;Lnet/caffeinemc/mods/sodium/client/render/chunk/lists/ChunkRenderListIterable;Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;Lnet/caffeinemc/mods/sodium/client/render/viewport/CameraTransform;)V",
            at = @At("HEAD"), remap = false, require = 1)
    private void afvBeginTerrain(ChunkRenderMatrices matrices, CommandList commands, ChunkRenderListIterable lists,
                                 TerrainRenderPass pass, CameraTransform camera, CallbackInfo callback) {
        TerrainWorkProbe.beginRender(pass, lists);
        TerrainProbe.beginPass(pass);
    }
    @Inject(method = "render(Lnet/caffeinemc/mods/sodium/client/render/chunk/ChunkRenderMatrices;Lnet/caffeinemc/mods/sodium/client/gl/device/CommandList;Lnet/caffeinemc/mods/sodium/client/render/chunk/lists/ChunkRenderListIterable;Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;Lnet/caffeinemc/mods/sodium/client/render/viewport/CameraTransform;)V",
            at = @At("RETURN"), remap = false, require = 1)
    private void afvEndTerrain(ChunkRenderMatrices matrices, CommandList commands, ChunkRenderListIterable lists,
                               TerrainRenderPass pass, CameraTransform camera, CallbackInfo callback) {
        TerrainProbe.endPass();
        TerrainWorkProbe.endRender(pass, replayed -> ((DefaultChunkRenderer) (Object) this).render(matrices, commands, lists, replayed, camera));
    }
    @Inject(method = "fillCommandBuffer(Lnet/caffeinemc/mods/sodium/client/gl/device/MultiDrawBatch;Lnet/caffeinemc/mods/sodium/client/render/chunk/region/RenderRegion;Lnet/caffeinemc/mods/sodium/client/render/chunk/data/SectionRenderDataStorage;Lnet/caffeinemc/mods/sodium/client/render/chunk/lists/ChunkRenderList;Lnet/caffeinemc/mods/sodium/client/render/viewport/CameraTransform;Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;Z)V",
            at = @At("HEAD"), remap = false, require = 1)
    private static void afvBeginFill(CallbackInfo callback) { TerrainWorkProbe.beginFill(); }
    @Inject(method = "fillCommandBuffer(Lnet/caffeinemc/mods/sodium/client/gl/device/MultiDrawBatch;Lnet/caffeinemc/mods/sodium/client/render/chunk/region/RenderRegion;Lnet/caffeinemc/mods/sodium/client/render/chunk/data/SectionRenderDataStorage;Lnet/caffeinemc/mods/sodium/client/render/chunk/lists/ChunkRenderList;Lnet/caffeinemc/mods/sodium/client/render/viewport/CameraTransform;Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;Z)V",
            at = @At("RETURN"), remap = false, require = 1)
    private static void afvEndFill(CallbackInfo callback) { TerrainWorkProbe.endFill(); }
    @Inject(method = {"addNonIndexedDrawCommands(Lnet/caffeinemc/mods/sodium/client/gl/device/MultiDrawBatch;JI)V",
            "addIndexedDrawCommands(Lnet/caffeinemc/mods/sodium/client/gl/device/MultiDrawBatch;JI)V"},
            at = @At("HEAD"), remap = false, require = 2)
    private static void afvSection(MultiDrawBatch batch, long meshData, int mask, CallbackInfo callback) { TerrainWorkProbe.section(batch); }
    @Redirect(method = "executeDrawBatch(Lnet/caffeinemc/mods/sodium/client/gl/device/CommandList;Lnet/caffeinemc/mods/sodium/client/gl/tessellation/GlTessellation;Lnet/caffeinemc/mods/sodium/client/gl/device/MultiDrawBatch;)V",
            at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/gl/device/DrawCommandList;multiDrawElementsBaseVertex(Lnet/caffeinemc/mods/sodium/client/gl/device/MultiDrawBatch;Lnet/caffeinemc/mods/sodium/client/gl/tessellation/GlIndexType;)V"),
            remap = false, require = 1)
    private static void afvDraw(DrawCommandList list, MultiDrawBatch batch, GlIndexType type) { TerrainWorkProbe.multiDraw(list, batch, type); }
}
