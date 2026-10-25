package dev.allforvulkan.framegraph;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Pack scheduling without a GPU or a running client. */
public final class IrisPackPlanChecks {
    public static void main(String[] args) throws Exception {
        Path smoke = Path.of("..", "..", "tests", "shaderpacks", "AFV-Smoke");
        var plan = IrisPackPlan.read(smoke);
        require(plan.supportedSummary().equals("shadow,gbuffers_textured_lit,gbuffers_terrain,final"), plan.supportedSummary());
        require(plan.blockedSummary().isEmpty(), plan.blockedSummary());
        var graph = plan.toGraph().compile();
        require(graph.passes.size() == 4, "Smoke pack passes: " + graph.passes.size());
        require(graph.groups.size() == 3, "Gbuffer passes did not merge: " + graph.groups);
        require(graph.barrierCount() == 5, "Smoke barriers: " + graph.barriers);

        Path root = Files.createTempDirectory("afv-pack");
        Path shaders = root.resolve("shaders");
        Files.createDirectories(shaders.resolve("world0"));
        Files.writeString(shaders.resolve("world0/gbuffers_terrain.fsh"), "void main(){}");
        writePair(shaders, "shadow");
        writePair(shaders, "gbuffers_terrain");
        Files.writeString(shaders.resolve("composite.csh"), "layout(local_size_x=8) in;");
        Files.writeString(shaders.resolve("final.fsh"), "layout(rgba8) uniform image2D colorimg; void main(){ imageStore(colorimg, ivec2(0), vec4(1)); }");
        var mixed = IrisPackPlan.read(root);
        require(mixed.supportedSummary().equals("shadow,gbuffers_terrain"), mixed.supportedSummary());
        require(mixed.blockedSummary().contains("composite (compute)"), mixed.blockedSummary());
        require(mixed.blockedSummary().contains("final (image load/store)"), mixed.blockedSummary());
        require(mixed.blockedSummary().contains("dimension folders"), mixed.blockedSummary());
        var mixedGraph = mixed.toGraph().compile();
        require(mixedGraph.passes.size() == 2, "Blocked programs entered the graph");

        Path zip = root.resolve("pack.zip");
        try (var archive = new ZipOutputStream(Files.newOutputStream(zip))) {
            archive.putNextEntry(new ZipEntry("shaders/shadow.vsh"));
            archive.write("void main(){}".getBytes());
            archive.closeEntry();
            archive.putNextEntry(new ZipEntry("shaders/shadow.fsh"));
            archive.write("void main(){}".getBytes());
            archive.closeEntry();
            archive.putNextEntry(new ZipEntry("shaders/world-1/final.fsh"));
            archive.write("void main(){}".getBytes());
            archive.closeEntry();
        }
        var zipped = IrisPackPlan.readZip(zip);
        require(zipped.supportedSummary().equals("shadow"), zipped.supportedSummary());
        require(zipped.blockedSummary().contains("dimension folders"), zipped.blockedSummary());

        var scheduled = ShaderBackendSelection.choose("direct", true, plan);
        require(scheduled.fellBack() && scheduled.active().equals("zink"), scheduled.toString());
        require(scheduled.coverage().equals("compiled:shadow,gbuffers_textured_lit,gbuffers_terrain"), scheduled.coverage());
        require(scheduled.fallback().contains("world draws stay on Zink"), scheduled.fallback());
        require(scheduled.fallback().contains("final (outside the shadow/gbuffer texture subset)"), scheduled.fallback());
        var blocked = ShaderBackendSelection.choose("direct", true, mixed);
        require(blocked.fallback().contains("composite (compute)"), blocked.fallback());
        require(ShaderBackendSelection.choose("direct", true).coverage().equals("composite-chain"), "Empty pack changed the fallback");
        require(ShaderBackendSelection.choose("direct", false, plan).coverage().equals("composite-chain"), "Pack plan replaced the native renderer");
        require(!ShaderBackendSelection.choose("zink", true, plan).fellBack(), "Zink request started scheduling");

        Path game = Files.createTempDirectory("afv-game");
        require(IrisPackPlan.fromGameDirectory(game).programs().isEmpty(), "Missing Iris config scheduled passes");
        Files.createDirectories(game.resolve("config"));
        Files.writeString(game.resolve("config/iris.properties"), "enableShaders=false\nshaderPack=pack.zip\n");
        require(IrisPackPlan.fromGameDirectory(game).programs().isEmpty(), "Disabled shaders were scheduled");
        Files.writeString(game.resolve("config/iris.properties"), "enableShaders=true\nshaderPack=../outside.zip\n");
        require(IrisPackPlan.fromGameDirectory(game).programs().isEmpty(), "Shader pack path escaped shaderpacks/");
        System.out.println("AFV_IRIS_PACK_PLAN_PASS: smoke schedule, blocked compute/image, zip, fallback");
    }

    private static void writePair(Path shaders, String name) throws Exception {
        Files.writeString(shaders.resolve(name + ".vsh"), "void main(){ gl_Position = vec4(0); }");
        Files.writeString(shaders.resolve(name + ".fsh"), "void main(){ gl_FragData[0] = vec4(1); }");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
