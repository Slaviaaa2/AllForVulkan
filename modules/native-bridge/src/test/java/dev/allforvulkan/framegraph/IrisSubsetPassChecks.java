package dev.allforvulkan.framegraph;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

/** Lavapipe draw of the compiled shadow and gbuffer subset. One empty program must not cancel the others. */
public final class IrisSubsetPassChecks {
    public static void main(String[] args) throws Exception {
        Path smoke = Path.of("..", "..", "tests", "shaderpacks", "AFV-Smoke");
        var compiled = IrisSubset.compile(IrisPackPlan.read(smoke));
        var shaders = new ArrayList<IrisSubset.Shader>();
        shaders.add(new IrisSubset.Shader("gbuffers_block", IrisPackPlan.Stage.GBUFFER, new byte[0], new byte[0]));
        shaders.addAll(compiled.shaders());
        var result = IrisSubsetPass.execute(shaders);
        if (result.skipped()) {
            if (Boolean.getBoolean("afv.requireCompositeDevice")) throw new IllegalStateException(result.detail());
            System.out.println("AFV_IRIS_SUBSET_DRAW_SKIP: " + result.detail());
            return;
        }
        if (!result.ran().equals("shadow,gbuffers_textured_lit,gbuffers_terrain"))
            throw new IllegalStateException(result.detail());
        if (!result.fallback().contains("gbuffers_block (empty SPIR-V)"))
            throw new IllegalStateException(result.detail());
        var decision = ShaderBackendSelection.choose("direct", true, IrisPackPlan.read(smoke));
        if (!decision.fellBack() || !decision.active().equals("zink"))
            throw new IllegalStateException("Subset draw replaced the Zink client: " + decision);
        float[] white = {
                -1f, -1f, 0f, 0.5f, 0.5f, 1f, 1f, 1f, 1f,
                3f, -1f, 0f, 0.5f, 0.5f, 1f, 1f, 1f, 1f,
                -1f, 3f, 0f, 0.5f, 0.5f, 1f, 1f, 1f, 1f
        };
        var tint = IrisSubsetPass.execute(compiled.shaders(), new IrisSubsetPass.Mesh(
                new byte[] {64, (byte) 128, (byte) 192, (byte) 255}, white, 64, 128, 192, 255));
        if (tint.skipped() || !tint.ran().equals("shadow,gbuffers_textured_lit,gbuffers_terrain"))
            throw new IllegalStateException(tint.detail());
        var rejected = IrisSubsetPass.execute(compiled.shaders(), new IrisSubsetPass.Mesh(new byte[0], white, 0, 0, 0, 0));
        if (rejected.skipped() || !rejected.fallback().contains("mesh (texture must be 1x1 RGBA)"))
            throw new IllegalStateException(rejected.detail());
        System.out.println("AFV_IRIS_SUBSET_DRAW_PASS: " + result.detail() + "; tint=" + tint.detail());
    }
}
