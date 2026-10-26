package dev.allforvulkan.neoforge.mixin;

import dev.allforvulkan.neoforge.IrisShadowHook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Iris calls this from LevelRenderer after the shadow pass. The handler does nothing when the flag is off.
 * It does not cancel the pass.
 */
@Mixin(targets = "net.irisshaders.iris.pipeline.IrisRenderingPipeline", remap = false)
public class IrisShadowPassMixin {
    private static int shadowStateLogs;
    private static String lastShadowState = "";

    @Inject(method = "renderShadows", at = @At("HEAD"), remap = false, require = 1)
    private void afvBeforeShadows(CallbackInfo callback) {
        dev.allforvulkan.neoforge.ShadowMeshCapture.beginShadow();
    }

    @Inject(method = "renderShadows", at = @At("RETURN"), remap = false, require = 1)
    private void afvAfterShadows(CallbackInfo callback) {
        if (Boolean.getBoolean("afv.shadowMesh")) logShadowState();
        IrisShadowHook.afterShadows();
    }

    /** Iris's own shadow renderer knows whether the pass returned before drawing terrain. */
    private void logShadowState() {
        if (shadowStateLogs >= 6) return;
        try {
            var rendererField = getClass().getDeclaredField("shadowRenderer");
            rendererField.setAccessible(true);
            Object renderer = rendererField.get(this);
            Class<?> settings = Class.forName("net.irisshaders.iris.gui.option.IrisVideoSettings");
            int slider = settings.getField("shadowDistance").getInt(null);
            int overridden = (int) settings.getMethod("getOverriddenShadowDistance", int.class).invoke(null, slider);
            String line;
            if (renderer == null) {
                line = "AFV_SHADOW_STATE: renderer=null slider=" + slider + " overridden=" + overridden;
            } else {
                boolean terrain = fieldBoolean(renderer, "shouldRenderTerrain");
                String debug = String.valueOf(field(renderer, "debugStringTerrain"));
                Object frustum = field(renderer, "terrainFrustumHolder");
                String distance = frustum == null ? "none" : String.valueOf(frustum.getClass().getMethod("getDistanceInfo").invoke(frustum));
                String culling = frustum == null ? "none" : String.valueOf(frustum.getClass().getMethod("getCullingInfo").invoke(frustum));
                line = "AFV_SHADOW_STATE: slider=" + slider + " overridden=" + overridden
                        + " terrain=" + terrain + " debug=" + debug
                        + " distance=" + distance + " culling=" + culling;
            }
            if (line.equals(lastShadowState)) return;
            lastShadowState = line;
            shadowStateLogs++;
            System.out.println(line);
        } catch (ReflectiveOperationException failure) {
            shadowStateLogs = 6;
            System.out.println("AFV_SHADOW_STATE: " + failure.getClass().getSimpleName() + " " + failure.getMessage());
        }
    }

    private static Object field(Object target, String name) throws ReflectiveOperationException {
        var found = target.getClass().getDeclaredField(name);
        found.setAccessible(true);
        return found.get(target);
    }

    private static boolean fieldBoolean(Object target, String name) throws ReflectiveOperationException {
        var found = target.getClass().getDeclaredField(name);
        found.setAccessible(true);
        return found.getBoolean(target);
    }
}
