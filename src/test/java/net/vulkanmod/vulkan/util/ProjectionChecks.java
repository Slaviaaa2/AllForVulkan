package net.vulkanmod.vulkan.util;

import org.joml.Matrix4f;
import org.joml.Vector4f;

public final class ProjectionChecks {
    public static void main(String[] args) {
        check(new Matrix4f().setOrtho(0, 960, 540, 0, 1000, 21000), 1000, 21000);
        check(new Matrix4f().setPerspective((float) Math.toRadians(70), 16f / 9, 0.05f, 512), 0.05f, 512);
        Matrix4f gui = ProjectionUtil.toVulkan(new Matrix4f().setOrtho(0, 960, 540, 0, 1000, 21000));
        if (Math.abs(depth(gui, -11000) - 0.5f) > 0.00001f) throw new AssertionError("GUI depth is clipped instead of centered");
        System.out.println("AFV_PROJECTION_PASS: ortho/perspective near=0 far=1, GUI depth=0.5, input unchanged");
    }
    private static void check(Matrix4f original, float near, float far) {
        Matrix4f copy = new Matrix4f(original);
        Matrix4f converted = ProjectionUtil.toVulkan(original);
        if (!original.equals(copy)) throw new AssertionError("Modified caller projection");
        if (Math.abs(depth(converted, -near)) > 0.0001f || Math.abs(depth(converted, -far) - 1) > 0.0001f)
            throw new AssertionError("Incorrect Vulkan clip depth");
        Vector4f gl = original.transform(new Vector4f(0.2f, 0.3f, -near * 2, 1));
        Vector4f vk = converted.transform(new Vector4f(0.2f, 0.3f, -near * 2, 1));
        if (gl.x != vk.x || gl.y != vk.y || gl.w != vk.w) throw new AssertionError("Changed non-depth clip coordinates");
    }
    private static float depth(Matrix4f matrix, float z) {
        Vector4f clip = matrix.transform(new Vector4f(0, 0, z, 1));
        return clip.z / clip.w;
    }
}
