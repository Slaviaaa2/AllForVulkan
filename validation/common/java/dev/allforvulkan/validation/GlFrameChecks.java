package dev.allforvulkan.validation;

import org.lwjgl.system.MemoryStack;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL21.*;
import static org.lwjgl.opengl.GL45.*;

/** Tiny numeric diagnostic of the identified Minecraft color target, not transport. */
public final class GlFrameChecks {
    private GlFrameChecks() {}
    public static void sampleColor(int texture) {
        if (!org.lwjgl.opengl.GL.getCapabilities().OpenGL45) return;
        int width = glGetTextureLevelParameteri(texture, 0, GL_TEXTURE_WIDTH);
        int height = glGetTextureLevelParameteri(texture, 0, GL_TEXTURE_HEIGHT);
        if (width < 32 || height < 32) throw new IllegalStateException("Main GL target is smaller than the diagnostic ROI");
        int pbo = glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING);
        int[] names = {GL_PACK_ALIGNMENT, GL_PACK_ROW_LENGTH, GL_PACK_SKIP_ROWS, GL_PACK_SKIP_PIXELS, GL_PACK_IMAGE_HEIGHT, GL_PACK_SKIP_IMAGES};
        int[] old = new int[names.length];
        for (int i = 0; i < names.length; i++) old[i] = glGetInteger(names[i]);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
            for (int name : names) glPixelStorei(name, name == GL_PACK_ALIGNMENT ? 1 : 0);
            var pixels = stack.malloc(32 * 32 * 4);
            glGetTextureSubImage(texture, 0, width / 2 - 16, height / 2 - 16, 0, 32, 32, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
            int nonblack = 0, maximum = 0;
            for (int i = 0; i < pixels.capacity(); i += 4) {
                int color = (pixels.get(i) & 255) | (pixels.get(i + 1) & 255) | (pixels.get(i + 2) & 255);
                if (color > 0) nonblack++;
                maximum = Math.max(maximum, color);
            }
            if (nonblack == 0) throw new IllegalStateException("AFV_GL_FRAME_ROI_FAIL: Minecraft's center color target is entirely black");
            System.out.println("AFV_GL_FRAME_ROI_PASS: texture=" + texture + ", extent=" + width + "x" + height + ", nonblack=" + nonblack + "/1024, maxChannelBits=" + maximum);
        } finally {
            for (int i = 0; i < names.length; i++) glPixelStorei(names[i], old[i]);
            glBindBuffer(GL_PIXEL_PACK_BUFFER, pbo);
        }
    }
}
