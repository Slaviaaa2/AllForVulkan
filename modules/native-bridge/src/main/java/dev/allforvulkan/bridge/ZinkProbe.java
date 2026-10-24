package dev.allforvulkan.bridge;

import java.nio.ByteBuffer;
import java.util.HexFormat;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL42.*;
import static org.lwjgl.opengl.GL43.*;
import static org.lwjgl.opengl.EXTMemoryObject.*;

/** Standalone M1 evidence; no game mixins or fake GL capabilities are loaded. */
public final class ZinkProbe {
    public static void main(String[] args) {
        try (ZinkContext context = new ZinkContext(16, 16)) {
            context.assertCurrentThread();
            var caps = GL.getCapabilities();
            System.out.println("ZINK_GL_VERSION=" + glGetString(GL_VERSION));
            System.out.println("ZINK_GL_RENDERER=" + glGetString(GL_RENDERER));
            System.out.println("ZINK_GLSL_VERSION=" + glGetString(GL_SHADING_LANGUAGE_VERSION));
            System.out.println("ZINK_EXTERNAL_MEMORY_FD=" + caps.GL_EXT_memory_object_fd);
            System.out.println("ZINK_EXTERNAL_SEMAPHORE_FD=" + caps.GL_EXT_semaphore_fd);
            if (caps.GL_EXT_memory_object) {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    int devices = glGetInteger(GL_NUM_DEVICE_UUIDS_EXT);
                    for (int i = 0; i < devices; ++i) {
                        ByteBuffer uuid = stack.malloc(GL_UUID_SIZE_EXT);
                        glGetUnsignedBytei_vEXT(GL_DEVICE_UUID_EXT, i, uuid);
                        byte[] bytes = new byte[GL_UUID_SIZE_EXT];
                        uuid.get(bytes);
                        System.out.println("ZINK_DEVICE_UUID=" + HexFormat.of().formatHex(bytes));
                    }
                }
            }
            IndexedStateProbe.run(context);
            DefaultFramebufferProbe.run(context);
            computeRoundTrip();
            ExternalImageProbe.run();
            if (java.util.Arrays.asList(args).contains("--wsi")) {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    var bytes = stack.malloc(GL_UUID_SIZE_EXT);
                    glGetUnsignedBytei_vEXT(GL_DEVICE_UUID_EXT, 0, bytes);
                    byte[] uuid = new byte[GL_UUID_SIZE_EXT]; bytes.get(uuid);
                    WsiSupportProbe.run(uuid);
                }
            }
            System.out.println("AFV_ZINK_PROBE_PASS: real offscreen desktop GL, image-store/compute, exact pixels");
        }
    }

    private static void computeRoundTrip() {
        int texture = glGenTextures(), framebuffer = glGenFramebuffers(), shader = 0, program = 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            glBindTexture(GL_TEXTURE_2D, texture);
            glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, 16, 16);
            glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
            if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) throw new IllegalStateException("Incomplete GL FBO");
            shader = glCreateShader(GL_COMPUTE_SHADER);
            glShaderSource(shader, """
                    #version 460 core
                    layout(local_size_x=4,local_size_y=4) in;
                    layout(rgba8,binding=0) writeonly uniform image2D target;
                    void main() {
                        ivec2 p=ivec2(gl_GlobalInvocationID.xy);
                        imageStore(target,p,vec4(float(p.x)/255.,float(p.y)/255.,128./255.,1.));
                    }
                    """);
            glCompileShader(shader);
            if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) throw new IllegalStateException(glGetShaderInfoLog(shader));
            program = glCreateProgram();
            glAttachShader(program, shader);
            glLinkProgram(program);
            if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) throw new IllegalStateException(glGetProgramInfoLog(program));
            glUseProgram(program);
            glBindImageTexture(0, texture, 0, false, 0, GL_WRITE_ONLY, GL_RGBA8);
            glDispatchCompute(4, 4, 1);
            glMemoryBarrier(GL_FRAMEBUFFER_BARRIER_BIT);
            // Diagnostic readback of a private 16x16 test image, never a product frame path.
            ByteBuffer pixels = stack.malloc(16 * 16 * 4);
            glReadPixels(0, 0, 16, 16, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
            for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
                int index = (y * 16 + x) * 4;
                if ((pixels.get(index) & 255) != x || (pixels.get(index + 1) & 255) != y
                        || (pixels.get(index + 2) & 255) != 128 || (pixels.get(index + 3) & 255) != 255)
                    throw new IllegalStateException("Zink pixel mismatch at " + x + "," + y);
            }
            int error = glGetError();
            if (error != GL_NO_ERROR) throw new IllegalStateException("GL error " + error);
        } finally {
            glUseProgram(0);
            if (program != 0) glDeleteProgram(program);
            if (shader != 0) glDeleteShader(shader);
            glDeleteFramebuffers(framebuffer);
            glDeleteTextures(texture);
        }
    }
}
