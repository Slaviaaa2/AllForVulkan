package dev.allforvulkan.bridge;

/** Release frames do not drain glGetError. Setup and --debug-gl still do. */
public final class GlDiagnostics {
    private GlDiagnostics() {}

    public static boolean errorsEnabled() {
        return Boolean.getBoolean("afv.debugGlClient");
    }
}
