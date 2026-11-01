package dev.allforvulkan.framegraph;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Complementary Reimagined's opaque shadow color: radial distortion, then the natural-shadow formula.
 * Water, glass, and waving are not this path. A mismatch keeps the GL image. */
public final class ComplementaryShadow {
    static final String VERTEX = """
            #version 450
            layout(location = 0) in vec3 Position;
            layout(location = 1) in vec2 UV0;
            layout(location = 2) in vec4 Color;
            layout(std140, set = 0, binding = 0) uniform Transform {
                mat4 ModelView;
                mat4 Projection;
                mat4 TextureMat;
                float edgeNudge;
            };
            layout(location = 0) out vec2 texcoord;
            layout(location = 1) out vec4 tint;
            void main() {
                texcoord = (TextureMat * vec4(UV0, 0.0, 1.0)).xy;
                tint = Color;
                gl_Position = Projection * ModelView * vec4(Position, 1.0);
                float bias = 1.0 - 25.6 * Projection[0][0];
                float radius = length(gl_Position.xy);
                float distort = radius * bias + (1.0 - bias);
                gl_Position.xy /= distort;
                gl_Position.z *= 0.2;
                gl_Position.z = 0.5 * gl_Position.z + 0.5 * gl_Position.w;
                gl_Position.y -= gl_Position.w * edgeNudge;
            }
            """;

    static final String FRAGMENT = """
            #version 450
            layout(location = 0) in vec2 texcoord;
            layout(location = 1) in vec4 tint;
            layout(location = 0) out vec4 color;
            layout(set = 0, binding = 1) uniform sampler2D tex;
            void main() {
                vec4 sampled = textureLod(tex, texcoord, 0.0);
                sampled.rgb *= tint.rgb;
                float weight = pow(sampled.a, (1.0 - sampled.a) * 0.5) * 1.05;
                sampled.rgb = mix(vec3(1.0), sampled.rgb, weight);
                sampled.rgb *= 1.0 - pow(sampled.a, 64.0);
                sampled.rgb *= 0.1;
                color = sampled;
            }
            """;

    private static IrisSubset.Shader shader;

    private ComplementaryShadow() {}

    /** The program text is Complementary's opaque shadow path, not a copy of the pack. */
    public static boolean isProgram(String source) {
        if (source == null) return false;
        return source.contains("DoNaturalShadowCalculation")
                && source.contains("shadowMapBias")
                && source.contains("gl_Position.z = gl_Position.z * 0.2");
    }

    /** True when the active Iris pack zip contains that shadow program. */
    public static boolean installed(Path gameDirectory) {
        try {
            Path pack = activePack(gameDirectory);
            if (pack == null || !pack.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")) return false;
            try (ZipFile zip = new ZipFile(pack.toFile())) {
                String program = text(zip, "shaders/program/shadow.glsl");
                String wrapper = text(zip, "shaders/world0/shadow.vsh");
                return isProgram(program) && wrapper.contains("#include \"/program/shadow.glsl\"");
            }
        } catch (IOException | RuntimeException failure) {
            return false;
        }
    }

    public static IrisSubset.Shader shader() {
        if (shader != null) return shader;
        var vertex = GlslCompiler.compile("complementary-shadow.vsh", VERTEX, true);
        var fragment = GlslCompiler.compile("complementary-shadow.fsh", FRAGMENT, false);
        if (!vertex.ok() || !fragment.ok()) return null;
        shader = new IrisSubset.Shader("shadow", IrisPackPlan.Stage.SHADOW, vertex.spirv(), fragment.spirv());
        return shader;
    }

    /** One texel of Complementary's natural shadow color. Alpha 1 becomes black. */
    public static float[] naturalShadow(float red, float green, float blue, float alpha) {
        float weight = (float) (Math.pow(alpha, (1.0 - alpha) * 0.5) * 1.05);
        float[] color = new float[] {
                mix(1f, red, weight),
                mix(1f, green, weight),
                mix(1f, blue, weight),
                alpha
        };
        float keep = 1f - (float) Math.pow(alpha, 64.0);
        color[0] *= keep * 0.1f;
        color[1] *= keep * 0.1f;
        color[2] *= keep * 0.1f;
        return color;
    }

    /** The same 1/32-pixel edge nudge the plain shadow replay uses. */
    public static float edgeNudge(int size) {
        return size > 0 ? (1f / 32f) * 2f / size : 0f;
    }

    private static float mix(float start, float end, float weight) {
        return start * (1f - weight) + end * weight;
    }

    private static Path activePack(Path gameDirectory) throws IOException {
        Path propertiesFile = gameDirectory.resolve("config").resolve("iris.properties");
        if (!Files.isRegularFile(propertiesFile)) return null;
        String pack = "";
        for (String line : Files.readAllLines(propertiesFile)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("shaderPack=")) pack = trimmed.substring("shaderPack=".length()).trim();
        }
        if (pack.isEmpty() || "false".equalsIgnoreCase(pack)) return null;
        Path root = gameDirectory.resolve("shaderpacks").normalize();
        Path file = root.resolve(pack).normalize();
        if (!file.startsWith(root) || !Files.exists(file)) return null;
        return file;
    }

    private static String text(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) return "";
        try (InputStream input = zip.getInputStream(entry)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
