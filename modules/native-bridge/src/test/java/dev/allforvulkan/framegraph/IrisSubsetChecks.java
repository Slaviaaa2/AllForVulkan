package dev.allforvulkan.framegraph;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Compiles the fixed shadow/gbuffer subset to SPIR-V and leaves every other program uncompiled. */
public final class IrisSubsetChecks {
    public static void main(String[] args) throws Exception {
        Path smoke = Path.of("..", "..", "tests", "shaderpacks", "AFV-Smoke");
        var plan = IrisPackPlan.read(smoke);
        var compiled = IrisSubset.compile(plan);
        require(compiled.compiledSummary().equals("shadow,gbuffers_textured_lit,gbuffers_terrain"), compiled.compiledSummary());
        require(compiled.skippedSummary().equals("final (outside the shadow/gbuffer texture subset)"), compiled.skippedSummary());
        for (var shader : compiled.shaders()) {
            require(spirv(shader.vertex()), shader.name() + " vertex");
            require(spirv(shader.fragment()), shader.name() + " fragment");
            validate(shader.name() + ".vert.spv", shader.vertex());
            validate(shader.name() + ".frag.spv", shader.fragment());
        }

        String vertex = """
                #version 120
                // rearranged
                varying vec4 tint; /* block */ varying vec2 texcoord;
                void main() {
                    tint = gl_Color;
                    texcoord = ( gl_TextureMatrix[0] * gl_MultiTexCoord0 ).xy;
                    gl_Position = ftransform();
                }
                """;
        String fragment = """
                #version 120
                varying vec2 texcoord;
                uniform sampler2D gtexture;
                varying vec4 tint;
                void main() { gl_FragColor = texture2D(gtexture, texcoord) * tint; }
                """;
        var reordered = IrisPackPlan.fromSources(Map.of("shadow.vsh", vertex, "shadow.fsh", fragment), false);
        require(IrisSubset.reason(reordered.programs().get(0)) == null, "reordered smoke program was rejected");
        require(IrisSubset.compile(reordered).compiledSummary().equals("shadow"), "reordered program did not compile");

        var version = IrisPackPlan.fromSources(Map.of(
                "gbuffers_terrain.vsh", vertex.replace("#version 120", "#version 330"),
                "gbuffers_terrain.fsh", fragment), false);
        require("glsl version".equals(IrisSubset.reason(version.programs().get(0))), IrisSubset.reason(version.programs().get(0)));

        var extra = IrisPackPlan.fromSources(Map.of(
                "gbuffers_terrain.vsh", vertex.replace("tint = gl_Color;", "tint = gl_Color; fog = gl_Color;"),
                "gbuffers_terrain.fsh", fragment), false);
        require("not the fixed texture*tint program".equals(IrisSubset.reason(extra.programs().get(0))),
                IrisSubset.reason(extra.programs().get(0)));

        var define = IrisPackPlan.fromSources(Map.of(
                "shadow.vsh", vertex + "\n#define EXTRA 1\n",
                "shadow.fsh", fragment), false);
        require("preprocessor".equals(IrisSubset.reason(define.programs().get(0))), IrisSubset.reason(define.programs().get(0)));

        var partial = IrisSubset.compile(plan, (filename, source, isVertex) -> {
            if (filename.startsWith("gbuffers_terrain")) return new GlslCompiler.Output(null, "forced failure");
            return GlslCompiler.compile(filename, source, isVertex);
        });
        require(partial.compiledSummary().equals("shadow,gbuffers_textured_lit"), partial.compiledSummary());
        require(partial.skippedSummary().contains("gbuffers_terrain (shader compiler: forced failure)"), partial.skippedSummary());
        require(partial.skippedSummary().contains("final (outside the shadow/gbuffer texture subset)"), partial.skippedSummary());

        var invalid = GlslCompiler.compile("broken.fsh", "#version 450\nvoid main(){ missing }", false);
        require(!invalid.ok() && invalid.error().contains("error"), invalid.error());

        var decision = ShaderBackendSelection.choose("direct", true, plan);
        require(decision.fellBack() && decision.active().equals("zink"), decision.toString());
        require(decision.coverage().equals("compiled:shadow,gbuffers_textured_lit,gbuffers_terrain"), decision.coverage());
        require(decision.fallback().contains("world draws stay on Zink"), decision.fallback());
        require(!ShaderBackendSelection.choose("zink", true, plan).fellBack(), "Zink request compiled a subset");
        System.out.println("AFV_IRIS_SUBSET_PASS: smoke SPIR-V, rejected extras, per-program compiler fallback");
    }

    private static boolean spirv(byte[] bytes) {
        if (bytes == null || bytes.length < 20 || bytes.length % 4 != 0) return false;
        int magic = (bytes[0] & 0xff) | ((bytes[1] & 0xff) << 8) | ((bytes[2] & 0xff) << 16) | ((bytes[3] & 0xff) << 24);
        return magic == 0x07230203;
    }

    private static void validate(String name, byte[] bytes) throws Exception {
        if (!Files.isExecutable(Path.of("/usr/bin/spirv-val"))) return;
        var path = Path.of("build", name);
        Files.createDirectories(path.getParent());
        Files.write(path, bytes);
        var process = new ProcessBuilder("/usr/bin/spirv-val", path.toString()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        require(process.waitFor() == 0, name + " failed spirv-val: " + output);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
