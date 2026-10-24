package dev.allforvulkan.bridge;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/** Samples a Vulkan-rendered scene using real GLSL. Identity and invert are useful
 * integration checks; this is an extension point, not an Iris shader-pack backend. */
public final class SceneColorPass implements GlFramePass {
    private int program, vao;
    private final int effect;
    public SceneColorPass(int effect) {
        this.effect = effect;
        int vertex = 0, fragment = 0;
        try {
            vertex = shader(GL_VERTEX_SHADER, """
                    #version 330 core
                    void main() {
                        vec2 p=vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                        gl_Position=vec4(p*2.0-1.0,0.0,1.0);
                    }
                    """);
            fragment = shader(GL_FRAGMENT_SHADER, """
                    #version 330 core
                    uniform sampler2D scene;
                    uniform int effect;
                    uniform sampler2D sceneDepth;
                    out vec4 color;
                    void main() {
                        color=texelFetch(scene,ivec2(gl_FragCoord.xy),0);
                        if (effect == 1) color.rgb=vec3(1.0)-color.rgb;
                        if (effect == 2) {
                            float depth=texelFetch(sceneDepth,ivec2(gl_FragCoord.xy),0).r;
                            color=vec4(vec3(fract(depth*256.0)),1.0);
                        }
                    }
                    """);
            program = glCreateProgram();
            glAttachShader(program, vertex); glAttachShader(program, fragment); glLinkProgram(program);
            if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) throw new IllegalStateException(glGetProgramInfoLog(program));
            vao = glGenVertexArrays();
        } catch (Throwable failure) { close(); throw failure; }
        finally { if (vertex != 0) glDeleteShader(vertex); if (fragment != 0) glDeleteShader(fragment); }
    }
    private static int shader(int type, String source) {
        int shader = glCreateShader(type);
        glShaderSource(shader, source); glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
            String error = glGetShaderInfoLog(shader); glDeleteShader(shader); throw new IllegalStateException(error);
        }
        return shader;
    }
    @Override public void render(Frame frame) {
        glBindFramebuffer(GL_FRAMEBUFFER, frame.outputFramebuffer());
        glViewport(0, 0, frame.width(), frame.height());
        glDisable(GL_DITHER); glDisable(GL_SCISSOR_TEST); glDisable(GL_BLEND); glDisable(GL_DEPTH_TEST); glDisable(GL_CULL_FACE);
        glColorMask(true, true, true, true);
        if (frame.srgb()) glEnable(GL_FRAMEBUFFER_SRGB); else glDisable(GL_FRAMEBUFFER_SRGB);
        int unit = glGetInteger(GL_ACTIVE_TEXTURE) - GL_TEXTURE0;
        int depthUnit = unit == 0 ? 1 : 0;
        glActiveTexture(GL_TEXTURE0 + depthUnit);
        int oldDepth = glGetInteger(GL_TEXTURE_BINDING_2D), oldSampler = glGetInteger(org.lwjgl.opengl.GL33.GL_SAMPLER_BINDING);
        glBindTexture(GL_TEXTURE_2D, frame.depthTexture());
        org.lwjgl.opengl.GL33.glBindSampler(depthUnit, 0);
        glActiveTexture(GL_TEXTURE0 + unit);
        try {
        glBindTexture(GL_TEXTURE_2D, frame.colorTexture());
        org.lwjgl.opengl.GL33.glBindSampler(glGetInteger(GL_ACTIVE_TEXTURE) - GL_TEXTURE0, 0);
        glUseProgram(program);
        glUniform1i(glGetUniformLocation(program, "scene"), glGetInteger(GL_ACTIVE_TEXTURE) - GL_TEXTURE0);
        glUniform1i(glGetUniformLocation(program, "effect"), effect == 2 && frame.stage() != Stage.WORLD_END ? 0 : effect);
        glUniform1i(glGetUniformLocation(program, "sceneDepth"), depthUnit);
        glBindVertexArray(vao); glDrawArrays(GL_TRIANGLES, 0, 3);
        GlTextureRegistry.check("GL scene post-processing");
        } finally {
            glActiveTexture(GL_TEXTURE0 + depthUnit);
            glBindTexture(GL_TEXTURE_2D, oldDepth);
            org.lwjgl.opengl.GL33.glBindSampler(depthUnit, oldSampler);
            glActiveTexture(GL_TEXTURE0 + unit);
        }
    }
    @Override public void close() {
        if (vao != 0) { glDeleteVertexArrays(vao); vao = 0; }
        if (program != 0) { glDeleteProgram(program); program = 0; }
    }
}
