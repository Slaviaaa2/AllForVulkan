package dev.allforvulkan.framegraph;

/** Lavapipe or another Vulkan device. Missing devices skip unless afv.requireCompositeDevice is set. */
public final class CompositeChainChecks {
    public static void main(String[] args) {
        var result = CompositeChain.execute();
        if (result.skipped()) {
            if (Boolean.getBoolean("afv.requireCompositeDevice"))
                throw new IllegalStateException(result.detail());
            System.out.println("AFV_COMPOSITE_CHAIN_SKIP: " + result.detail());
            return;
        }
        if (!result.passed()) throw new IllegalStateException(result.detail());
        System.out.println("AFV_COMPOSITE_CHAIN_PASS: " + result.detail());
    }
}
