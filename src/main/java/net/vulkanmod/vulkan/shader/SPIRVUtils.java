package net.vulkanmod.vulkan.shader;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.lwjgl.system.NativeResource;
import org.lwjgl.util.shaderc.ShadercIncludeResolveI;
import org.lwjgl.util.shaderc.ShadercIncludeResult;
import org.lwjgl.util.shaderc.ShadercIncludeResultReleaseI;

import java.io.IOException;
import java.nio.ByteBuffer;

import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.util.shaderc.Shaderc.*;

public class SPIRVUtils {
    private static final boolean DEBUG = false;
    private static final boolean OPTIMIZATIONS = true;

    private static long compiler;
    private static long options;

    //The dedicated Includer and Releaser Inner Classes used to Initialise #include Support for ShaderC
    private static final ShaderIncluder SHADER_INCLUDER = new ShaderIncluder();
    private static final ShaderReleaser SHADER_RELEASER = new ShaderReleaser();
    private static final long pUserData = 0;

    private static ObjectArrayList<String> includePaths;

    static {
        initCompiler();
    }

    private static void initCompiler() {
        compiler = shaderc_compiler_initialize();

        if (compiler == NULL) {
            throw new RuntimeException("Failed to create shader compiler");
        }

        options = shaderc_compile_options_initialize();

        if (options == NULL) {
            throw new RuntimeException("Failed to create compiler options");
        }

        if (OPTIMIZATIONS)
            shaderc_compile_options_set_optimization_level(options, shaderc_optimization_level_performance);

        if (DEBUG)
            shaderc_compile_options_set_generate_debug_info(options);

        shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_2);
        shaderc_compile_options_set_include_callbacks(options, SHADER_INCLUDER, SHADER_RELEASER, pUserData);

        includePaths = new ObjectArrayList<>();
        addIncludePath("/assets/vulkanmod/shaders/include/");
    }

    public static void addIncludePath(String path) {
        includePaths.add(path.endsWith("/") ? path : path + "/");
    }

    public static SPIRV compileShader(String filename, String source, ShaderKind shaderKind) {
        if (source == null) {
            throw new NullPointerException("source for %s.%s is null".formatted(filename, shaderKind));
        }

        long result = shaderc_compile_into_spv(compiler, source, shaderKind.kind, filename, "main", options);

        if (result == NULL) {
            throw new RuntimeException("Failed to compile shader " + filename + " into SPIR-V");
        }

        if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success) {
            String error = shaderc_result_get_error_message(result);
            shaderc_result_release(result);
            throw new RuntimeException("Failed to compile shader " + filename + " into SPIR-V:\n" + error);
        }

        return new SPIRV(result, shaderc_result_get_bytes(result));
    }

    public enum ShaderKind {
        VERTEX_SHADER(shaderc_glsl_vertex_shader),
        GEOMETRY_SHADER(shaderc_glsl_geometry_shader),
        FRAGMENT_SHADER(shaderc_glsl_fragment_shader),
        COMPUTE_SHADER(shaderc_glsl_compute_shader);

        private final int kind;

        ShaderKind(int kind) {
            this.kind = kind;
        }
    }

    private static class ShaderIncluder implements ShadercIncludeResolveI {
        @Override
        public long invoke(long userData, long requestedSource, int type, long requestingSource, long depth) {
            String requested = memUTF8(requestedSource);
            for (String includePath : includePaths) {
                try (var stream = SPIRVUtils.class.getResourceAsStream(includePath + requested)) {
                    if (stream != null) {
                        byte[] bytes = stream.readAllBytes();
                        ByteBuffer content = memAlloc(bytes.length);
                        content.put(bytes).flip();
                        return ShadercIncludeResult.calloc()
                                .source_name(memUTF8(requested, false))
                                .content(content).user_data(userData).address();
                    }
                } catch (IOException exception) {
                    return failure("Could not read include " + requested + ": " + exception.getMessage());
                }
            }
            return failure("Unable to find " + requested + " in AFV shader include paths");
        }

        private long failure(String message) {
            // Report errors to shaderc; never unwind a Java exception through C++.
            return ShadercIncludeResult.calloc().source_name(memUTF8("", false))
                    .content(memUTF8(message, false)).address();
        }
    }

    private static class ShaderReleaser implements ShadercIncludeResultReleaseI {
        @Override
        public void invoke(long userData, long includeResult) {
            // The compiler owns the callback result until this release callback.
            ShadercIncludeResult result = ShadercIncludeResult.create(includeResult);
            memFree(result.source_name());
            memFree(result.content());
            result.free();
        }
    }

    public static final class SPIRV implements NativeResource {

        private final long handle;
        private ByteBuffer bytecode;

        public SPIRV(long handle, ByteBuffer bytecode) {
            this.handle = handle;
            this.bytecode = bytecode;
        }

        public ByteBuffer bytecode() {
            return bytecode;
        }

        @Override
        public void free() {
            if (bytecode != null) {
                shaderc_result_release(handle);
                bytecode = null;
            }
        }
    }

}