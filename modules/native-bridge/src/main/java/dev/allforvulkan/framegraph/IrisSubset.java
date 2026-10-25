package dev.allforvulkan.framegraph;

import java.util.ArrayList;
import java.util.List;

/** Turns the fixed Iris shadow/gbuffer texture program into Vulkan GLSL and SPIR-V.
 * Any other supported program stays on the skipped list for Zink. */
public final class IrisSubset {
    static final String VULKAN_VERTEX = """
            #version 450
            layout(location = 0) in vec3 Position;
            layout(location = 1) in vec2 UV0;
            layout(location = 2) in vec4 Color;
            layout(set = 0, binding = 0) uniform Transform {
                mat4 ModelView;
                mat4 Projection;
                mat4 TextureMat;
            };
            layout(location = 0) out vec2 texcoord;
            layout(location = 1) out vec4 tint;
            void main() {
                gl_Position = Projection * ModelView * vec4(Position, 1.0);
                texcoord = (TextureMat * vec4(UV0, 0.0, 1.0)).xy;
                tint = Color;
            }
            """;

    static final String VULKAN_FRAGMENT = """
            #version 450
            layout(location = 0) in vec2 texcoord;
            layout(location = 1) in vec4 tint;
            layout(location = 0) out vec4 color;
            layout(set = 0, binding = 1) uniform sampler2D gtexture;
            void main() {
                color = texture(gtexture, texcoord) * tint;
            }
            """;

    public record Shader(String name, IrisPackPlan.Stage stage, byte[] vertex, byte[] fragment) {}

    public record Result(List<Shader> shaders, List<String> skipped) {
        public String compiledSummary() {
            return String.join(",", shaders.stream().map(Shader::name).toList());
        }

        public String skippedSummary() { return String.join("; ", skipped); }
    }

    private static final String VERTEX_POSITION = "gl_Position = ftransform ( )";
    private static final String VERTEX_UV = "texcoord = ( gl_TextureMatrix [ 0 ] * gl_MultiTexCoord0 ) . xy";
    private static final String VERTEX_TINT = "tint = gl_Color";
    private static final String FRAGMENT_DATA = "gl_FragData [ 0 ] = texture2D ( gtexture , texcoord ) * tint";
    private static final String FRAGMENT_COLOR = "gl_FragColor = texture2D ( gtexture , texcoord ) * tint";

    private IrisSubset() {}

    public static Result compile(IrisPackPlan plan) {
        return compile(plan, GlslCompiler::compile);
    }

    static Result compile(IrisPackPlan plan, GlslCompiler.Backend backend) {
        var shaders = new ArrayList<Shader>();
        var skipped = new ArrayList<String>();
        if (plan != null) {
            for (var program : plan.supported()) {
                String reason = reason(program);
                if (reason != null) {
                    skipped.add(program.name + " (" + reason + ")");
                    continue;
                }
                var vertex = backend.compile(program.name + ".vsh", VULKAN_VERTEX, true);
                if (!vertex.ok()) {
                    skipped.add(program.name + " (shader compiler: " + vertex.error() + ")");
                    continue;
                }
                var fragment = backend.compile(program.name + ".fsh", VULKAN_FRAGMENT, false);
                if (!fragment.ok()) {
                    skipped.add(program.name + " (shader compiler: " + fragment.error() + ")");
                    continue;
                }
                shaders.add(new Shader(program.name, program.stage, vertex.spirv(), fragment.spirv()));
            }
        }
        return new Result(List.copyOf(shaders), List.copyOf(skipped));
    }

    /** Null when the program is the fixed texture*tint shadow or gbuffer program. */
    static String reason(IrisPackPlan.Program program) {
        if (program.stage != IrisPackPlan.Stage.SHADOW && program.stage != IrisPackPlan.Stage.GBUFFER)
            return "outside the shadow/gbuffer texture subset";
        if (program.name.startsWith("shadowcomp")) return "outside the shadow/gbuffer texture subset";
        String vertex = stage(program.vertex, true);
        if (vertex != null) return vertex;
        return stage(program.fragment, false);
    }

