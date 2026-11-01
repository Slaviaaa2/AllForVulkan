package dev.allforvulkan.framegraph;

/** The opaque Complementary shadow color and one distorted quad. The pack itself is not in the repo. */
public final class ComplementaryShadowChecks {
    private ComplementaryShadowChecks() {}

    static void check() {
        float[] opaque = ComplementaryShadow.naturalShadow(0.8f, 0.2f, 0.1f, 1f);
        if (opaque[0] != 0f || opaque[1] != 0f || opaque[2] != 0f || opaque[3] != 1f)
            throw new AssertionError("opaque shadow color was not black");
        float[] clear = ComplementaryShadow.naturalShadow(0f, 0f, 0f, 0f);
        if (Math.abs(clear[0] - 0.1f) > 1e-5f) throw new AssertionError("transparent shadow color " + clear[0]);
        if (!ComplementaryShadow.isProgram(
                "void DoNaturalShadowCalculation() {}\nfloat shadowMapBias;\ngl_Position.z = gl_Position.z * 0.2;"))
            throw new AssertionError("shadow program was not recognized");
        if (ComplementaryShadow.isProgram("texture2D(gtexture, texcoord) * tint"))
            throw new AssertionError("texture tint program was treated as Complementary");
        var shader = ComplementaryShadow.shader();
        if (shader == null) throw new AssertionError("complementary shadow spir-v did not compile");
        float[] identity = new float[16];
        float[] projection = new float[16];
        for (int column = 0; column < 4; column++) {
            identity[column * 4 + column] = 1f;
            projection[column * 4 + column] = 1f;
        }
        projection[0] = 1f / 64f;
        projection[5] = 1f / 64f;
        float[] vertices = {
                -4f, -4f, 0f, 0.5f, 0.5f, 1f, 1f, 1f, 1f,
                4f, -4f, 0f, 0.5f, 0.5f, 1f, 1f, 1f, 1f,
                4f, 4f, 0f, 0.5f, 0.5f, 1f, 1f, 1f, 1f,
                -4f, -4f, 0f, 0.5f, 0.5f, 1f, 1f, 1f, 1f,
                4f, 4f, 0f, 0.5f, 0.5f, 1f, 1f, 1f, 1f,
                -4f, 4f, 0f, 0.5f, 0.5f, 1f, 1f, 1f, 1f
        };
        var rendered = ShadowMeshPass.render(shader, new ShadowMeshPass.Draw(
                vertices, new byte[][] {{(byte) 255, (byte) 255, (byte) 255, (byte) 255}}, 1, 1,
                identity, projection, 32, 0, 0, 0, 0, true));
        if (rendered.skipped() || rendered.rgba() == null)
            throw new IllegalStateException(rendered.detail() == null ? "no image" : rendered.detail());
        int index = (16 * 32 + 16) * 4;
        int red = rendered.rgba()[index] & 255;
        int green = rendered.rgba()[index + 1] & 255;
        int blue = rendered.rgba()[index + 2] & 255;
        int alpha = rendered.rgba()[index + 3] & 255;
        if (red != 0 || green != 0 || blue != 0 || alpha != 255)
            throw new AssertionError("distorted opaque quad " + red + "," + green + "," + blue + "," + alpha);
        System.out.println("AFV_COMPLEMENTARY_SHADOW_PASS: center=0,0,0,255");
    }
}
