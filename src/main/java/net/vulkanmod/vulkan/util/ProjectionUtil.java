package net.vulkanmod.vulkan.util;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/** Convert OpenGL clip z in [-w,w] to Vulkan [0,w] at the renderer boundary.
 * Leave Minecraft/MOD matrices in OpenGL space; never transform shared JOML classes. */
public final class ProjectionUtil {
    private ProjectionUtil() {}
    public static Matrix4f toVulkan(Matrix4fc source) {
        return new Matrix4f(source)
                .m02((source.m02() + source.m03()) * 0.5f)
                .m12((source.m12() + source.m13()) * 0.5f)
                .m22((source.m22() + source.m23()) * 0.5f)
                .m32((source.m32() + source.m33()) * 0.5f);
    }
}
