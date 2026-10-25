package dev.allforvulkan.framegraph;

import java.nio.file.Path;

/** A two-color mesh is not the constant clear. A missing device skips unless the require flag is set. */
public final class ShadowMeshPassChecks {
    public static void main(String[] args) throws Exception {
        var compiled = IrisSubset.compile(IrisPackPlan.read(Path.of("..", "..", "tests", "shaderpacks", "AFV-Smoke")));
        IrisSubset.Shader shadow = compiled.shaders().stream().filter(shader -> shader.name().equals("shadow")).findFirst().orElseThrow();
        float[] identity = new float[16];
        for (int column = 0; column < 4; column++) identity[column * 4 + column] = 1f;
        float[] vertices = {
                -1f, -1f, 0f, 0.5f, 0.5f, 1f, 0f, 0f, 1f,
                0f, -1f, 0f, 0.5f, 0.5f, 1f, 0f, 0f, 1f,
                0f, 1f, 0f, 0.5f, 0.5f, 1f, 0f, 0f, 1f,
                -1f, -1f, 0f, 0.5f, 0.5f, 1f, 0f, 0f, 1f,
                0f, 1f, 0f, 0.5f, 0.5f, 1f, 0f, 0f, 1f,
                -1f, 1f, 0f, 0.5f, 0.5f, 1f, 0f, 0f, 1f,
                0f, -1f, 0f, 0.5f, 0.5f, 0f, 1f, 0f, 1f,
                1f, -1f, 0f, 0.5f, 0.5f, 0f, 1f, 0f, 1f,
                1f, 1f, 0f, 0.5f, 0.5f, 0f, 1f, 0f, 1f,
                0f, -1f, 0f, 0.5f, 0.5f, 0f, 1f, 0f, 1f,
                1f, 1f, 0f, 0.5f, 0.5f, 0f, 1f, 0f, 1f,
                0f, 1f, 0f, 0.5f, 0.5f, 0f, 1f, 0f, 1f
        };
        var draw = new ShadowMeshPass.Draw(vertices, new byte[] {(byte) 255, (byte) 255, (byte) 255, (byte) 255}, 1, 1,
                identity, identity, 32, 0, 0, 0, 255);
        var result = ShadowMeshPass.render(shadow, draw);
        if (result.skipped()) {
            if (Boolean.getBoolean("afv.requireCompositeDevice")) throw new IllegalStateException(result.detail());
            System.out.println("AFV_SHADOW_MESH_DRAW_SKIP: " + result.detail());
            return;
        }
        if (result.rgba() == null) throw new IllegalStateException(result.detail());
        int left = pixel(result.rgba(), 32, 4, 16);
        int right = pixel(result.rgba(), 32, 28, 16);
        require(channel(left, 0) > 200 && channel(left, 1) < 40 && channel(left, 2) < 40, "left pixel " + hex(left));
        require(channel(right, 1) > 200 && channel(right, 0) < 40 && channel(right, 2) < 40, "right pixel " + hex(right));
        var rejected = ShadowMeshPass.render(shadow, new ShadowMeshPass.Draw(new float[0], draw.texture(), 1, 1,
                identity, identity, 32, 0, 0, 0, 255));
        if (rejected.skipped() || rejected.rgba() != null) throw new IllegalStateException(rejected.detail());
        System.out.println("AFV_SHADOW_MESH_DRAW_PASS: " + result.detail() + " left=" + hex(left) + " right=" + hex(right));
    }

    private static int pixel(byte[] rgba, int size, int x, int y) {
        int index = (y * size + x) * 4;
        return (rgba[index] & 255) | ((rgba[index + 1] & 255) << 8) | ((rgba[index + 2] & 255) << 16) | ((rgba[index + 3] & 255) << 24);
    }

    private static int channel(int pixel, int index) { return (pixel >> (8 * index)) & 255; }

    private static String hex(int pixel) {
        return channel(pixel, 0) + "," + channel(pixel, 1) + "," + channel(pixel, 2) + "," + channel(pixel, 3);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
