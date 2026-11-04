package net.caffeinemc.mods.sodium.client.render.chunk.region;

import net.caffeinemc.mods.sodium.client.gl.device.MultiDrawBatch;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;

/** Compile-only stand-in. The game loads Sodium's class; this file is not packaged. */
public class RenderRegion {
    public int getOriginX() { return 0; }
    public int getOriginY() { return 0; }
    public int getOriginZ() { return 0; }
    public RenderSection getSection(int index) { return null; }
    /** Sodium 0.8 only. */
    public MultiDrawBatch getCachedBatch(TerrainRenderPass pass) { return null; }
}
