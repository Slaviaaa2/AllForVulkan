package dev.allforvulkan.validation;

/** Explicit private-client scenario. Rebuild real Iris pipelines without restarting the world. */
public final class IrisLifecycle {
    private final String pack;
    private Object previousPipeline;
    private int step, changedTick;
    private long changedFrame;

    public IrisLifecycle(int tick, long frames, Runnable resizeWithoutShaders) {
        previousPipeline = IrisAssertions.requireActive();
        pack = IrisAssertions.configuredPack();
        IrisAssertions.setEnabled(false);
        resizeWithoutShaders.run();
        changedTick = tick; changedFrame = frames;
        System.out.println("AFV_IRIS_CYCLE_BEGIN: disable shaders and resize in the same world");
    }

    public boolean tick(int tick, long frames, int colorTexture) {
        // Require rendered frames in each mode, not just a successful setter.
        if (tick - changedTick < 60 || frames - changedFrame < 60) return false;
        if (!pack.equals(IrisAssertions.configuredPack())) throw new IllegalStateException("Iris changed the selected shader pack during its cycle");
        if (step == 0) {
            IrisAssertions.requireDisabled();
            GlFrameChecks.sampleColor(colorTexture);
            System.out.println("AFV_IRIS_CYCLE_OFF_PASS: resized world rendered with shaders disabled");
            IrisAssertions.setEnabled(true);
        } else {
            Object pipeline = IrisAssertions.requireActive();
            if (pipeline == previousPipeline) throw new IllegalStateException("Iris reused a destroyed pipeline after cycle step " + step);
            previousPipeline = pipeline;
            GlFrameChecks.sampleColor(colorTexture);
            if (step == 1) {
                System.out.println("AFV_IRIS_CYCLE_ON_PASS: selected pack rebuilt and rendered");
                IrisAssertions.reload();
            } else {
                System.out.println("AFV_IRIS_CYCLE_PASS: off, resize, on, shader-only reload; distinct real pipelines, pack=" + pack);
                return true;
            }
        }
        step++;
        changedTick = tick; changedFrame = frames;
        return false;
    }
}
