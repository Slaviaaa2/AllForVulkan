package dev.allforvulkan.validation;

import java.util.Optional;

/** Only loaded when the explicit test requests an actual enabled Iris pack. */
public final class IrisAssertions {
    private IrisAssertions() {}
    public static void requireActive() {
        try {
            Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
            Optional<?> error = (Optional<?>) iris.getMethod("getStoredError").invoke(null);
            if (error.isPresent()) throw new IllegalStateException("Iris stored a shader error", (Throwable) error.get());
            if (!(boolean)iris.getMethod("isPackInUseQuick").invoke(null)) throw new IllegalStateException("Iris fell back to shaders off");
            Object manager = iris.getMethod("getPipelineManager").invoke(null);
            Object pipeline = ((Optional<?>)manager.getClass().getMethod("getPipeline").invoke(manager)).orElseThrow();
            if (pipeline.getClass().getSimpleName().contains("Vanilla")) throw new IllegalStateException("Iris selected its vanilla pipeline");
            System.out.println("AFV_IRIS_ACTIVE_PIPELINE=" + pipeline.getClass().getName());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot validate Iris pipeline", failure);
        }
    }
}
