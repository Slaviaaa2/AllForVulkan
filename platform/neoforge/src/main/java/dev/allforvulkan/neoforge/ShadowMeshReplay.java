package dev.allforvulkan.neoforge;

import dev.allforvulkan.framegraph.IrisPackPlan;
import dev.allforvulkan.framegraph.IrisSubset;
import dev.allforvulkan.framegraph.ShadowMeshPass;
import dev.allforvulkan.framegraph.SodiumShadowDecoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

/** Replays the shadow meshes Sodium submitted, through the subset program, when that program is the pack's shadow shader. */
public final class ShadowMeshReplay {
    private static final Pattern CLEAR = Pattern.compile(
            "shadowcolor0ClearColor\\s*=\\s*vec4\\s*\\(\\s*([0-9.]+)\\s*,\\s*([0-9.]+)\\s*,\\s*([0-9.]+)\\s*,\\s*([0-9.]+)\\s*\\)");
    private static IrisSubset.Shader shader;
    private static boolean resolved;
    private static int clearRed = 255;
    private static int clearGreen = 255;
    private static int clearBlue = 255;
    private static int clearAlpha = 255;
    private static byte[] atlas;
    private static int atlasWidth;
    private static int atlasHeight;
    private static byte[] cached;
    private static int cachedGeneration = -1;
    private static int cachedSize;
    private static int cachedVertices = -1;
    private static String cachedDetail = "";
    private static int clipLogs;
    private static int loggedVertices = -1;

    public record Image(boolean subset, byte[] rgba, String detail) {}

    private ShadowMeshReplay() {}

    public static Image render(int size) {
        if (!resolve()) return new Image(false, null, "");
        ShadowMeshCapture.Snapshot snapshot = ShadowMeshCapture.snapshot();
        if (!ShadowMeshCapture.hasMatrices()) return new Image(true, null, "no shadow draw");
        if (snapshot.vertexCount() == 0) return new Image(true, null, "no shadow vertices");
        if (cached != null && cachedGeneration == snapshot.generation() && cachedSize == size
                && cachedVertices == snapshot.vertexCount())
            return new Image(true, cached, cachedDetail);
        if (!readAtlas()) return new Image(true, null, "block atlas could not be read");
        int bits = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac") ? 4 : 8;
        double precision = 1 << bits;
        float shrinkU = (float) (1.0 / 32768.0 - 1.0 / (atlasWidth * precision));
        float shrinkV = (float) (1.0 / 32768.0 - 1.0 / (atlasHeight * precision));
        float[] vertices = decode(snapshot, shrinkU, shrinkV);
        if (vertices == null) return new Image(true, null, "shadow mesh could not be decoded");
        logClip(snapshot, vertices);
        var draw = new ShadowMeshPass.Draw(vertices, atlas, atlasWidth, atlasHeight, snapshot.modelView(), snapshot.projection(),
                size, clearRed, clearGreen, clearBlue, clearAlpha);
        var rendered = ShadowMeshPass.render(shader, draw);
        if (rendered.skipped() || rendered.rgba() == null)
            return new Image(true, null, rendered.detail() == null ? "shadow mesh draw failed" : rendered.detail());
        cached = rendered.rgba();
        cachedGeneration = snapshot.generation();
        cachedSize = size;
        cachedVertices = snapshot.vertexCount();
        cachedDetail = rendered.detail() + ", sections=" + snapshot.draws().size() + ", stride=" + snapshot.stride();
        return new Image(true, cached, cachedDetail);
    }

    private static float[] decode(ShadowMeshCapture.Snapshot snapshot, float shrinkU, float shrinkV) {
        int floats = 0;
        var decoded = new java.util.ArrayList<SodiumShadowDecoder.Mesh>();
        for (var draw : snapshot.draws()) {
            var mesh = SodiumShadowDecoder.decode(draw.packed(), draw.stride(), draw.vertices(),
                    draw.regionX(), draw.regionY(), draw.regionZ(), shrinkU, shrinkV);
            if (mesh == null) continue;
            floats += mesh.vertices().length;
            if (floats > 2_000_000) return null;
            decoded.add(mesh);
        }
        if (floats < 9) return null;
        float[] vertices = new float[floats];
        int offset = 0;
        for (var mesh : decoded) {
            System.arraycopy(mesh.vertices(), 0, vertices, offset, mesh.vertices().length);
            offset += mesh.vertices().length;
        }
        return vertices;
    }

