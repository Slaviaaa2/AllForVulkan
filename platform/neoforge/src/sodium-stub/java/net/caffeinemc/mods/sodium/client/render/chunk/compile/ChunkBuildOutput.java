package net.caffeinemc.mods.sodium.client.render.chunk.compile;

import java.util.Map;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionMeshParts;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;

/** Compile-only stand-in. The game loads Sodium's class; this file is not packaged. */
public class ChunkBuildOutput extends BuilderTaskOutput {
    public final Map<TerrainRenderPass, BuiltSectionMeshParts> meshes = null;
}
