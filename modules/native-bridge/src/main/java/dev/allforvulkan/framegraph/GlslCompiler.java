package dev.allforvulkan.framegraph;

import java.nio.ByteBuffer;

import static org.lwjgl.util.shaderc.Shaderc.shaderc_compilation_status_success;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compile_into_spv;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compile_options_initialize;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compile_options_set_optimization_level;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compile_options_set_target_env;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compiler_initialize;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_env_version_vulkan_1_2;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_glsl_fragment_shader;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_glsl_vertex_shader;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_optimization_level_performance;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_result_get_bytes;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_result_get_compilation_status;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_result_get_error_message;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_result_release;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_target_env_vulkan;

/** Compiles one GLSL stage to SPIR-V. A failure is a message, so one program can fall back. */
final class GlslCompiler {
    record Output(byte[] spirv, String error) {
        boolean ok() { return error.isEmpty() && spirv != null && spirv.length >= 4; }
    }

    @FunctionalInterface
    interface Backend {
        Output compile(String filename, String source, boolean vertex);
    }

    private static final Object LOCK = new Object();
    private static long compiler;
    private static long options;
    private static String unavailable = "";

    private GlslCompiler() {}

    static Output compile(String filename, String source, boolean vertex) {
        synchronized (LOCK) {
            String ready = ensure();
            if (!ready.isEmpty()) return new Output(null, ready);
            long result = 0;
            try {
                int kind = vertex ? shaderc_glsl_vertex_shader : shaderc_glsl_fragment_shader;
                result = shaderc_compile_into_spv(compiler, source, kind, filename, "main", options);
                if (result == 0) return new Output(null, "shader compiler returned no result");
                if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success) {
                    return new Output(null, firstLine(shaderc_result_get_error_message(result)));
                }
                ByteBuffer bytes = shaderc_result_get_bytes(result);
                byte[] copy = new byte[bytes.remaining()];
                bytes.get(copy);
                if (!spirv(copy)) return new Output(null, "shader compiler returned invalid SPIR-V");
                return new Output(copy, "");
            } catch (Throwable failure) {
                return new Output(null, firstLine(failure.getMessage() == null ? failure.toString() : failure.getMessage()));
            } finally {
                if (result != 0) shaderc_result_release(result);
            }
        }
    }

    private static String ensure() {
        if (compiler != 0) return "";
        if (!unavailable.isEmpty()) return unavailable;
        try {
            compiler = shaderc_compiler_initialize();
            options = compiler == 0 ? 0 : shaderc_compile_options_initialize();
            if (compiler == 0 || options == 0) {
                unavailable = "shader compiler unavailable";
                return unavailable;
            }
            shaderc_compile_options_set_optimization_level(options, shaderc_optimization_level_performance);
            shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_2);
            return "";
        } catch (Throwable failure) {
            unavailable = "shader compiler unavailable: " + firstLine(
                    failure.getMessage() == null ? failure.toString() : failure.getMessage());
            return unavailable;
        }
    }

    private static boolean spirv(byte[] bytes) {
        if (bytes.length < 20 || bytes.length % 4 != 0) return false;
        int magic = (bytes[0] & 0xff) | ((bytes[1] & 0xff) << 8) | ((bytes[2] & 0xff) << 16) | ((bytes[3] & 0xff) << 24);
        return magic == 0x07230203;
    }

    private static String firstLine(String message) {
        String line = message == null ? "" : message.trim();
        int breakAt = line.indexOf('\n');
        if (breakAt >= 0) line = line.substring(0, breakAt).trim();
        return line.length() > 180 ? line.substring(0, 180) : line;
    }
}
