package dev.allforvulkan.bridge;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/** Real GLSL texture sampling into the currently bound shared framebuffer. */
public final class TextureSampler implements AutoCloseable {
    private int program, vao;
    public TextureSampler() {
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
                    uniform sampler2D source;
                    out vec4 color;
                    void main() { color=texelFetch(source,ivec2(gl_FragCoord.xy)/16,0); }
                    """);
            program = glCreateProgram();
            glAttachShader(program, vertex); glAttachShader(program, fragment);
            glLinkProgram(program);
            if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) throw new IllegalStateException(glGetProgramInfoLog(program));
            vao = glGenVertexArrays();
        } catch (Throwable failure) { close(); throw failure; }
        finally { if (vertex != 0) glDeleteShader(vertex); if (fragment != 0) glDeleteShader(fragment); }
    }
    private static int shader(int kind, String source) {
        int shader = glCreateShader(kind);
        glShaderSource(shader, source); glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
            String error = glGetShaderInfoLog(shader); glDeleteShader(shader); throw new IllegalStateException(error);
        }
        return shader;
    }
    public void draw(int texture) {
        glDisable(GL_BLEND); glDisable(GL_DEPTH_TEST); glDisable(GL_CULL_FACE); glDisable(GL_FRAMEBUFFER_SRGB);
        glBindTexture(GL_TEXTURE_2D, texture);
        org.lwjgl.opengl.GL33.glBindSampler(glGetInteger(GL_ACTIVE_TEXTURE) - GL_TEXTURE0, 0);
        glUseProgram(program);
        glUniform1i(glGetUniformLocation(program, "source"), glGetInteger(GL_ACTIVE_TEXTURE) - GL_TEXTURE0);
        glBindVertexArray(vao);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        GlTextureRegistry.check("sample Minecraft texture into shared image");
    }
    @Override public void close() {
        if (vao != 0) { glDeleteVertexArrays(vao); vao = 0; }
        if (program != 0) { glDeleteProgram(program); program = 0; }
    }
}
