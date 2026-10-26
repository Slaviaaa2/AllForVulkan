package net.caffeinemc.mods.sodium.client.util.iterator;

/** Compile-only stand-in. The game loads Sodium's class; this file is not packaged. */
public interface ByteIterator {
    boolean hasNext();
    int nextByteAsInt();
}
