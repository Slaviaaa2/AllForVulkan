package dev.allforvulkan.framegraph;

/** Chooses the Iris shader execution path. The direct slice does not replace
 * a running GL client; callers keep the current renderer and record why. */
public final class ShaderBackendSelection {
    public record Decision(String requested, String active, String coverage, String fallback) {
        public boolean fellBack() { return !fallback.isEmpty(); }
    }

    private ShaderBackendSelection() {}

    public static Decision choose(String requested, boolean thirdPartyGlClient) {
        return choose(requested, thirdPartyGlClient, IrisPackPlan.empty());
    }

    /** A pack plan names programs the scheduler understands. World draws stay on Zink
     * until those passes have a mesh source, and unsupported programs stay on Zink too. */
    public static Decision choose(String requested, boolean thirdPartyGlClient, IrisPackPlan plan) {
        String normalized = requested == null ? "zink" : requested.trim().toLowerCase(java.util.Locale.ROOT);
        if (!normalized.equals("direct")) return new Decision("zink", "zink", "zink-driver", "");
        if (!thirdPartyGlClient)
            return new Decision("direct", "zink", "composite-chain",
                    "the direct slice is not the native terrain renderer; this launch keeps the current renderer");
        if (plan == null || plan.supported().isEmpty())
            return new Decision("direct", "zink", "composite-chain",
                    "Iris/Sodium passes are outside the direct composite-chain slice; the GL client stays on Zink");
        String blocked = plan.blockedSummary();
        String fallback = "world draws stay on Zink" + (blocked.isEmpty() ? "" : "; unsupported programs: " + blocked);
        return new Decision("direct", "zink", "scheduled:" + plan.supportedSummary(), fallback);
    }
}
