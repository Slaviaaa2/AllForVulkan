package dev.allforvulkan.validation;

import java.util.Optional;

/** Only loaded when the explicit test requests an actual enabled Iris pack. */
public final class IrisAssertions {
    private IrisAssertions() {}
    public static Object requireActive() {
        try {
            Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
            Optional<?> error = (Optional<?>) iris.getMethod("getStoredError").invoke(null);
            if (error.isPresent()) throw new IllegalStateException("Iris stored a shader error", (Throwable) error.get());
            if (!(boolean)iris.getMethod("isPackInUseQuick").invoke(null)) throw new IllegalStateException("Iris fell back to shaders off");
            Object manager = iris.getMethod("getPipelineManager").invoke(null);
            Object pipeline = ((Optional<?>)manager.getClass().getMethod("getPipeline").invoke(manager)).orElseThrow();
            if (pipeline.getClass().getSimpleName().contains("Vanilla")) throw new IllegalStateException("Iris selected its vanilla pipeline");
            System.out.println("AFV_IRIS_ACTIVE_PIPELINE=" + pipeline.getClass().getName());
            return pipeline;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot validate Iris pipeline", failure);
        }
    }

    public static void requireDisabled() {
        try {
            Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
            Optional<?> error = (Optional<?>) iris.getMethod("getStoredError").invoke(null);
            if (error.isPresent()) throw new IllegalStateException("Iris stored an error while disabling shaders", (Throwable) error.get());
            if ((boolean) iris.getMethod("isPackInUseQuick").invoke(null)) throw new IllegalStateException("Iris pack stayed active after disabling shaders");
            Object manager = iris.getMethod("getPipelineManager").invoke(null);
            Object pipeline = ((Optional<?>) manager.getClass().getMethod("getPipeline").invoke(manager)).orElseThrow();
            if (!pipeline.getClass().getSimpleName().contains("Vanilla")) throw new IllegalStateException("Iris did not select its vanilla pipeline");
            System.out.println("AFV_IRIS_DISABLED_PIPELINE=" + pipeline.getClass().getName());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot validate disabled Iris pipeline", failure);
        }
    }

    /** Use Iris's public API, including its normal save/rebuild path. */
    public static void setEnabled(boolean enabled) {
        try {
            Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Object instance = api.getMethod("getInstance").invoke(null);
            Object config = api.getMethod("getConfig").invoke(instance);
            Class<?> configApi = Class.forName("net.irisshaders.iris.api.v0.IrisApiConfig");
            configApi.getMethod("setShadersEnabledAndApply", boolean.class).invoke(config, enabled);
            if ((boolean) configApi.getMethod("areShadersEnabled").invoke(config) != enabled)
                throw new IllegalStateException("Iris did not apply shaders enabled=" + enabled);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot change Iris shaders through its public API", failure);
        }
    }

    public static String configuredPack() {
        try {
            Object config = Class.forName("net.irisshaders.iris.Iris").getMethod("getIrisConfig").invoke(null);
            return (String) ((Optional<?>) config.getClass().getMethod("getShaderPackName").invoke(config)).orElseThrow();
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot read configured Iris pack", failure);
        }
    }

    public static void reload() {
        try { Class.forName("net.irisshaders.iris.Iris").getMethod("reload").invoke(null); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException("Cannot reload Iris shaders", failure); }
    }
}
