package dev.allforvulkan.bridge;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.opengl.EXTDirectStateAccess;
import org.lwjgl.opengl.GL;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.glDrawBuffers;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL45.*;

/** Real-driver checks for virtual FBO 0, performed before game initialization. */
public final class DirectDefaultFramebufferProbe {
    private DirectDefaultFramebufferProbe() {}
    public static void run() {
        int texture = 0, framebuffer = 0, depth = 0, extFramebuffer = 0;
        try (var state = new GlStateScope(); var stack = MemoryStack.stackPush()) {
            require(DirectDefaultFramebuffer.address(stack.ASCII("glGetFramebufferParameterivEXT"), 1L) == 1L, "EXT named query must not use core target entrypoint");
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
            checkGl("unrouted setup");

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
            glReadBuffer(read); glDrawBuffer(draw);
            require(glGetInteger(GL_READ_BUFFER) == read && glGetInteger(GL_DRAW_BUFFER0) == draw, "default selectors");
            // DrawBuffers accepts individual window buffers, not FRONT/BACK aliases.
            int listDraw = draw == GL_FRONT ? GL_FRONT_LEFT : draw == GL_BACK ? GL_BACK_LEFT : draw;
            glDrawBuffers(stack.ints(listDraw));
            require(glGetInteger(GL_DRAW_BUFFER0) == listDraw, "default draw-buffer list");
            glDrawBuffer(draw);
            checkGl("selector operations");
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
            checkGl("routed operations");
            if (GL.getCapabilities().GL_EXT_direct_state_access) {
                extFramebuffer = glCreateFramebuffers();
                glNamedFramebufferTexture(extFramebuffer, GL_COLOR_ATTACHMENT0, texture, 0);
                // The EXT query takes an object name, unlike the core target query.
                require(EXTDirectStateAccess.glGetFramebufferParameteriEXT(0, GL_READ_BUFFER) == read, "EXT named default read query");
                require(EXTDirectStateAccess.glGetFramebufferParameteriEXT(0, GL_DRAW_BUFFER) == draw, "EXT named default draw query");
                EXTDirectStateAccess.glFramebufferReadBufferEXT(0, GL_NONE);
                EXTDirectStateAccess.glFramebufferDrawBufferEXT(0, GL_NONE);
                require(glGetInteger(GL_READ_BUFFER) == GL_NONE && glGetInteger(GL_DRAW_BUFFER) == GL_NONE, "EXT disable default selectors");
                glClearNamedFramebufferfv(0, GL_COLOR, 0, stack.floats(1, 0, 0, 1));
                // Named operations on FBO 0 must work while an explicit FBO is bound.
                glBindFramebuffer(GL_FRAMEBUFFER, extFramebuffer);
                EXTDirectStateAccess.glFramebufferReadBufferEXT(0, read);
                EXTDirectStateAccess.glFramebufferDrawBuffersEXT(0, stack.ints(listDraw));
                require(EXTDirectStateAccess.glGetFramebufferParameteriEXT(0, GL_DRAW_BUFFER) == listDraw, "EXT named draw-buffer list");
                EXTDirectStateAccess.glFramebufferDrawBufferEXT(0, draw);
                require(glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING) == extFramebuffer && glGetInteger(GL_READ_FRAMEBUFFER_BINDING) == extFramebuffer, "EXT named operations preserve bindings");
                glReadPixels(0, 0, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
                require(pixel.get(0) == 0 && (pixel.get(1) & 255) == 255 && pixel.get(2) == 0, "EXT none suppresses alias clear");
                require(EXTDirectStateAccess.glGetFramebufferParameteriEXT(extFramebuffer, GL_DRAW_BUFFER) == GL_COLOR_ATTACHMENT0, "EXT explicit named query");
                EXTDirectStateAccess.glFramebufferDrawBufferEXT(extFramebuffer, GL_NONE);
                EXTDirectStateAccess.glFramebufferReadBufferEXT(extFramebuffer, GL_NONE);
                require(EXTDirectStateAccess.glGetFramebufferParameteriEXT(0, GL_DRAW_BUFFER) == draw && EXTDirectStateAccess.glGetFramebufferParameteriEXT(0, GL_READ_BUFFER) == read, "EXT explicit setters preserve default selectors");
                EXTDirectStateAccess.glFramebufferDrawBufferEXT(extFramebuffer, GL_COLOR_ATTACHMENT0);
                EXTDirectStateAccess.glFramebufferReadBufferEXT(extFramebuffer, GL_COLOR_ATTACHMENT0);
                glBindFramebuffer(GL_FRAMEBUFFER, 0);
                glClearNamedFramebufferfv(0, GL_COLOR, 0, stack.floats(1, 0, 0, 1));
                glReadPixels(0, 0, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
                require((pixel.get(0) & 255) == 255 && pixel.get(1) == 0 && pixel.get(2) == 0, "EXT reenabled alias draw/read");
                checkGl("EXT DSA routed operations");
                System.out.println("AFV_DIRECT_EXT_DSA_PASS: distinct named queries, selectors, none, binding preservation, explicit framebuffer isolation");
            } else {
                System.out.println("AFV_DIRECT_EXT_DSA_SKIP: GL_EXT_direct_state_access not advertised; distinct query resolution checked");
            }

            // Mutation of logical FBO 0 must still be rejected by the driver.
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
            require(glGetError() == GL_INVALID_OPERATION, "default mutation validation");
            DirectDefaultFramebuffer.end();
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glReadPixels(0, 0, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
            require(pixel.get(0) == 0 && pixel.get(1) == 0 && (pixel.get(2) & 255) == 255, "EGL target not overwritten");
            checkGl("router detach");
            System.out.println("AFV_DIRECT_DEFAULT_PROBE_PASS: real bindings/selectors/typed+array queries, metadata, clear/read, named clear, distinct extension resolution, mutation validation, detach");
        } finally {
            DirectDefaultFramebuffer.end();
            if (extFramebuffer != 0) glDeleteFramebuffers(extFramebuffer);
            if (framebuffer != 0) glDeleteFramebuffers(framebuffer);
            if (depth != 0) glDeleteRenderbuffers(depth);
            if (texture != 0) glDeleteTextures(texture);
        }
    }
    private static void checkGl(String operation) {
        int error = glGetError();
        if (error != GL_NO_ERROR) throw new IllegalStateException("Direct default framebuffer probe: " + operation + " GL error 0x" + Integer.toHexString(error));
    }
    private static void require(boolean condition, String operation) {
        if (!condition) throw new IllegalStateException("Direct default framebuffer probe: " + operation);
    }
}