    private static String stage(String source, boolean vertex) {
        String stripped = source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", " ");
        if (stripped.indexOf('"') >= 0 || stripped.indexOf('\'') >= 0) return "not the fixed texture*tint program";
var matcher = java.util.regex.Pattern.compile("(?m)^[ \\t]*#[ \\t]*version[ \\t]+(\\d+).*").matcher(stripped);
        int count = 0;
        while (matcher.find()) {
            count++;
            if (!matcher.group(1).equals("120")) return "glsl version";
        }
        if (count > 1) return "glsl version";
        stripped = stripped.replaceAll("(?m)^[ \\t]*#[ \\t]*version[ \\t]+\\d+.*", " ");
        if (stripped.indexOf('#') >= 0) return "preprocessor";
        var tokens = tokens(stripped);
        int index = 0;
        var declarations = new ArrayList<String>();
        while (index < tokens.size() && !tokens.get(index).equals("void")) {
            // Iris reads shadowMapResolution and the clear color from const declarations. They are not shader logic.
            if (tokens.get(index).equals("const")) {
                index = skipStatement(tokens, index);
                if (index < 0) return "not the fixed texture*tint program";
                continue;
            }
            if (index + 3 >= tokens.size() || !tokens.get(index + 3).equals(";")) return "not the fixed texture*tint program";
            declarations.add(tokens.get(index) + " " + tokens.get(index + 1) + " " + tokens.get(index + 2));
            index += 4;
        }
        if (index + 4 >= tokens.size() || !tokens.get(index).equals("void") || !tokens.get(index + 1).equals("main")
                || !tokens.get(index + 2).equals("(") || !tokens.get(index + 3).equals(")") || !tokens.get(index + 4).equals("{"))
            return "not the fixed texture*tint program";
        index += 5;
        var statements = new ArrayList<String>();
        var current = new StringBuilder();
        boolean any = false;
        for (; index < tokens.size(); index++) {
            String token = tokens.get(index);
            if (token.equals("}")) {
                index++;
                break;
            }
            if (token.equals(";")) {
                if (!any) return "not the fixed texture*tint program";
                if (statements.contains(current.toString())) return "not the fixed texture*tint program";
                statements.add(current.toString());
                current.setLength(0);
                any = false;
                continue;
            }
            if (any) current.append(' ');
            current.append(token);
            any = true;
        }
        if (any || index != tokens.size()) return "not the fixed texture*tint program";
        if (vertex) {
            if (!sameMembers(declarations, java.util.List.of("varying vec2 texcoord", "varying vec4 tint")))
                return "not the fixed texture*tint program";
            if (!sameMembers(statements, java.util.List.of(VERTEX_POSITION, VERTEX_UV, VERTEX_TINT)))
                return "not the fixed texture*tint program";
            return null;
        }
        if (!sameMembers(declarations, java.util.List.of("uniform sampler2D gtexture", "varying vec2 texcoord", "varying vec4 tint")))
            return "not the fixed texture*tint program";
        if (statements.size() != 1 || !(statements.contains(FRAGMENT_DATA) || statements.contains(FRAGMENT_COLOR)))
            return "not the fixed texture*tint program";
        return null;
    }

    private static int skipStatement(List<String> tokens, int index) {
        int depth = 0;
        for (; index < tokens.size(); index++) {
            String token = tokens.get(index);
            if (token.equals("(")) depth++;
            else if (token.equals(")")) {
                if (depth == 0) return -1;
                depth--;
            } else if (token.equals(";") && depth == 0) return index + 1;
        }
        return -1;
    }

    private static boolean sameMembers(List<String> actual, List<String> expected) {
        return actual.size() == expected.size() && actual.containsAll(expected);
    }

    private static List<String> tokens(String source) {
        var tokens = new ArrayList<String>();
        var current = new StringBuilder();
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (Character.isWhitespace(c)) {
                flush(tokens, current);
                continue;
            }
            if ("(){}[];,*=.".indexOf(c) >= 0 || c == '[' || c == ']') {
                flush(tokens, current);
                tokens.add(String.valueOf(c));
                continue;
            }
            current.append(c);
        }
        flush(tokens, current);
        return tokens;
    }

    private static void flush(List<String> tokens, StringBuilder current) {
        if (!current.isEmpty()) {
            tokens.add(current.toString());
            current.setLength(0);
        }
    }
}
