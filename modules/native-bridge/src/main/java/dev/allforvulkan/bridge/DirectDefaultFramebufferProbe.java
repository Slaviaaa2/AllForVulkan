package dev.allforvulkan.bridge;

import org.lwjgl.system.MemoryStack;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.glDrawBuffers;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL45.*;

/** Real-driver checks for virtual FBO 0, performed before game initialization. */
public final class DirectDefaultFramebufferProbe {
    private DirectDefaultFramebufferProbe() {}
    public static void run() {
        int texture = 0, framebuffer = 0, depth = 0;
        try (var state = new GlStateScope(); var stack = MemoryStack.stackPush()) {
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            int read = glGetInteger(GL_READ_BUFFER), draw = glGetInteger(GL_DRAW_BUFFER);
            int object = glGetFramebufferAttachmentParameteri(GL_FRAMEBUFFER, GL_FRONT_LEFT, GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
            texture = glCreateTextures(GL_TEXTURE_2D);
            glTextureStorage2D(texture, 1, GL_RGBA8, 4, 4);
            framebuffer = glCreateFramebuffers();
            glNamedFramebufferTexture(framebuffer, GL_COLOR_ATTACHMENT0, texture, 0);
            depth = glCreateRenderbuffers();
            glNamedRenderbufferStorage(depth, GL_DEPTH24_STENCIL8, 4, 4);
            glNamedFramebufferRenderbuffer(framebuffer, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, depth);
            glDisable(GL_SCISSOR_TEST);
            glDisable(GL_FRAMEBUFFER_SRGB);
            glColorMask(true, true, true, true);
            glClearColor(0, 0, 1, 1);
            glClear(GL_COLOR_BUFFER_BIT);
            require(glGetError() == GL_NO_ERROR, "unrouted setup");

            DirectDefaultFramebuffer.begin(framebuffer);
            require(glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING) == 0 && glGetInteger(GL_READ_FRAMEBUFFER_BINDING) == 0, "integer binding");
            var ints = stack.mallocInt(1);
            glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, ints);
            require(ints.get(0) == 0, "buffer binding query");
            int[] array = {-1};
            glGetIntegerv(GL_READ_FRAMEBUFFER_BINDING, array);
            require(array[0] == 0, "array binding query");
            require(!glGetBoolean(GL_DRAW_FRAMEBUFFER_BINDING) && glGetFloat(GL_READ_FRAMEBUFFER_BINDING) == 0, "typed binding query");
            require(glGetFramebufferAttachmentParameteri(GL_FRAMEBUFFER, GL_FRONT_LEFT, GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE) == object, "default attachment identity");
            glReadBuffer(read); glDrawBuffer(draw); glDrawBuffers(stack.ints(draw));
            require(glGetInteger(GL_READ_BUFFER) == read && glGetInteger(GL_DRAW_BUFFER0) == draw, "default selectors");
            int temporary = glCreateFramebuffers();
            glBindFramebuffer(GL_FRAMEBUFFER, temporary);
            glDeleteFramebuffers(temporary);
            glClearColor(1, 0, 1, 1);
            glClear(GL_COLOR_BUFFER_BIT);
            var pixel = stack.malloc(4);
            glReadPixels(0, 0, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
            require((pixel.get(0) & 255) == 255 && pixel.get(1) == 0 && (pixel.get(2) & 255) == 255, "routed clear/read");
            glClearNamedFramebufferfv(0, GL_COLOR, 0, stack.floats(0, 1, 0, 1));
            glReadPixels(0, 0, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
            require(pixel.get(0) == 0 && (pixel.get(1) & 255) == 255 && pixel.get(2) == 0, "named clear to zero");
            require(glGetError() == GL_NO_ERROR, "routed operations");
            // Mutation of logical FBO 0 must still be rejected by the driver.
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
            require(glGetError() == GL_INVALID_OPERATION, "default mutation validation");
            DirectDefaultFramebuffer.end();
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glReadPixels(0, 0, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
            require(pixel.get(0) == 0 && pixel.get(1) == 0 && (pixel.get(2) & 255) == 255, "EGL target not overwritten");
            require(glGetError() == GL_NO_ERROR, "router detach");
            System.out.println("AFV_DIRECT_DEFAULT_PROBE_PASS: real bindings/selectors/typed+array queries, metadata, clear/read, named clear, mutation validation, detach");
        } finally {
            DirectDefaultFramebuffer.end();
            if (framebuffer != 0) glDeleteFramebuffers(framebuffer);
            if (depth != 0) glDeleteRenderbuffers(depth);
            if (texture != 0) glDeleteTextures(texture);
        }
    }
    private static void require(boolean condition, String operation) {
        if (!condition) throw new IllegalStateException("Direct default framebuffer probe: " + operation);
    }
}
