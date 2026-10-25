package dev.allforvulkan.bridge;

/** Bridge presentation defaults. Frame-pipeline depth above 1 stays opt-in. */
public final class PresentationPolicy {
    private PresentationPolicy() {}

    /** Async present overlaps vkQueuePresentKHR with the next GL frame.
     * An explicit property wins; otherwise the bridge default is on.
     * The frame pipeline already owns a worker, so it stays asynchronous. */
    public static boolean asyncPresent(String asyncProperty, String legacyProperty, boolean framePipeline) {
        if (framePipeline) return true;
        if (asyncProperty != null) return Boolean.parseBoolean(asyncProperty);
        if (legacyProperty != null) return Boolean.parseBoolean(legacyProperty);
        return true;
    }

    /** Zero when the frame pipeline is off. The default outstanding depth is 1. */
    public static int pipelineDepth(boolean framePipeline, String requested, int slots) {
        if (!framePipeline) return 0;
        if (slots < 1) throw new IllegalArgumentException("No presentation slots");
        int value = 1;
        if (requested != null && !requested.isBlank()) value = Integer.parseInt(requested.trim());
        return Math.max(1, Math.min(slots, value));
    }
}
