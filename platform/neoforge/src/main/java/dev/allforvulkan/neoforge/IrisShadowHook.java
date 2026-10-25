package dev.allforvulkan.neoforge;

import dev.allforvulkan.framegraph.ShadowConstantPass;
import dev.allforvulkan.validation.ShadowImage;
import dev.allforvulkan.validation.ShadowProbe;
import java.nio.ByteBuffer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.vulkanmod.Initializer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

/**
 * After Iris has drawn its shadow pass, optionally replace shadowcolor0 with a Vulkan image.
 * The flag defaults off. A mismatch or a device failure leaves Iris's image in place.
 * This does not cancel {@code renderShadows}.
 */
@EventBusSubscriber(modid = "allforvulkan", value = Dist.CLIENT)
public final class IrisShadowHook {
    private static boolean loggedSite;
    private static boolean loggedPass;
    private static boolean loggedFallback;
    private static String lastFallback = "";
    private static int fallbackLines;
    private static int loggedMismatch = -2;
    private static int mismatchLogs;
    private static boolean matched;
    private static String lastResult = "none";

    public static boolean matched() { return matched; }

    public static String lastResult() { return lastResult; }

    private IrisShadowHook() {}

    /** Sodium may render the world without calling the pipeline method. The frame event still sees the texture. */
    @SubscribeEvent
    public static void afterFrame(RenderFrameEvent.Post event) {
        afterShadows();
    }

    public static void afterShadows() {
        if (!loggedSite) {
            System.out.println("AFV_IRIS_SHADOW_HOOK");
            loggedSite = true;
        }
        if (!BackendBootstrap.irisShadow()) return;
        try {
            replace();
        } catch (RuntimeException failure) {
            matched = false;
            fallback(failure.getMessage());
        }
    }

    private static void replace() {
        ShadowProbe.Frame frame = ShadowProbe.readFrame();
        if (frame == null) return;
        if (Boolean.getBoolean("afv.irisShadowForceFallback")) {
            matched = false;
            fallback("forced");
            return;
        }
        var mesh = ShadowMeshReplay.render(frame.resolution());
        if (mesh.subset()) {
            byte[] chosen = ShadowImage.replacementNear(frame.rgba(), mesh.rgba(), frame.resolution(), frame.resolution());
            if (chosen != mesh.rgba()) {
                matched = false;
                int mismatches = mesh.rgba() == null ? -1
                        : ShadowImage.nearMismatches(frame.rgba(), mesh.rgba(), frame.resolution(), frame.resolution(), ShadowImage.TOLERANCE);
                if (mesh.rgba() != null && mismatches != loggedMismatch && mismatchLogs < 5) {
                    loggedMismatch = mismatches;
                    mismatchLogs++;
                    System.out.println("AFV_SHADOW_MESH_MISMATCH:" + ShadowImage.nearMismatchSummary(
                            frame.rgba(), mesh.rgba(), frame.resolution(), frame.resolution(), ShadowImage.TOLERANCE, 8));
                }
                lastResult = "mismatch " + mismatches + (mesh.rgba() == null ? ""
                        : ShadowImage.bestShift(frame.rgba(), mesh.rgba(), frame.resolution(), frame.resolution(), ShadowImage.TOLERANCE));
                fallback(mesh.rgba() == null ? mesh.detail() : "mesh mismatch " + mismatches + " " + mesh.detail());
                return;
            }
            matched = true;
            lastResult = "match";
            upload(frame.texture(), frame.resolution(), chosen);
            if (!loggedPass) {
                String meshLine = "AFV_IRIS_SHADOW_MESH: " + mesh.detail();
                Initializer.LOGGER.info(meshLine);
                System.out.println(meshLine);
            }
            pass("AFV_IRIS_SHADOW_PASS: " + mesh.detail());
            return;
        }
        var image = ShadowConstantPass.render(frame.resolution());
        byte[] chosen = ShadowImage.replacement(frame.rgba(), image.rgba(), frame.resolution(), frame.resolution());
        if (chosen != image.rgba()) {
            matched = false;
            fallback(image.rgba() == null ? image.detail() : "center mismatch");
            return;
        }
        matched = true;
        upload(frame.texture(), frame.resolution(), chosen);
        pass("AFV_IRIS_SHADOW_PASS: " + image.detail());
    }

    private static void pass(String summary) {
        if (!loggedPass) {
            loggedPass = true;
            Initializer.LOGGER.info(summary);
            System.out.println(summary);
        }
    }

    private static void fallback(String reason) {
        if (loggedPass || fallbackLines >= 8) return;
        String summary = "AFV_IRIS_SHADOW_FALLBACK: " + (reason == null || reason.isBlank() ? "unavailable" : reason);
        if (summary.equals(lastFallback)) return;
        lastFallback = summary;
        loggedFallback = true;
        fallbackLines++;
        Initializer.LOGGER.info(summary);
        System.out.println(summary);
    }

    private static void upload(int texture, int resolution, byte[] rgba) {
        int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int unpack = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);
        while (GL11.glGetError() != GL11.GL_NO_ERROR) { /* drop errors from the shadow pass */ }
        ByteBuffer pixels = MemoryUtil.memAlloc(rgba.length);
        try {
            pixels.put(rgba).flip();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, resolution, resolution,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
            int error = GL11.glGetError();
            if (error != GL11.GL_NO_ERROR) throw new IllegalStateException("glTexSubImage2D failed: " + error);
        } finally {
            MemoryUtil.memFree(pixels);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, unpack);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous);
        }
    }
}
