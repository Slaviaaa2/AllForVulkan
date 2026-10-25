package dev.allforvulkan.framegraph;

/** Chooses the Iris shader execution path. The direct slice does not replace
 * a running GL client; callers keep the current renderer and record why. */
public final class ShaderBackendSelection {
    public record Decision(String requested, String active, String coverage, String fallback) {
        public boolean fellBack() { return !fallback.isEmpty(); }
    }

    private ShaderBackendSelection() {}

    public static Decision choose(String requested, boolean thirdPartyGlClient) {
        String normalized = requested == null ? "zink" : requested.trim().toLowerCase(java.util.Locale.ROOT);
        if (!normalized.equals("direct")) return new Decision("zink", "zink", "zink-driver", "");
        String reason = thirdPartyGlClient
                ? "Iris/Sodium passes are outside the direct composite-chain slice; the GL client stays on Zink"
                : "the direct slice is not the native terrain renderer; this launch keeps the current renderer";
        return new Decision("direct", "zink", "composite-chain", reason);
    }
}
