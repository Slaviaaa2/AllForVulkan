package dev.allforvulkan.bridge;

import org.lwjgl.system.MemoryStack;
import java.nio.IntBuffer;
import java.nio.ByteBuffer;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/** State touched by the shared-image diagnostic and its texture-sampling producer.
 * Restores actual driver state so Minecraft/other GL producers do not inherit it. */
public final class GlStateScope implements AutoCloseable {
    private final MemoryStack stack = MemoryStack.stackPush();
    private final int active = glGetInteger(GL_ACTIVE_TEXTURE);
    private final int sampler = glGetInteger(org.lwjgl.opengl.GL33.GL_SAMPLER_BINDING);
    private final int texture = glGetInteger(GL_TEXTURE_BINDING_2D);
    private final int draw = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING), read = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
    private final int program = glGetInteger(GL_CURRENT_PROGRAM), vao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
    private final IntBuffer viewport = stack.mallocInt(4), scissorBox = stack.mallocInt(4);
    private final ByteBuffer mask = stack.malloc(4);
    private final boolean scissor = glIsEnabled(GL_SCISSOR_TEST), blend = glIsEnabled(GL_BLEND), depth = glIsEnabled(GL_DEPTH_TEST);
    private final boolean cull = glIsEnabled(GL_CULL_FACE), srgb = glIsEnabled(GL_FRAMEBUFFER_SRGB);
    private final java.nio.FloatBuffer clear = stack.mallocFloat(4);
    public GlStateScope() {
        glGetIntegerv(GL_VIEWPORT, viewport);
        glGetIntegerv(GL_SCISSOR_BOX, scissorBox);
        glGetBooleanv(GL_COLOR_WRITEMASK, mask);
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
        glViewport(viewport.get(0), viewport.get(1), viewport.get(2), viewport.get(3));
        glScissor(scissorBox.get(0), scissorBox.get(1), scissorBox.get(2), scissorBox.get(3));
        glColorMask(mask.get(0) != 0, mask.get(1) != 0, mask.get(2) != 0, mask.get(3) != 0);
        glClearColor(clear.get(0), clear.get(1), clear.get(2), clear.get(3));
        enable(GL_SCISSOR_TEST, scissor); enable(GL_BLEND, blend); enable(GL_DEPTH_TEST, depth);
        enable(GL_CULL_FACE, cull); enable(GL_FRAMEBUFFER_SRGB, srgb);
        stack.close();
    }
    private static void enable(int capability, boolean enabled) {
        if (enabled) glEnable(capability); else glDisable(capability);
    }
}