    /** One line per mesh size, so a fully clipped replay is visible without a device. */
    private static void logClip(ShadowMeshCapture.Snapshot snapshot, float[] vertices) {
        if (!Boolean.getBoolean("afv.shadowMesh") || clipLogs >= 4 || vertices.length == loggedVertices) return;
        loggedVertices = vertices.length;
        clipLogs++;
        float[] projection = ShadowMeshPass.vulkanProjection(snapshot.projection());
        int inside = 0;
        int count = vertices.length / 9;
        for (int index = 0; index < vertices.length; index += 9) {
            float[] eye = multiply(snapshot.modelView(), vertices[index], vertices[index + 1], vertices[index + 2], 1f);
            float[] clip = multiply(projection, eye[0], eye[1], eye[2], eye[3]);
            if (clip[3] > 0f && Math.abs(clip[0]) <= clip[3] && Math.abs(clip[1]) <= clip[3] && clip[2] >= 0f && clip[2] <= clip[3])
                inside++;
        }
        var draw = snapshot.draws().isEmpty() ? null : snapshot.draws().get(0);
        System.out.println("AFV_SHADOW_MESH_CLIP: vertices=" + count
                + " inside=" + inside
                + " proj=" + snapshot.projection()[0] + "," + snapshot.projection()[5] + "," + snapshot.projection()[10] + "," + snapshot.projection()[14]
                + " model=" + snapshot.modelView()[12] + "," + snapshot.modelView()[13] + "," + snapshot.modelView()[14]
                + " first=" + vertices[0] + "," + vertices[1] + "," + vertices[2]
                + " region=" + (draw == null ? "none" : draw.regionX() + "," + draw.regionY() + "," + draw.regionZ()));
    }

    private static float[] multiply(float[] matrix, float x, float y, float z, float w) {
        return new float[] {
                matrix[0] * x + matrix[4] * y + matrix[8] * z + matrix[12] * w,
                matrix[1] * x + matrix[5] * y + matrix[9] * z + matrix[13] * w,
                matrix[2] * x + matrix[6] * y + matrix[10] * z + matrix[14] * w,
                matrix[3] * x + matrix[7] * y + matrix[11] * z + matrix[15] * w
        };
    }

    private static boolean resolve() {
        if (resolved) return shader != null;
        resolved = true;
        try {
            var plan = IrisPackPlan.fromGameDirectory(Minecraft.getInstance().gameDirectory.toPath());
            var compiled = IrisSubset.compile(plan);
            shader = compiled.shaders().stream().filter(item -> item.name().equals("shadow")).findFirst().orElse(null);
            for (var program : plan.programs()) {
                if (!program.name.equals("shadow") || program.fragment == null) continue;
                Matcher matcher = CLEAR.matcher(program.fragment);
                if (!matcher.find()) continue;
                clearRed = clamp(matcher.group(1));
                clearGreen = clamp(matcher.group(2));
                clearBlue = clamp(matcher.group(3));
                clearAlpha = clamp(matcher.group(4));
            }
        } catch (RuntimeException failure) {
            shader = null;
        }
        return shader != null;
    }

    private static int clamp(String value) {
        return Math.max(0, Math.min(255, Math.round(Float.parseFloat(value) * 255f)));
    }

    private static boolean readAtlas() {
        if (atlas != null) return true;
        AbstractTexture texture = Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
        if (texture == null || texture.getId() <= 0) return false;
        int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int pack = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
        while (GL11.glGetError() != GL11.GL_NO_ERROR) { /* drop errors from the shadow pass */ }
        try {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture.getId());
            int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
            int height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
            if (width < 1 || height < 1 || width > 4096 || height > 4096) return false;
            int max = GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL);
            if (max < 0) max = 0;
            var buffer = org.lwjgl.BufferUtils.createByteBuffer(width * height * 4);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);
            if (GL11.glGetError() != GL11.GL_NO_ERROR) return false;
            byte[] rgba = new byte[width * height * 4];
            buffer.get(rgba);
            atlas = rgba;
            atlasWidth = width;
            atlasHeight = height;
            return max >= 0;
        } finally {
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, pack);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous);
        }
    }
}
