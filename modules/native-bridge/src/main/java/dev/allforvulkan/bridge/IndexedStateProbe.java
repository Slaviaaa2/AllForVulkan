package dev.allforvulkan.bridge;

import org.lwjgl.system.MemoryStack;
import java.nio.ByteBuffer;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL14.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL40.*;
import static org.lwjgl.opengl.GL41.*;
import static org.lwjgl.opengl.GL45.glBlitNamedFramebuffer;
import static org.lwjgl.opengl.GL45.glNamedFramebufferReadBuffer;

/** Real MRT pixels and indexed state after bridge-like operations and drawable replacement. */
final class IndexedStateProbe {
    static void run(ZinkContext context) {
        int oldWidth = context.width(), oldHeight = context.height();
        try (var stack = MemoryStack.stackPush(); var original = new GlStateScope()) {
            int framebuffer = glGenFramebuffers(), vao = glGenVertexArrays();
            int[] textures = {glGenTextures(), glGenTextures()};
            int vertex = 0, fragment = 0, program = 0;
            int srcRgb = glGetIntegeri(GL_BLEND_SRC_RGB, 1), dstRgb = glGetIntegeri(GL_BLEND_DST_RGB, 1);
            int srcAlpha = glGetIntegeri(GL_BLEND_SRC_ALPHA, 1), dstAlpha = glGetIntegeri(GL_BLEND_DST_ALPHA, 1);
            try {
                glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
                for (int i = 0; i < 2; i++) {
                    glBindTexture(GL_TEXTURE_2D, textures[i]);
                    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 4, 4, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer)null);
                    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0 + i, GL_TEXTURE_2D, textures[i], 0);
                }
                glDrawBuffers(stack.ints(GL_COLOR_ATTACHMENT0, GL_COLOR_ATTACHMENT1));
                if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
                    throw new IllegalStateException("Indexed state MRT incomplete");
                glDisable(GL_SCISSOR_TEST); glDisable(GL_FRAMEBUFFER_SRGB); glDisable(GL_DITHER);
                glDisable(GL_DEPTH_TEST); glDisable(GL_CULL_FACE);
                glColorMask(true, true, true, true);
                glClearColor(0, 0, 1, 1); glClear(GL_COLOR_BUFFER_BIT);
                vertex = shader(GL_VERTEX_SHADER, """
                        #version 460 core
                        void main() {
                            vec2 p=vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                            gl_Position=vec4(p*2.0-1.0,0.0,1.0);
                        }
                        """);
                fragment = shader(GL_FRAGMENT_SHADER, """
                        #version 460 core
                        layout(location=0) out vec4 first;
                        layout(location=1) out vec4 second;
                        void main() { first=vec4(1,0,0,.5); second=vec4(0,1,0,.25); }
                        """);
                program = glCreateProgram();
                glAttachShader(program, vertex); glAttachShader(program, fragment); glLinkProgram(program);
                if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) throw new IllegalStateException(glGetProgramInfoLog(program));
                glUseProgram(program); glBindVertexArray(vao);
                int buffers = glGetInteger(GL_MAX_DRAW_BUFFERS), viewports = glGetInteger(GL_MAX_VIEWPORTS);
                for (int i = 0; i < buffers; i++) {
                    glColorMaski(i, i != 1, i == 1, i == 1, false);
                    if ((i & 1) != 0) glEnablei(GL_BLEND, i); else glDisablei(GL_BLEND, i);
                }
                glBlendFuncSeparatei(1, GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ZERO);
                for (int i = 0; i < viewports; i++) {
                    glViewportIndexedf(i, i == 0 ? 0 : i + .25f, i == 0 ? 0 : i + .5f, 4, 4);
                    glScissorIndexed(i, i, i + 1, i + 2, i + 3);
                    if ((i & 1) != 0) glEnablei(GL_SCISSOR_TEST, i); else glDisablei(GL_SCISSOR_TEST, i);
                }
                probeBlit(context, buffers, viewports, stack);
                // Legacy APIs deliberately overwrite every index, as the old bridge did.
                try (var saved = new GlStateScope()) {
                    overwrite();
                    try (var nested = new GlStateScope()) { glColorMask(false, true, false, true); }
                }
                expectState(buffers, viewports);
                try {
                    try (var saved = new GlStateScope()) { overwrite(); throw new ExpectedFailure(); }
                } catch (ExpectedFailure expected) { /* try-with-resources must restore on failure too. */ }
                expectState(buffers, viewports);
                context.resize(53, 37);
                expectState(buffers, viewports);
                context.resize(29, 43);
                expectState(buffers, viewports);
                glDrawArrays(GL_TRIANGLES, 0, 3);
                expectPixel(GL_COLOR_ATTACHMENT0, 255, 0, 255, stack);
                expectPixel(GL_COLOR_ATTACHMENT1, 0, 64, 191, stack);
                int error = glGetError();
                if (error != GL_NO_ERROR) throw new IllegalStateException("Indexed state probe GL error 0x" + Integer.toHexString(error));
                System.out.println("AFV_INDEXED_STATE_PASS: " + buffers + " color masks/blend enables, " + viewports
                        + " fractional viewports/scissors, named blit/bindings/exception restore, nested scopes, resize, exact MRT pixels");
            } finally {
                glBlendFuncSeparatei(1, srcRgb, dstRgb, srcAlpha, dstAlpha);
                context.resize(oldWidth, oldHeight);
                glDeleteFramebuffers(framebuffer);
                for (int texture : textures) glDeleteTextures(texture);
                if (program != 0) glDeleteProgram(program);
                if (vertex != 0) glDeleteShader(vertex);
                if (fragment != 0) glDeleteShader(fragment);
                glDeleteVertexArrays(vao);
            }
        }
    }
    private static void probeBlit(ZinkContext context, int buffers, int viewports, MemoryStack stack) {
        int framebuffer = glGenFramebuffers(), texture = glGenTextures();
        int defaultReadBuffer;
        try (var saved = new GlStateScope()) {
            glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
            defaultReadBuffer = glGetInteger(GL_READ_BUFFER);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
            glDisablei(GL_SCISSOR_TEST, 0); glDisable(GL_FRAMEBUFFER_SRGB);
            glColorMask(true, true, true, true);
            glClearColor(1, 0, 1, 1); glClear(GL_COLOR_BUFFER_BIT);
            glBindTexture(GL_TEXTURE_2D, texture);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 4, 4, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer)null);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer);
            glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
            if (glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
                throw new IllegalStateException("Blit state probe framebuffer incomplete");
            // The scope must choose a real default read buffer and restore GL_NONE.
            glReadBuffer(GL_NONE);
        }
        int read = glGetInteger(GL_READ_FRAMEBUFFER_BINDING), draw = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        int readBuffer = glGetInteger(GL_READ_BUFFER);
        boolean srgb = glIsEnabled(GL_FRAMEBUFFER_SRGB);
        try {
            glEnablei(GL_SCISSOR_TEST, 0); glEnable(GL_FRAMEBUFFER_SRGB);
            for (int attempt = 0; attempt < 4; attempt++) {
                int producerRead = attempt < 2 ? read : 0;
                int expectedDefault = (attempt & 1) == 0 ? GL_NONE : context.defaultReadBuffer();
                glBindFramebuffer(GL_READ_FRAMEBUFFER, producerRead);
                glNamedFramebufferReadBuffer(0, expectedDefault);
                try {
                    try (var saved = new GlBlitScope(context.defaultReadBuffer())) {
                        glBlitNamedFramebuffer(0, framebuffer, 0, 0, 4, 4, 0, 0, 4, 4, GL_COLOR_BUFFER_BIT, GL_NEAREST);
                        if (glGetInteger(GL_READ_FRAMEBUFFER_BINDING) != producerRead || glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING) != draw)
                            throw new IllegalStateException("Named blit changed producer framebuffer bindings while active");
                        if ((attempt & 1) != 0) throw new ExpectedFailure();
                    }
                } catch (ExpectedFailure expected) { /* The same restoration is required on failure. */ }
                if (glGetInteger(GL_READ_FRAMEBUFFER_BINDING) != producerRead || glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING) != draw
                        || glGetInteger(GL_READ_BUFFER) != (producerRead == 0 ? expectedDefault : readBuffer) || !glIsEnabledi(GL_SCISSOR_TEST, 0)
                        || !glIsEnabled(GL_FRAMEBUFFER_SRGB)) throw new IllegalStateException("Blit state restore failed");
                glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
                if (glGetInteger(GL_READ_BUFFER) != expectedDefault) throw new IllegalStateException("Default read buffer lost");
                glBindFramebuffer(GL_READ_FRAMEBUFFER, framebuffer);
                expectPixel(GL_COLOR_ATTACHMENT0, 255, 0, 255, stack);
                glBindFramebuffer(GL_READ_FRAMEBUFFER, producerRead);
                glDisablei(GL_SCISSOR_TEST, 0);
                expectState(buffers, viewports);
                glEnablei(GL_SCISSOR_TEST, 0);
            }
        } finally {
            glBindFramebuffer(GL_READ_FRAMEBUFFER, 0); glReadBuffer(defaultReadBuffer);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, read); glBindFramebuffer(GL_DRAW_FRAMEBUFFER, draw);
            glDisablei(GL_SCISSOR_TEST, 0);
            if (!srgb) glDisable(GL_FRAMEBUFFER_SRGB);
            glDeleteFramebuffers(framebuffer); glDeleteTextures(texture);
        }
    }
    private static void overwrite() {
        glColorMask(true, true, true, true); glDisable(GL_BLEND);
        glViewport(0, 0, 11, 13); glScissor(0, 0, 7, 9); glDisable(GL_SCISSOR_TEST);
    }
    private static void expectState(int buffers, int viewports) {
        try (var local = MemoryStack.stackPush()) {
            var mask = local.malloc(4); var viewport = local.mallocFloat(4); var scissor = local.mallocInt(4);
            for (int i = 0; i < buffers; i++) {
                glGetBooleani_v(GL_COLOR_WRITEMASK, i, mask);
                if ((mask.get(0) != 0) != (i != 1) || (mask.get(1) != 0) != (i == 1)
                        || (mask.get(2) != 0) != (i == 1) || mask.get(3) != 0
                        || glIsEnabledi(GL_BLEND, i) != ((i & 1) != 0))
                    throw new IllegalStateException("Indexed color/blend state lost at " + i);
            }
            for (int i = 0; i < viewports; i++) {
                glGetFloati_v(GL_VIEWPORT, i, viewport); glGetIntegeri_v(GL_SCISSOR_BOX, i, scissor);
                if (viewport.get(0) != (i == 0 ? 0 : i + .25f) || viewport.get(1) != (i == 0 ? 0 : i + .5f)
                        || viewport.get(2) != 4 || viewport.get(3) != 4 || scissor.get(0) != i || scissor.get(1) != i + 1
                        || scissor.get(2) != i + 2 || scissor.get(3) != i + 3 || glIsEnabledi(GL_SCISSOR_TEST, i) != ((i & 1) != 0))
                    throw new IllegalStateException("Indexed viewport/scissor state lost at " + i);
            }
        }
    }
    private static void expectPixel(int attachment, int red, int green, int blue, MemoryStack stack) {
        glReadBuffer(attachment);
        var pixel = stack.malloc(4);
        glReadPixels(1, 1, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
        if ((pixel.get(0) & 255) != red || (pixel.get(1) & 255) != green || (pixel.get(2) & 255) != blue || (pixel.get(3) & 255) != 255)
            throw new IllegalStateException("MRT result lost after state restore: attachment " + attachment + ", RGBA="
                    + (pixel.get(0) & 255) + "," + (pixel.get(1) & 255) + "," + (pixel.get(2) & 255) + "," + (pixel.get(3) & 255));
    }
    private static int shader(int kind, String source) {
        int shader = glCreateShader(kind);
        glShaderSource(shader, source); glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
            String error = glGetShaderInfoLog(shader); glDeleteShader(shader); throw new IllegalStateException(error);
        }
        return shader;
    }
    private static final class ExpectedFailure extends RuntimeException {}
}
