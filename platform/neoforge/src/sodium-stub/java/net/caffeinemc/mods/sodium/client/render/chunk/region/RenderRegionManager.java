package net.caffeinemc.mods.sodium.client.render.chunk.region;

import java.util.Collection;
import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.BuilderTaskOutput;

/** Compile-only stand-in. The game loads Sodium's class; this file is not packaged. */
public class RenderRegionManager {
    public void uploadResults(CommandList commands, Collection<BuilderTaskOutput> outputs) {}
}
