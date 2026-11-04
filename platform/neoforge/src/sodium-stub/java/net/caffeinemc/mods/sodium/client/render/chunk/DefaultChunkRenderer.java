package net.caffeinemc.mods.sodium.client.render.chunk;

import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;

/** Compile-only stand-in. The game loads Sodium's class; this file is not packaged.
 * Sodium 0.6 has the first render method, Sodium 0.8 the second. */
public class DefaultChunkRenderer extends ShaderChunkRenderer {
    public void render(ChunkRenderMatrices matrices, CommandList commands, ChunkRenderListIterable lists,
                       TerrainRenderPass pass, CameraTransform camera) {}
    public void render(ChunkRenderMatrices matrices, CommandList commands, ChunkRenderListIterable lists,
                       TerrainRenderPass pass, CameraTransform camera, boolean indexed) {}
}
