package net.caffeinemc.mods.sodium.client.render.chunk.lists;

import java.util.Iterator;

/** Compile-only stand-in. The game loads Sodium's class; this file is not packaged. */
public interface ChunkRenderListIterable {
    Iterator<ChunkRenderList> iterator(boolean reverse);
}
