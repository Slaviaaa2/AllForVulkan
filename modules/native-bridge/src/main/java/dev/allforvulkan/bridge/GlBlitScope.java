package dev.allforvulkan.bridge;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL45.glNamedFramebufferReadBuffer;

/** State touched by a default-framebuffer color blit, without changing producer state.
 * Blits ignore viewport, color masks, blending, shaders and textures; only scissor
 * index 0 applies. Keep the broader GlStateScope for rendering and image creation. */
public final class GlBlitScope implements AutoCloseable {
    private final boolean scissor = glIsEnabledi(GL_SCISSOR_TEST, 0);
    private final boolean srgb = glIsEnabled(GL_FRAMEBUFFER_SRGB);
    private final int defaultReadBuffer;
    private final boolean readBufferChanged;

    public GlBlitScope(int sourceReadBuffer) {
        // GL_READ_BUFFER has no named query. Bind only for that query when
        // necessary, then restore immediately; the actual blit is fully named.
        int read = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        if (read != 0) glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
        try { defaultReadBuffer = glGetInteger(GL_READ_BUFFER); }
        finally { if (read != 0) glBindFramebuffer(GL_READ_FRAMEBUFFER, read); }
        readBufferChanged = defaultReadBuffer != sourceReadBuffer;
        if (readBufferChanged) glNamedFramebufferReadBuffer(0, sourceReadBuffer);
        if (scissor) glDisablei(GL_SCISSOR_TEST, 0);
        if (srgb) glDisable(GL_FRAMEBUFFER_SRGB);
    }

    @Override public void close() {
        if (readBufferChanged) glNamedFramebufferReadBuffer(0, defaultReadBuffer);
        if (scissor) glEnablei(GL_SCISSOR_TEST, 0);
        if (srgb) glEnable(GL_FRAMEBUFFER_SRGB);
    }
}
