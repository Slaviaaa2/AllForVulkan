package dev.allforvulkan.bridge;

import org.lwjgl.system.MemoryStack;
import java.nio.IntBuffer;
import java.nio.FloatBuffer;
import java.nio.ByteBuffer;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL41.*;

/** State touched by shared-image presentation and its texture-sampling producer.
 * Restores actual driver state so Minecraft/other GL producers do not inherit it. */
public final class GlStateScope implements AutoCloseable {
    private final MemoryStack stack = MemoryStack.stackPush();
    private final int active = glGetInteger(GL_ACTIVE_TEXTURE);
    private final int sampler = glGetInteger(org.lwjgl.opengl.GL33.GL_SAMPLER_BINDING);
    private final int texture = glGetInteger(GL_TEXTURE_BINDING_2D);
    private final int draw = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING), read = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
    private final int program = glGetInteger(GL_CURRENT_PROGRAM), vao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
    private final int viewports = glGetInteger(GL_MAX_VIEWPORTS);
    private final FloatBuffer viewport = stack.mallocFloat(viewports * 4);
    private final IntBuffer scissorBox = stack.mallocInt(viewports * 4);
    private final boolean[] scissors = new boolean[viewports];
    private final int drawBuffers = glGetInteger(GL_MAX_DRAW_BUFFERS);
    private final ByteBuffer masks = stack.malloc(drawBuffers * 4);
    private final boolean[] blends = new boolean[drawBuffers];
    private final boolean depth = glIsEnabled(GL_DEPTH_TEST);
    private final boolean dither = glIsEnabled(GL_DITHER);
    private final boolean cull = glIsEnabled(GL_CULL_FACE), srgb = glIsEnabled(GL_FRAMEBUFFER_SRGB);
    private final java.nio.FloatBuffer clear = stack.mallocFloat(4);
    public GlStateScope() {
        for (int i = 0; i < viewports; i++) {
            glGetFloati_v(GL_VIEWPORT, i, viewport.slice(i * 4, 4));
            glGetIntegeri_v(GL_SCISSOR_BOX, i, scissorBox.slice(i * 4, 4));
            scissors[i] = glIsEnabledi(GL_SCISSOR_TEST, i);
        }
        for (int i = 0; i < drawBuffers; i++) {
            glGetBooleani_v(GL_COLOR_WRITEMASK, i, masks.slice(i * 4, 4));
            blends[i] = glIsEnabledi(GL_BLEND, i);
        }
        glGetFloatv(GL_COLOR_CLEAR_VALUE, clear);
    }
    @Override public void close() {
        glActiveTexture(active);
        glBindTexture(GL_TEXTURE_2D, texture);
        org.lwjgl.opengl.GL33.glBindSampler(active - GL_TEXTURE0, sampler);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, draw);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, read);
        glUseProgram(program);
        glBindVertexArray(vao);
        glViewportArrayv(0, viewport);
        glScissorArrayv(0, scissorBox);
        for (int i = 0; i < viewports; i++) {
            if (scissors[i]) glEnablei(GL_SCISSOR_TEST, i); else glDisablei(GL_SCISSOR_TEST, i);
        }
        // Non-indexed ColorMask/Enable(BLEND) affect every draw buffer.
        // Restoring buffer 0 through those APIs would destroy MRT state.
        for (int i = 0; i < drawBuffers; i++) {
            int base = i * 4;
            glColorMaski(i, masks.get(base) != 0, masks.get(base + 1) != 0, masks.get(base + 2) != 0, masks.get(base + 3) != 0);
            if (blends[i]) glEnablei(GL_BLEND, i); else glDisablei(GL_BLEND, i);
        }
        glClearColor(clear.get(0), clear.get(1), clear.get(2), clear.get(3));
        enable(GL_DEPTH_TEST, depth);
        enable(GL_DITHER, dither); enable(GL_CULL_FACE, cull); enable(GL_FRAMEBUFFER_SRGB, srgb);
        stack.close();
    }
    private static void enable(int capability, boolean enabled) {
        if (enabled) glEnable(capability); else glDisable(capability);
    }
}
