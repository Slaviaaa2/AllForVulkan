package dev.allforvulkan.framegraph;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/** Scheduler, cache and SPIR-V structure checks. No Vulkan device required. */
public final class FrameGraphChecks {
    public static void main(String[] args) throws Exception {
        var chain = CompositeChain.chain().compile();
        require(chain.barrierCount() == 6, "Composite chain barriers: " + chain.barriers);
        require(chain.groups.size() == 4, "Composite passes merge across different attachments");
        require(chain.waves.size() == 4, "Dependent composite passes entered one wave");
        require(contains(chain, 0, "scene", FrameGraph.Use.UNDEFINED, FrameGraph.Use.TRANSFER_DST), "Clear transition missing");
        require(contains(chain, 1, "scene", FrameGraph.Use.TRANSFER_DST, FrameGraph.Use.SAMPLE), "Scene sample barrier missing");
        require(contains(chain, 2, "lit", FrameGraph.Use.COLOR, FrameGraph.Use.SAMPLE), "Lit sample barrier missing");
        require(contains(chain, 3, "output", FrameGraph.Use.COLOR, FrameGraph.Use.TRANSFER_SRC), "Readback barrier missing");

        var merged = new FrameGraph();
        merged.pass("sky", FrameGraph.Kind.GRAPHICS).color("output", FrameGraph.Load.CLEAR);
        merged.pass("clouds", FrameGraph.Kind.GRAPHICS).color("output", FrameGraph.Load.LOAD);
        var compiled = merged.compile();
        require(compiled.groups.size() == 1 && compiled.groups.get(0).size() == 2, "Compatible fullscreen passes did not merge");
        require(compiled.barrierCount() == 1, "Merged passes inserted a barrier: " + compiled.barriers);
        require(compiled.waves.size() == 2, "Framebuffer write order was dropped");

        var feedback = new FrameGraph();
        feedback.pass("write", FrameGraph.Kind.GRAPHICS).color("output", FrameGraph.Load.CLEAR);
        feedback.pass("sample", FrameGraph.Kind.GRAPHICS).read("output", FrameGraph.Use.SAMPLE).color("other", FrameGraph.Load.DISCARD);
        var split = feedback.compile();
        require(split.groups.size() == 2, "A sampled attachment merged into its producer");
        require(contains(split, 1, "output", FrameGraph.Use.COLOR, FrameGraph.Use.SAMPLE), "Feedback barrier missing");

        var parallel = CompositeChain.parallelClears().compile();
        require(parallel.waves.size() == 1 && parallel.waves.get(0).size() == 2, "Independent clears were serialized");

        var selection = ShaderBackendSelection.choose("direct", true);
        require(selection.fellBack() && selection.active().equals("zink") && selection.requested().equals("direct"), selection.toString());
        require(!ShaderBackendSelection.choose("zink", true).fellBack(), "Zink request fell back");
        require(ShaderBackendSelection.choose("direct", false).fellBack(), "Direct slice replaced the native renderer");

        var ring = new UniformRing(3, 64, 256);
        require(ring.offset(0) == 0 && ring.offset(1) == 256 && ring.offset(2) == 512, "Uniform slices overlap");
        require(ring.acquire(0) == 0 && ring.acquire(1) == 256, "In-flight slices were refused");
        requireThrows(() -> ring.acquire(0));
        ring.retire(0);
        require(ring.acquire(0) == 0, "Retired slice stayed in flight");

        var library = new PipelineLibrary();
        var key = new PipelineLibrary.Key("grade", 37, 1);
        int generation = library.prepare(key);
        require(library.prepare(key) == generation && library.hits() == 1 && library.misses() == 1, "Pipeline key was rebuilt");
        var restored = PipelineLibrary.restore(library.snapshot());
        require(restored.prepare(key) == generation && restored.hits() == 1 && restored.misses() == 0, "Snapshot was a miss");

        var descriptors = new DescriptorCache();
        var descriptor = new DescriptorCache.Key(3, 256, 16, 9, 4, 5);
        require(descriptors.slot(descriptor) == 0 && descriptors.slot(descriptor) == 0 && descriptors.hits() == 1, "Descriptor slot changed");
        require(descriptors.slot(new DescriptorCache.Key(3, 512, 16, 9, 4, 5)) == 1, "Distinct range reused a slot");

        require(dev.allforvulkan.bridge.PresentationPolicy.asyncPresent(null, null, false), "Async present default changed");
        require(!dev.allforvulkan.bridge.PresentationPolicy.asyncPresent("false", "true", false), "Explicit async property lost");
        require(dev.allforvulkan.bridge.PresentationPolicy.asyncPresent(null, "true", false), "Legacy async property ignored");
        require(dev.allforvulkan.bridge.PresentationPolicy.asyncPresent("false", null, true), "Frame pipeline became synchronous");
        require(dev.allforvulkan.bridge.PresentationPolicy.pipelineDepth(false, "3", 3) == 0, "Pipeline depth enabled without the frame pipeline");
        require(dev.allforvulkan.bridge.PresentationPolicy.pipelineDepth(true, null, 3) == 1, "Default pipeline depth exceeded 1");
        require(dev.allforvulkan.bridge.PresentationPolicy.pipelineDepth(true, "3", 3) == 3, "Requested depth was clamped");
        require(dev.allforvulkan.bridge.PresentationPolicy.pipelineDepth(true, "9", 3) == 3, "Depth exceeded the image ring");

        validateSpirv("composite-vert.spv", CompositeShaders.vertex());
        validateSpirv("composite-frag.spv", CompositeShaders.fragment());
        System.out.println("AFV_FRAME_GRAPH_PASS: barriers, merge, waves, ring, pipeline snapshot, descriptors, presentation defaults, SPIR-V");
    }

    private static boolean contains(FrameGraph.Compiled compiled, int pass, String resource, FrameGraph.Use from, FrameGraph.Use to) {
        return compiled.barriersBefore(pass).stream().anyMatch(barrier ->
                barrier.resource.equals(resource) && barrier.from == from && barrier.to == to);
    }

    private static void validateSpirv(String name, int[] words) throws Exception {
        require(words[0] == 0x07230203 && words[3] > 1, name + " is not SPIR-V");
        var path = Path.of("build", name);
        Files.createDirectories(path.getParent());
        var bytes = ByteBuffer.allocate(words.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int word : words) bytes.putInt(word);
        Files.write(path, bytes.array());
        if (Files.isExecutable(Path.of("/usr/bin/spirv-val"))) {
            var process = new ProcessBuilder("/usr/bin/spirv-val", path.toString()).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes());
            require(process.waitFor() == 0, name + " failed spirv-val: " + output);
        }
    }

    private static void requireThrows(Runnable action) {
        try { action.run(); throw new AssertionError("Expected failure did not happen"); }
        catch (IllegalStateException expected) { }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
