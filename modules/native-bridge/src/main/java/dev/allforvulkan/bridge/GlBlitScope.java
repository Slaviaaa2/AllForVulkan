package dev.allforvulkan.bridge;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.*;

/** State touched by a default-framebuffer color blit, without changing producer state.
 * Blits ignore viewport, color masks, blending, shaders and textures; only scissor
 * index 0 applies. Keep the broader GlStateScope for rendering and image creation. */
public final class GlBlitScope implements AutoCloseable {
    private final int read = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
    private final int draw = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
    private final boolean scissor = glIsEnabledi(GL_SCISSOR_TEST, 0);
    private final boolean srgb = glIsEnabled(GL_FRAMEBUFFER_SRGB);
    private final int defaultReadBuffer;

    public GlBlitScope() {
        glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
        defaultReadBuffer = glGetInteger(GL_READ_BUFFER);
        glReadBuffer(glGetBoolean(GL_DOUBLEBUFFER) ? GL_BACK : GL_FRONT);
        if (scissor) glDisablei(GL_SCISSOR_TEST, 0);
        if (srgb) glDisable(GL_FRAMEBUFFER_SRGB);
    }

    @Override public void close() {
        glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
        glReadBuffer(defaultReadBuffer);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, read);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, draw);
        if (scissor) glEnablei(GL_SCISSOR_TEST, 0);
        if (srgb) glEnable(GL_FRAMEBUFFER_SRGB);
    }
}
