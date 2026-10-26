package net.caffeinemc.mods.sodium.client.render.viewport;

/** Compile-only stand-in. The game loads Sodium's class; this file is not packaged. */
public class CameraTransform {
    public final int intX;
    public final int intY;
    public final int intZ;
    public final float fracX;
    public final float fracY;
    public final float fracZ;

    public CameraTransform() {
        intX = intY = intZ = 0;
        fracX = fracY = fracZ = 0f;
    }
}
