package net.caffeinemc.mods.sodium.client.util;

import java.nio.ByteBuffer;

/** Compile-only stand-in. The game loads Sodium's class; this file is not packaged. */
public class NativeBuffer {
    public ByteBuffer getDirectBuffer() { return null; }
}
