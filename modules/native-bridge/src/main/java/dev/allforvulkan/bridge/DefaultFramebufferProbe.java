package dev.allforvulkan.bridge;

import org.lwjgl.system.MemoryStack;
import java.nio.ByteBuffer;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.*;

/** Isolated real-driver checks of the default drawable and live objects across resize. */
final class DefaultFramebufferProbe {
    static void run(ZinkContext context) {
        try (var stack = MemoryStack.stackPush(); var state = new GlStateScope()) {
            int defaultRead = glGetInteger(GL_READ_BUFFER), defaultDraw = glGetInteger(GL_DRAW_BUFFER);
            int texture = glGenTextures(), framebuffer = glGenFramebuffers();
            try {
                glBindTexture(GL_TEXTURE_2D, texture);
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 2, 2, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer)null);
                glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
                glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
                if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) throw new IllegalStateException("Resize sentinel FBO incomplete");
                glDisable(GL_SCISSOR_TEST);
                glClearColor(0, 1, 0, 1); glClear(GL_COLOR_BUFFER_BIT);
                for (int[] extent : new int[][] {{73, 41}, {31, 47}}) {
                    glViewport(7, 9, 3, 5); glScissor(4, 6, 2, 2);
                    context.resize(extent[0], extent[1]);
                    var values = stack.mallocInt(4);
                    glGetIntegerv(GL_VIEWPORT, values);
                    if (values.get(0) != 7 || values.get(1) != 9 || values.get(2) != 3 || values.get(3) != 5)
                        throw new IllegalStateException("Resize changed the existing viewport");
                    glGetIntegerv(GL_SCISSOR_BOX, values);
                    if (values.get(0) != 4 || values.get(1) != 6 || values.get(2) != 2 || values.get(3) != 2)
                        throw new IllegalStateException("Resize changed the existing scissor");
                    if (glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING) != framebuffer || glGetInteger(GL_READ_FRAMEBUFFER_BINDING) != framebuffer
                            || !glIsTexture(texture) || !glIsFramebuffer(framebuffer))
                        throw new IllegalStateException("Resize replaced live GL object state");
                    expectPixel(0, 0, 0, 255, 0, stack);
                    glBindFramebuffer(GL_FRAMEBUFFER, 0);
                    int output = glGetBoolean(GL_DOUBLEBUFFER) ? GL_BACK : GL_FRONT;
                    if (context.defaultReadBuffer() != output) throw new IllegalStateException("Cached default read buffer changed across resize");
                    glDrawBuffer(output); glReadBuffer(output);
                    int depth = glGetFramebufferAttachmentParameteri(GL_FRAMEBUFFER, GL_DEPTH, GL_FRAMEBUFFER_ATTACHMENT_DEPTH_SIZE);
                    int stencil = glGetFramebufferAttachmentParameteri(GL_FRAMEBUFFER, GL_STENCIL, GL_FRAMEBUFFER_ATTACHMENT_STENCIL_SIZE);
                    if (depth < 24 || stencil < 8) throw new IllegalStateException("Default drawable lacks window-compatible depth/stencil");
                    glDisable(GL_SCISSOR_TEST);
                    glClearColor(0, 0, 1, 1); glClear(GL_COLOR_BUFFER_BIT);
                    glEnable(GL_SCISSOR_TEST); glScissor(extent[0] - 8, extent[1] - 8, 8, 8);
                    glClearColor(1, 0, 1, 1); glClear(GL_COLOR_BUFFER_BIT);
                    expectPixel(extent[0] - 4, extent[1] - 4, 255, 0, 255, stack);
                    expectPixel(0, 0, 0, 0, 255, stack);
                    glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
                    System.out.println("AFV_DEFAULT_DRAWABLE_SIZE=" + extent[0] + "x" + extent[1] + " DEPTH=" + depth + " STENCIL=" + stencil);
                }
                int error = glGetError();
                if (error != GL_NO_ERROR) throw new IllegalStateException("Default drawable probe: GL error 0x" + Integer.toHexString(error));
                System.out.println("AFV_DEFAULT_DRAWABLE_PASS: final corner pixels, resize, live texture/FBO contents and state preserved");
            } finally {
                glBindFramebuffer(GL_FRAMEBUFFER, 0);
                glReadBuffer(defaultRead); glDrawBuffer(defaultDraw);
                glDeleteFramebuffers(framebuffer); glDeleteTextures(texture);
            }
        }
    }
    private static void expectPixel(int x, int y, int red, int green, int blue, MemoryStack stack) {
        var pixel = stack.malloc(4);
        glReadPixels(x, y, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
        if ((pixel.get(0) & 255) != red || (pixel.get(1) & 255) != green || (pixel.get(2) & 255) != blue || (pixel.get(3) & 255) != 255)
            throw new IllegalStateException("Default drawable pixel mismatch at " + x + "," + y);
    }
}
