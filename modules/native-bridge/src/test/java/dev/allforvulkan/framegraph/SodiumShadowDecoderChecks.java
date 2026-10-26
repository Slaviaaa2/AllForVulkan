package dev.allforvulkan.framegraph;

import java.nio.file.Path;
import java.util.Map;

/** The pinned Sodium compact layout round-trips, and Iris const directives stay out of the subset body. */
public final class SodiumShadowDecoderChecks {
    public static void main(String[] args) throws Exception {
        byte[] quad = new byte[SodiumShadowDecoder.COMPACT_BYTES * 4];
        for (int corner = 0; corner < 4; corner++) {
            byte[] vertex = SodiumShadowDecoder.packVertex(0f, 0f, 0f, 255, 0, 0, 255, 0.5f, 0.25f, true, false, 40);
            System.arraycopy(vertex, 0, quad, corner * SodiumShadowDecoder.COMPACT_BYTES, vertex.length);
        }
        var mesh = SodiumShadowDecoder.decode(quad, 20, 4, 10f, 0f, -4f, 0.01f, 0.02f);
        require(mesh != null && mesh.quads() == 1 && mesh.vertices().length == 54, "quad did not expand to two triangles");
        require(near(mesh.vertices()[0], 26f) && near(mesh.vertices()[1], 0f) && near(mesh.vertices()[2], 28f),
                "camera-relative position was " + mesh.vertices()[0] + "," + mesh.vertices()[1] + "," + mesh.vertices()[2]);
        require(near(mesh.vertices()[3], 0.51f) && near(mesh.vertices()[4], 0.25f - 0.02f), "UV shrink was not applied");
        require(mesh.vertices()[5] == 1f && mesh.vertices()[6] == 0f && mesh.vertices()[7] == 0f && mesh.vertices()[8] == 1f,
                "vertex color was swapped");
        byte[] wider = new byte[24 * 4];
        for (int corner = 0; corner < 4; corner++)
            System.arraycopy(SodiumShadowDecoder.packVertex(0f, 16f, 0f, 0, 255, 0, 255, 0f, 0f, false, false, 0), 0, wider, corner * 24, 20);
        var extra = SodiumShadowDecoder.decode(wider, 24, 4, 0f, 0f, 0f, 0f, 0f);
        require(extra != null && near(extra.vertices()[1], 16f), "a trailing Iris attribute hid the position");
        require(SodiumShadowDecoder.decode(quad, 20, 3, 0, 0, 0, 0, 0) == null, "a partial quad was accepted");

        String vertex = """
                #version 120
                const int shadowMapResolution = 32;
                const float shadowDistance = 16.0;
                varying vec2 texcoord;
                varying vec4 tint;
                void main() {
                    gl_Position = ftransform();
                    texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
                    tint = gl_Color;
                }
                """;
        String fragment = """
                #version 120
                const bool shadowcolor0Clear = true;
                const vec4 shadowcolor0ClearColor = vec4(0.0, 0.0, 0.0, 1.0);
                uniform sampler2D gtexture;
                varying vec2 texcoord;
                varying vec4 tint;
                void main() { gl_FragData[0] = texture2D(gtexture, texcoord) * tint; }
                """;
        var plan = IrisPackPlan.fromSources(Map.of("shadow.vsh", vertex, "shadow.fsh", fragment), false);
        require(IrisSubset.reason(plan.programs().get(0)) == null, IrisSubset.reason(plan.programs().get(0)));
        var compiled = IrisSubset.compile(plan);
        require(compiled.compiledSummary().equals("shadow"), compiled.compiledSummary() + " / " + compiled.skippedSummary());
        Path meshPack = Path.of("..", "..", "tests", "shaderpacks", "AFV-Shadow-Mesh");
        var fromDisk = IrisSubset.compile(IrisPackPlan.read(meshPack));
        require(fromDisk.compiledSummary().contains("shadow"), fromDisk.skippedSummary());
        System.out.println("AFV_SHADOW_MESH_DECODE_PASS: compact quad, trailing attributes, const directives");
    }

    private static boolean near(float actual, float expected) {
        return Math.abs(actual - expected) < 0.002f;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
