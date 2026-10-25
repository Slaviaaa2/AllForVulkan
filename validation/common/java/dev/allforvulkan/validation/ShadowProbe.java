package dev.allforvulkan.validation;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import org.lwjgl.opengl.GL11;

/** Reads Iris shadowcolor0 after the world has rendered. The Vulkan hook is not required. */
public final class ShadowProbe {
    private static String summary = "";
    private static String formatName = "unknown";
    private static boolean loggedWaiting;

    private ShadowProbe() {}

    public static String summary() { return summary; }

    /** False until Iris has a shadow color texture. Throws if that texture cannot be read. */
    public static boolean capture() {
        int texture;
        int resolution;
        try {
            Object pipeline = IrisAssertions.requireActive();
            Object targets = field(pipeline, "shadowRenderTargets");
            if (targets == null) {
                waiting("Iris has no shadow targets yet");
                return false;
            }
            texture = (int) targets.getClass().getMethod("getColorTextureId", int.class).invoke(targets, 0);
            resolution = (int) targets.getClass().getMethod("getResolution").invoke(targets);
            Object format = targets.getClass().getMethod("getColorTextureFormat", int.class).invoke(targets, 0);
            formatName = format == null ? "unknown" : format.toString();
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("AFV_SHADOW_PROBE_FAIL: cannot reach Iris shadow targets", failure);
        }
        if (texture <= 0 || resolution <= 0) {
            waiting("shadow texture is not allocated");
            return false;
        }
        byte[] rgba = read(texture, resolution);
        Path output = Path.of("afv-shadow-off.rgba");
        String hook = System.getProperty("afv.irisShadow", "false");
        if (Boolean.parseBoolean(hook)) output = Path.of("afv-shadow-on.rgba");
        try {
            ShadowImage.write(output, resolution, resolution, rgba);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("AFV_SHADOW_PROBE_FAIL: cannot write " + output, failure);
        }
        int nonzero = 0;
        int cx = resolution / 2;
        int index = (cx * resolution + cx) * 4;
        for (int i = 0; i < rgba.length; i += 4)
            if ((rgba[i] | rgba[i + 1] | rgba[i + 2]) != 0) nonzero++;
        summary = "width=" + resolution + " height=" + resolution
                + " format=" + formatName
                + " hook=" + Boolean.parseBoolean(hook)
                + " file=" + output
                + " nonzero=" + nonzero + "/" + (resolution * resolution)
                + " center=" + (rgba[index] & 255) + "," + (rgba[index + 1] & 255) + ","
                + (rgba[index + 2] & 255) + "," + (rgba[index + 3] & 255);
        return true;
    }

    private static void waiting(String reason) {
        if (!loggedWaiting) {
            System.out.println("AFV_SHADOW_PROBE_WAIT: " + reason);
            loggedWaiting = true;
        }
    }

    private static Object field(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static byte[] read(int texture, int resolution) {
        int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int pack = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
        while (GL11.glGetError() != GL11.GL_NO_ERROR) { /* drop errors from the frame before this read */ }
        ByteBuffer pixels = org.lwjgl.BufferUtils.createByteBuffer(resolution * resolution * 4);
        try {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
            int error = GL11.glGetError();
            if (error != GL11.GL_NO_ERROR) throw new IllegalStateException("glGetTexImage failed: " + error);
            pixels.limit(pixels.capacity());
            pixels.position(0);
        } finally {
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, pack);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous);
        }
        byte[] rgba = new byte[pixels.remaining()];
        pixels.get(rgba);
        return rgba;
    }
}
