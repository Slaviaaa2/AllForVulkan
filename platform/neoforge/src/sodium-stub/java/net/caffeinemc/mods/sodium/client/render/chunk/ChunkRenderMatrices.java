package net.caffeinemc.mods.sodium.client.render.chunk;

import org.joml.Matrix4fc;

/** Compile-only stand-in. The game loads Sodium's class; this file is not packaged. */
public record ChunkRenderMatrices(Matrix4fc projection, Matrix4fc modelView) {}
