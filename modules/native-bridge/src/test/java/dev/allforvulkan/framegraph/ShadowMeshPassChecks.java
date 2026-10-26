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
        float[] ortho = new float[16];
        ortho[0] = 0.03125f;
        ortho[5] = 0.03125f;
        ortho[10] = -0.007810975f;
        ortho[14] = -0.21851201f;
        ortho[15] = 1f;
        float[] vulkan = ShadowMeshPass.vulkanProjection(ortho);
        require(Math.abs(vulkan[5] - 0.03125f) < 1e-6f, "Y scale was rewritten");
        float[] nudged = ShadowMeshPass.vulkanProjection(ortho, 32);
        require(nudged[13] < vulkan[13], "edge nudge did not move clip Y toward llvmpipe's lower pixel");
        require(Math.abs(vulkan[10] - 0.5f * ortho[10]) < 1e-6f, "projection z scale was not halved");
        require(Math.abs(vulkan[14] - (0.5f * ortho[14] + 0.5f)) < 1e-5f, "projection z bias was not applied");
        var draw = new ShadowMeshPass.Draw(vertices, new byte[][] {{(byte) 255, (byte) 255, (byte) 255, (byte) 255}}, 1, 1,
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
        float[] behind = {
                -1f, -1f, -0.5f, 0.5f, 0.5f, 0f, 0f, 1f, 1f,
                1f, -1f, -0.5f, 0.5f, 0.5f, 0f, 0f, 1f, 1f,
                1f, 1f, -0.5f, 0.5f, 0.5f, 0f, 0f, 1f, 1f,
                -1f, -1f, -0.5f, 0.5f, 0.5f, 0f, 0f, 1f, 1f,
                1f, 1f, -0.5f, 0.5f, 0.5f, 0f, 0f, 1f, 1f,
                -1f, 1f, -0.5f, 0.5f, 0.5f, 0f, 0f, 1f, 1f
        };
        var negative = ShadowMeshPass.render(shadow, new ShadowMeshPass.Draw(behind, draw.mips(), 1, 1,
                identity, identity, 32, 0, 0, 0, 255));
        if (negative.skipped() || negative.rgba() == null) throw new IllegalStateException(negative.detail());
        float edge0 = 12.5f / 16f - 1f;
        float edge1 = 15.5f / 16f - 1f;
        float[] edge = {
                -0.5f, edge0, 0f, 0.5f, 0.5f, 1f, 0f, 0f, 1f,
                0.5f, edge0, 0f, 0.5f, 0.5f, 1f, 0f, 0f, 1f,
                0.5f, edge1, 0f, 0.5f, 0.5f, 1f, 0f, 0f, 1f,
                -0.5f, edge0, 0f, 0.5f, 0.5f, 1f, 0f, 0f, 1f,
                0.5f, edge1, 0f, 0.5f, 0.5f, 1f, 0f, 0f, 1f,
                -0.5f, edge1, 0f, 0.5f, 0.5f, 1f, 0f, 0f, 1f
        };
        var tied = ShadowMeshPass.render(shadow, new ShadowMeshPass.Draw(edge, draw.mips(), 1, 1,
                identity, identity, 32, 0, 0, 0, 255));
        if (tied.skipped() || tied.rgba() == null) throw new IllegalStateException(tied.detail());
        require(channel(pixel(tied.rgba(), 32, 16, 12), 0) > 200, "lower edge pixel was dropped");
        require(channel(pixel(tied.rgba(), 32, 16, 15), 0) < 40, "upper edge pixel was filled");
        int center = pixel(negative.rgba(), 32, 16, 16);
        require(channel(center, 2) > 200 && channel(center, 0) < 40 && channel(center, 1) < 40,
                "negative clip z was discarded " + hex(center));
        var rejected = ShadowMeshPass.render(shadow, new ShadowMeshPass.Draw(new float[0], draw.mips(), 1, 1,
                identity, identity, 32, 0, 0, 0, 255));
        if (rejected.skipped() || rejected.rgba() != null) throw new IllegalStateException(rejected.detail());
        System.out.println("AFV_SHADOW_MESH_DRAW_PASS: " + result.detail() + " left=" + hex(left) + " right=" + hex(right)
                + " negativeZ=" + hex(center));
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
