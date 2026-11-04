package net.caffeinemc.mods.sodium.client.gl.device;

import net.caffeinemc.mods.sodium.client.gl.tessellation.GlIndexType;

/** Compile-only stand-in. The game loads Sodium's class; this file is not packaged. */
public interface DrawCommandList {
    void multiDrawElementsBaseVertex(MultiDrawBatch batch, GlIndexType indexType);
}
