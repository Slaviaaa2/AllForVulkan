package dev.allforvulkan.framegraph;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.*;
import static org.lwjgl.vulkan.VK12.VK_API_VERSION_1_2;

/** Headless composite chain. The compiled frame graph chooses barriers and recording;
 * this does not translate GL calls and does not use Zink. */
public final class CompositeChain {
    public static final int SIZE = 8;
    private static final int FORMAT = VK_FORMAT_R8G8B8A8_UNORM;

    public record Result(boolean skipped, boolean passed, String detail) {
        static Result skip(String detail) { return new Result(true, false, detail); }
        static Result pass(String detail) { return new Result(false, true, detail); }
    }

    private CompositeChain() {}

    public static FrameGraph chain() {
        var graph = new FrameGraph();
        graph.pass("clear", FrameGraph.Kind.TRANSFER).write("scene", FrameGraph.Use.TRANSFER_DST);
        graph.pass("exposure", FrameGraph.Kind.GRAPHICS).read("scene", FrameGraph.Use.SAMPLE)
                .color("lit", FrameGraph.Load.DISCARD);
        graph.pass("grade", FrameGraph.Kind.GRAPHICS).read("lit", FrameGraph.Use.SAMPLE)
                .color("output", FrameGraph.Load.DISCARD);
        graph.pass("readback", FrameGraph.Kind.TRANSFER).read("output", FrameGraph.Use.TRANSFER_SRC);
        return graph;
    }

    public static FrameGraph parallelClears() {
        var graph = new FrameGraph();
        graph.pass("clear-red", FrameGraph.Kind.TRANSFER).write("red", FrameGraph.Use.TRANSFER_DST);
        graph.pass("clear-green", FrameGraph.Kind.TRANSFER).write("green", FrameGraph.Use.TRANSFER_DST);
        return graph;
    }

    public static Result execute() {
        try {
            return run();
        } catch (Unavailable unavailable) {
            return Result.skip(unavailable.getMessage());
        }
    }

    private static Result run() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (VK.getInstanceVersionSupported() < VK_API_VERSION_1_2)
                throw new Unavailable("Vulkan 1.2 loader is unavailable");
            var instanceInfo = VkInstanceCreateInfo.calloc(stack).sType$Default()
                    .pApplicationInfo(VkApplicationInfo.calloc(stack).sType$Default()
                            .apiVersion(VK_API_VERSION_1_2).pApplicationName(stack.UTF8("AFV composite")));
            var pointer = stack.mallocPointer(1);
            int created = vkCreateInstance(instanceInfo, null, pointer);
            if (created != VK_SUCCESS) throw new Unavailable("vkCreateInstance failed: " + created);
            var instance = new VkInstance(pointer.get(0), instanceInfo);
            long deviceHandle = 0;
            VkDevice device = null;
            long pipeline = 0;
            long reloaded = 0;
            long layout = 0;
            long descriptorLayout = 0;
            long renderPass = 0;
            long sampler = 0;
            long cache = 0;
            long restoredCache = 0;
            long descriptorPool = 0;
            long uniform = 0;
            long uniformMemory = 0;
            ByteBuffer uniformMapping = null;
            var images = new HashMap<String, GpuImage>();
            var sets = new HashMap<Integer, Long>();
            try {
                var count = stack.ints(0);
                check(vkEnumeratePhysicalDevices(instance, count, null), "enumerate devices");
                if (count.get(0) == 0) throw new Unavailable("No Vulkan device");
                var devices = stack.mallocPointer(count.get(0));
                check(vkEnumeratePhysicalDevices(instance, count, devices), "enumerate devices");
                String preferred = System.getProperty("afv.compositeDevice", "");
                VkPhysicalDevice physical = null;
                String deviceName = "";
                int family = -1;
                for (int index = 0; index < devices.capacity(); index++) {
                    var candidate = new VkPhysicalDevice(devices.get(index), instance);
                    var properties = VkPhysicalDeviceProperties.malloc(stack);
                    vkGetPhysicalDeviceProperties(candidate, properties);
                    String name = properties.deviceNameString();
                    if (!preferred.isBlank() && !name.toLowerCase(java.util.Locale.ROOT).contains(preferred.toLowerCase(java.util.Locale.ROOT)))
                        continue;
                    int queue = graphicsFamily(candidate, stack);
                    if (queue < 0 || !formatSupported(candidate, stack)) continue;
                    physical = candidate;
                    deviceName = name;
                    family = queue;
                    if (!preferred.isBlank() || name.toLowerCase(java.util.Locale.ROOT).contains("llvmpipe")) break;
                }
                if (physical == null) throw new Unavailable("No Vulkan device can sample and transfer R8G8B8A8");
                var limits = VkPhysicalDeviceProperties.malloc(stack);
                vkGetPhysicalDeviceProperties(physical, limits);
                int alignment = alignment(limits.limits().minUniformBufferOffsetAlignment());
                var queueInfo = VkDeviceQueueCreateInfo.calloc(1, stack);
                queueInfo.get(0).sType$Default().queueFamilyIndex(family).pQueuePriorities(stack.floats(1));
                var deviceInfo = VkDeviceCreateInfo.calloc(stack).sType$Default()
                        .pQueueCreateInfos(queueInfo).pEnabledFeatures(VkPhysicalDeviceFeatures.calloc(stack));
                pointer.rewind();
                check(vkCreateDevice(physical, deviceInfo, null, pointer), "vkCreateDevice");
                deviceHandle = pointer.get(0);
                device = new VkDevice(deviceHandle, physical, deviceInfo, VK_API_VERSION_1_2);
                var queuePointer = stack.mallocPointer(1);
                vkGetDeviceQueue(device, family, 0, queuePointer);
                var queue = new VkQueue(queuePointer.get(0), device);
                var memory = VkPhysicalDeviceMemoryProperties.malloc(stack);
                vkGetPhysicalDeviceMemoryProperties(physical, memory);
                for (String name : new String[] {"scene", "lit", "output", "red", "green"})
                    images.put(name, GpuImage.create(physical, device, memory, stack));
                uniform = createBuffer(device, stack, alignment * 4L, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
                int uniformType = memoryType(memory, requirements(device, uniform, stack),
                        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
                if (uniformType < 0) throw new Unavailable("No coherent host memory for uniforms");
                uniformMemory = allocate(device, stack, requirements(device, uniform, stack).size(), uniformType);
                check(vkBindBufferMemory(device, uniform, uniformMemory, 0), "bind uniform");
                var mapped = stack.mallocPointer(1);
                check(vkMapMemory(device, uniformMemory, 0, alignment * 4L, 0, mapped), "map uniform");
                uniformMapping = MemoryUtil.memByteBuffer(mapped.get(0), alignment * 4);
                var ring = new UniformRing(4, 16, alignment);
                sampler = createSampler(device, stack);
                descriptorLayout = createDescriptorLayout(device, stack);
                layout = createPipelineLayout(device, stack, descriptorLayout);
                renderPass = createRenderPass(device, stack);
                int[] vertex = CompositeShaders.vertex();
                int[] fragment = CompositeShaders.fragment();
                var library = new PipelineLibrary();
                var key = new PipelineLibrary.Key(PipelineLibrary.shaderIdentity(vertex, fragment), FORMAT, 1);
                cache = createPipelineCache(device, stack, null);
                pipeline = createPipeline(device, stack, layout, renderPass, cache, vertex, fragment);
                int generation = library.prepare(key);
                ByteBuffer blob = pipelineCacheData(device, cache, stack);
                int cacheBytes = blob.remaining();
                vkDestroyPipeline(device, pipeline, null);
                pipeline = 0;
                vkDestroyPipelineCache(device, cache, null);
                cache = 0;
                restoredCache = createPipelineCache(device, stack, blob);
                MemoryUtil.memFree(blob);
                reloaded = createPipeline(device, stack, layout, renderPass, restoredCache, vertex, fragment);
                int creates = 2;
                if (library.prepare(key) != generation) throw new IllegalStateException("Reloaded pipeline key was not reused");
                descriptorPool = createDescriptorPool(device, stack);
                var descriptors = new DescriptorCache();
                var compiled = chain().compile();
                var layouts = new HashMap<String, FrameGraph.Use>();
                int hitsBefore = library.hits();
                drawChain(device, queue, family, compiled, images, layouts, ring, 0, uniform, uniformMapping,
                        sampler, descriptorLayout, layout, renderPass, reloaded, descriptorPool, descriptors, sets, library, key);
                drawChain(device, queue, family, compiled, images, layouts, ring, 1, uniform, uniformMapping,
                        sampler, descriptorLayout, layout, renderPass, reloaded, descriptorPool, descriptors, sets, library, key);
                drawChain(device, queue, family, compiled, images, layouts, ring, 0, uniform, uniformMapping,
                        sampler, descriptorLayout, layout, renderPass, reloaded, descriptorPool, descriptors, sets, library, key);
                int descriptorHits = descriptors.hits();
                int pipelineHits = library.hits() - hitsBefore;
                if (library.misses() != 1) throw new IllegalStateException("Expected one pipeline miss, got " + library.misses());
                if (pipelineHits < 4) throw new IllegalStateException("Pipeline library did not reuse the composite pipeline: " + pipelineHits);
                if (descriptorHits < 2) throw new IllegalStateException("Descriptor cache did not reuse a composite set: " + descriptorHits);
                var parallel = parallelClears().compile();
                if (parallel.waves.size() != 1 || parallel.waves.get(0).size() != 2)
                    throw new IllegalStateException("Independent clears were not one recording wave: " + parallel.waves);
                clearPair(device, queue, family, images.get("red"), images.get("green"), memory);
                String detail = "device=" + deviceName + ", " + FrameGraph.describe(compiled)
                        + ", barriersIssued=" + compiled.barrierCount()
                        + ", pipelineCreates=" + creates + ", pipelineHits=" + pipelineHits
                        + ", pipelineCacheBytes=" + cacheBytes
                        + ", descriptorHits=" + descriptorHits + ", parallelCommandBuffers=2, ringFrames=4";
                return Result.pass(detail);
            } finally {
                if (device != null) vkDeviceWaitIdle(device);
                for (long set : sets.values()) { /* pool frees sets */ }
                if (descriptorPool != 0) vkDestroyDescriptorPool(device, descriptorPool, null);
                if (reloaded != 0) vkDestroyPipeline(device, reloaded, null);
                if (pipeline != 0) vkDestroyPipeline(device, pipeline, null);
                if (restoredCache != 0) vkDestroyPipelineCache(device, restoredCache, null);
                if (cache != 0) vkDestroyPipelineCache(device, cache, null);
                if (layout != 0) vkDestroyPipelineLayout(device, layout, null);
                if (descriptorLayout != 0) vkDestroyDescriptorSetLayout(device, descriptorLayout, null);
                if (renderPass != 0) vkDestroyRenderPass(device, renderPass, null);
                if (sampler != 0) vkDestroySampler(device, sampler, null);
                if (uniformMemory != 0) { vkUnmapMemory(device, uniformMemory); vkFreeMemory(device, uniformMemory, null); }
                if (uniform != 0) vkDestroyBuffer(device, uniform, null);
                for (var image : images.values()) image.close(device);
                if (deviceHandle != 0) vkDestroyDevice(device, null);
                vkDestroyInstance(instance, null);
            }
        }
    }

    private static void drawChain(VkDevice device, VkQueue queue, int family, FrameGraph.Compiled compiled,
                                  HashMap<String, GpuImage> images, HashMap<String, FrameGraph.Use> layouts, UniformRing ring, int frame,
                                  long uniform, ByteBuffer mapping, long sampler, long descriptorLayout, long pipelineLayout, long renderPass,
                                  long pipeline, long descriptorPool, DescriptorCache descriptors, HashMap<Integer, Long> sets,
                                  PipelineLibrary library, PipelineLibrary.Key key) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int exposure = ring.acquire(frame * 2);
            int grade = ring.acquire(frame * 2 + 1);
            writeFactor(mapping, exposure, 0.5f, 0.5f, 0.5f, 1f);
            writeFactor(mapping, grade, 1f, 1f, 1f, 1f);
            long pool = createCommandPool(device, stack, family);
            long outputCopy = createBuffer(device, stack, SIZE * SIZE * 4L, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
            var memoryProps = VkPhysicalDeviceMemoryProperties.malloc(stack);
            vkGetPhysicalDeviceMemoryProperties(device.getPhysicalDevice(), memoryProps);
            var outputRequirements = requirements(device, outputCopy, stack);
            int outputType = memoryType(memoryProps, outputRequirements, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
            if (outputType < 0) throw new Unavailable("No coherent readback memory");
            long outputMemory = allocate(device, stack, outputRequirements.size(), outputType);
            var framebuffers = new ArrayList<Long>();
            try {
                check(vkBindBufferMemory(device, outputCopy, outputMemory, 0), "bind readback");
                long command = allocateCommand(device, stack, pool);
                check(vkBeginCommandBuffer(new VkCommandBuffer(command, device), VkCommandBufferBeginInfo.calloc(stack).sType$Default()), "begin chain");
                var buffer = new VkCommandBuffer(command, device);
                int issued = 0;
                for (int index = 0; index < compiled.passes.size(); index++) {
                    for (var planned : compiled.barriersBefore(index)) {
                        FrameGraph.Use from = layouts.getOrDefault(planned.resource, FrameGraph.Use.UNDEFINED);
                        if (from != planned.to) {
                            barrier(buffer, stack, images.get(planned.resource), from, planned.to);
                            issued++;
                        }
                        layouts.put(planned.resource, planned.to);
                    }
                    var pass = compiled.passes.get(index);
                    switch (pass.name()) {
                        case "clear" -> clear(buffer, stack, images.get("scene"), 64, 128, 192, 255);
                        case "exposure" -> {
                            library.prepare(key);
                            framebuffers.add(draw(device, buffer, stack, images.get("scene"), images.get("lit"), uniform, exposure,
                                    sampler, descriptorLayout, pipelineLayout, renderPass, pipeline, descriptorPool, descriptors, sets));
                        }
                        case "grade" -> {
                            library.prepare(key);
                            framebuffers.add(draw(device, buffer, stack, images.get("lit"), images.get("output"), uniform, grade,
                                    sampler, descriptorLayout, pipelineLayout, renderPass, pipeline, descriptorPool, descriptors, sets));
                        }
                        case "readback" -> copy(buffer, stack, images.get("output"), outputCopy);
                        default -> throw new IllegalStateException("Unexpected pass " + pass.name());
                    }
                }
                if (frame == 0 && issued != compiled.barrierCount())
                    throw new IllegalStateException("Dropped frame-graph barriers: " + issued + " of " + compiled.barrierCount());
                check(vkEndCommandBuffer(buffer), "end chain");
                submit(device, queue, stack, command);
                read(device, outputMemory, stack, 32, 64, 96, 255, "composite frame " + frame);
            } finally {
                for (long framebuffer : framebuffers) vkDestroyFramebuffer(device, framebuffer, null);
                ring.retire(frame * 2);
                ring.retire(frame * 2 + 1);
                vkDestroyBuffer(device, outputCopy, null);
                vkFreeMemory(device, outputMemory, null);
                vkDestroyCommandPool(device, pool, null);
            }
        }
    }

    private static void clearPair(VkDevice device, VkQueue queue, int family, GpuImage red, GpuImage green,
                                  VkPhysicalDeviceMemoryProperties memory) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
        long pool = createCommandPool(device, stack, family);
        long redBuffer = createBuffer(device, stack, SIZE * SIZE * 4L, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        long greenBuffer = createBuffer(device, stack, SIZE * SIZE * 4L, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        long redMemory = bindReadback(device, stack, memory, redBuffer);
        long greenMemory = bindReadback(device, stack, memory, greenBuffer);
        try {
            long first = allocateCommand(device, stack, pool);
            long second = allocateCommand(device, stack, pool);
            recordClear(device, stack, first, red, redBuffer, 255, 0, 0);
            recordClear(device, stack, second, green, greenBuffer, 0, 255, 0);
            submit(device, queue, stack, first, second);
            read(device, redMemory, stack, 255, 0, 0, 255, "parallel red");
            read(device, greenMemory, stack, 0, 255, 0, 255, "parallel green");
        } finally {
            vkDestroyBuffer(device, redBuffer, null);
            vkDestroyBuffer(device, greenBuffer, null);
            vkFreeMemory(device, redMemory, null);
            vkFreeMemory(device, greenMemory, null);
            vkDestroyCommandPool(device, pool, null);
        }
        }
    }

    private static void recordClear(VkDevice device, MemoryStack stack, long command, GpuImage image, long buffer, int r, int g, int b) {
        var recorded = new VkCommandBuffer(command, device);
        check(vkBeginCommandBuffer(recorded, VkCommandBufferBeginInfo.calloc(stack).sType$Default()), "begin clear");
        barrier(recorded, stack, image, FrameGraph.Use.UNDEFINED, FrameGraph.Use.TRANSFER_DST);
        clear(recorded, stack, image, r, g, b, 255);
        barrier(recorded, stack, image, FrameGraph.Use.TRANSFER_DST, FrameGraph.Use.TRANSFER_SRC);
        copy(recorded, stack, image, buffer);
        check(vkEndCommandBuffer(recorded), "end clear");
    }

    /** Returns the framebuffer. It has to outlive command-buffer execution. */
    private static long draw(VkDevice device, VkCommandBuffer command, MemoryStack stack, GpuImage source, GpuImage target,
                             long uniform, int offset, long sampler, long descriptorLayout, long pipelineLayout, long renderPass,
                             long pipeline, long descriptorPool, DescriptorCache cache, HashMap<Integer, Long> sets) {
        int slot = cache.slot(new DescriptorCache.Key(uniform, offset, 16, source.view, sampler, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL));
        Long cached = sets.get(slot);
        long set = cached == null ? allocateSet(device, stack, descriptorPool, descriptorLayout) : cached;
        if (cached == null) {
            writeSet(device, stack, set, uniform, offset, source.view, sampler);
            sets.put(slot, set);
        }
        long framebuffer = createFramebuffer(device, stack, renderPass, target.view);
        var clear = VkClearValue.calloc(1, stack);
        vkCmdBeginRenderPass(command, VkRenderPassBeginInfo.calloc(stack).sType$Default().renderPass(renderPass)
                .framebuffer(framebuffer).pClearValues(clear).renderArea(area -> area.extent().set(SIZE, SIZE)), VK_SUBPASS_CONTENTS_INLINE);
        vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
        vkCmdBindDescriptorSets(command, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, 0, stack.longs(set), null);
        vkCmdSetViewport(command, 0, VkViewport.calloc(1, stack).x(0).y(0).width(SIZE).height(SIZE).minDepth(0).maxDepth(1));
        vkCmdSetScissor(command, 0, VkRect2D.calloc(1, stack).extent(extent -> extent.set(SIZE, SIZE)));
        vkCmdDraw(command, 3, 1, 0, 0);
        vkCmdEndRenderPass(command);
        return framebuffer;
    }

    private static void barrier(VkCommandBuffer command, MemoryStack stack, GpuImage image, FrameGraph.Use from, FrameGraph.Use to) {
        var barrier = VkImageMemoryBarrier.calloc(1, stack).sType$Default().image(image.handle)
                .oldLayout(layout(from)).newLayout(layout(to)).srcAccessMask(access(from)).dstAccessMask(access(to))
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
        barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
        vkCmdPipelineBarrier(command, stage(from), stage(to), 0, null, null, barrier);
    }

    private static void clear(VkCommandBuffer command, MemoryStack stack, GpuImage image, int r, int g, int b, int a) {
        var color = VkClearColorValue.calloc(stack);
        color.float32(0, r / 255f).float32(1, g / 255f).float32(2, b / 255f).float32(3, a / 255f);
        var range = VkImageSubresourceRange.calloc(1, stack).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
        vkCmdClearColorImage(command, image.handle, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, color, range);
    }

    private static void copy(VkCommandBuffer command, MemoryStack stack, GpuImage image, long buffer) {
        var region = VkBufferImageCopy.calloc(1, stack);
        region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
        region.imageExtent().set(SIZE, SIZE, 1);
        vkCmdCopyImageToBuffer(command, image.handle, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, buffer, region);
    }

    private static void submit(VkDevice device, VkQueue queue, MemoryStack stack, long... commands) {
        var fence = stack.mallocLong(1);
        check(vkCreateFence(device, VkFenceCreateInfo.calloc(stack).sType$Default(), null, fence), "fence");
        try {
            var buffers = stack.mallocPointer(commands.length);
            for (long command : commands) buffers.put(command);
            buffers.flip();
            check(vkQueueSubmit(queue, VkSubmitInfo.calloc(stack).sType$Default().pCommandBuffers(buffers), fence.get(0)), "submit");
            check(vkWaitForFences(device, fence, true, 10_000_000_000L), "wait");
        } finally {
            vkDestroyFence(device, fence.get(0), null);
        }
    }

    private static void read(VkDevice device, long memory, MemoryStack stack, int r, int g, int b, int a, String label) {
        var mapped = stack.mallocPointer(1);
        check(vkMapMemory(device, memory, 0, SIZE * SIZE * 4L, 0, mapped), "map readback");
        try {
            ByteBuffer pixels = MemoryUtil.memByteBuffer(mapped.get(0), SIZE * SIZE * 4);
            for (int i = 0; i < SIZE * SIZE; i++) {
                int actualR = pixels.get(i * 4) & 0xff;
                int actualG = pixels.get(i * 4 + 1) & 0xff;
                int actualB = pixels.get(i * 4 + 2) & 0xff;
                int actualA = pixels.get(i * 4 + 3) & 0xff;
                if (actualR != r || actualG != g || actualB != b || actualA != a)
                    throw new IllegalStateException(label + " pixel " + i + " is " + actualR + "," + actualG + "," + actualB + "," + actualA
                            + " expected " + r + "," + g + "," + b + "," + a);
            }
        } finally {
            vkUnmapMemory(device, memory);
        }
    }

    private static void writeFactor(ByteBuffer mapping, int offset, float r, float g, float b, float a) {
        mapping.putFloat(offset, r).putFloat(offset + 4, g).putFloat(offset + 8, b).putFloat(offset + 12, a);
    }

    private static int stage(FrameGraph.Use use) {
        return switch (use) {
            case UNDEFINED -> VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
            case COLOR -> VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
            case DEPTH -> VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT;
            case SAMPLE, STORAGE_READ, STORAGE_WRITE -> VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
            case TRANSFER_SRC, TRANSFER_DST -> VK_PIPELINE_STAGE_TRANSFER_BIT;
        };
    }

    private static int access(FrameGraph.Use use) {
        return switch (use) {
            case UNDEFINED -> 0;
            case COLOR -> VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
            case DEPTH -> VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
            case SAMPLE, STORAGE_READ -> VK_ACCESS_SHADER_READ_BIT;
            case STORAGE_WRITE -> VK_ACCESS_SHADER_WRITE_BIT;
            case TRANSFER_SRC -> VK_ACCESS_TRANSFER_READ_BIT;
            case TRANSFER_DST -> VK_ACCESS_TRANSFER_WRITE_BIT;
        };
    }

    private static int layout(FrameGraph.Use use) {
        return switch (use) {
            case UNDEFINED -> VK_IMAGE_LAYOUT_UNDEFINED;
            case COLOR -> VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
            case DEPTH -> VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL;
            case SAMPLE, STORAGE_READ -> VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            case STORAGE_WRITE -> VK_IMAGE_LAYOUT_GENERAL;
            case TRANSFER_SRC -> VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
            case TRANSFER_DST -> VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        };
    }

    private static int graphicsFamily(VkPhysicalDevice device, MemoryStack stack) {
        var count = stack.ints(0);
        vkGetPhysicalDeviceQueueFamilyProperties(device, count, null);
        var queues = VkQueueFamilyProperties.malloc(count.get(0), stack);
        vkGetPhysicalDeviceQueueFamilyProperties(device, count, queues);
        for (int index = 0; index < queues.capacity(); index++)
            if (queues.get(index).queueCount() > 0 && (queues.get(index).queueFlags() & VK_QUEUE_GRAPHICS_BIT) != 0
                    && (queues.get(index).queueFlags() & VK_QUEUE_TRANSFER_BIT) != 0) return index;
        for (int index = 0; index < queues.capacity(); index++)
            if (queues.get(index).queueCount() > 0 && (queues.get(index).queueFlags() & VK_QUEUE_GRAPHICS_BIT) != 0) return index;
        return -1;
    }

    private static boolean formatSupported(VkPhysicalDevice device, MemoryStack stack) {
        var properties = VkFormatProperties.malloc(stack);
        vkGetPhysicalDeviceFormatProperties(device, FORMAT, properties);
                int features = properties.optimalTilingFeatures();
        int required = VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT | VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT
                | VK_FORMAT_FEATURE_TRANSFER_SRC_BIT | VK_FORMAT_FEATURE_TRANSFER_DST_BIT;
        return (features & required) == required;
    }

    private static int alignment(long reported) {
        int value = reported < 16 ? 16 : reported > Integer.MAX_VALUE ? 256 : (int) reported;
        if (Integer.bitCount(value) != 1) value = Integer.highestOneBit(value) << 1;
        return value;
    }

    private static long createBuffer(VkDevice device, MemoryStack stack, long size, int usage) {
        var handle = stack.mallocLong(1);
        check(vkCreateBuffer(device, VkBufferCreateInfo.calloc(stack).sType$Default().size(size).usage(usage)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE), null, handle), "create buffer");
        return handle.get(0);
    }

    private static VkMemoryRequirements requirements(VkDevice device, long buffer, MemoryStack stack) {
        var requirements = VkMemoryRequirements.malloc(stack);
        vkGetBufferMemoryRequirements(device, buffer, requirements);
        return requirements;
    }

    private static int memoryType(VkPhysicalDeviceMemoryProperties properties, VkMemoryRequirements requirements, int flags) {
        for (int index = 0; index < properties.memoryTypeCount(); index++)
            if ((requirements.memoryTypeBits() & (1 << index)) != 0 && (properties.memoryTypes(index).propertyFlags() & flags) == flags)
                return index;
        return -1;
    }

    private static int memoryType(VkPhysicalDeviceMemoryProperties properties, int bits, int flags) {
        for (int index = 0; index < properties.memoryTypeCount(); index++)
            if ((bits & (1 << index)) != 0 && (properties.memoryTypes(index).propertyFlags() & flags) == flags) return index;
        if (flags == 0) for (int index = 0; index < properties.memoryTypeCount(); index++)
            if ((bits & (1 << index)) != 0) return index;
        return -1;
    }

    private static long allocate(VkDevice device, MemoryStack stack, long size, int type) {
        var handle = stack.mallocLong(1);
        check(vkAllocateMemory(device, VkMemoryAllocateInfo.calloc(stack).sType$Default().allocationSize(size).memoryTypeIndex(type), null, handle), "allocate");
        return handle.get(0);
    }

    private static long bindReadback(VkDevice device, MemoryStack stack, VkPhysicalDeviceMemoryProperties memory, long buffer) {
        var requirements = requirements(device, buffer, stack);
        int type = memoryType(memory, requirements, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        if (type < 0) throw new Unavailable("No coherent readback memory");
        long allocated = allocate(device, stack, requirements.size(), type);
        check(vkBindBufferMemory(device, buffer, allocated, 0), "bind readback");
        return allocated;
    }

    private static long createSampler(VkDevice device, MemoryStack stack) {
        var handle = stack.mallocLong(1);
        check(vkCreateSampler(device, VkSamplerCreateInfo.calloc(stack).sType$Default()
                .magFilter(VK_FILTER_NEAREST).minFilter(VK_FILTER_NEAREST)
                .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).minLod(0).maxLod(0), null, handle), "sampler");
        return handle.get(0);
    }

    private static long createDescriptorLayout(VkDevice device, MemoryStack stack) {
        var bindings = VkDescriptorSetLayoutBinding.calloc(2, stack);
        bindings.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
        bindings.get(1).binding(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
        var handle = stack.mallocLong(1);
        check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings), null, handle), "descriptor layout");
        return handle.get(0);
    }

    private static long createPipelineLayout(VkDevice device, MemoryStack stack, long descriptorLayout) {
        var handle = stack.mallocLong(1);
        check(vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                .pSetLayouts(stack.longs(descriptorLayout)), null, handle), "pipeline layout");
        return handle.get(0);
    }

    private static long createRenderPass(VkDevice device, MemoryStack stack) {
        var attachment = VkAttachmentDescription.calloc(1, stack);
        attachment.get(0).format(FORMAT).samples(VK_SAMPLE_COUNT_1_BIT)
                .loadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                .initialLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL).finalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        var reference = VkAttachmentReference.calloc(1, stack);
        reference.get(0).attachment(0).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        var subpass = VkSubpassDescription.calloc(1, stack);
        subpass.get(0).pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS).colorAttachmentCount(1).pColorAttachments(reference);
        var dependency = VkSubpassDependency.calloc(1, stack);
        dependency.get(0).srcSubpass(VK_SUBPASS_EXTERNAL).dstSubpass(0)
                .srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT).dstStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
                .dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
        var handle = stack.mallocLong(1);
        check(vkCreateRenderPass(device, VkRenderPassCreateInfo.calloc(stack).sType$Default()
                .pAttachments(attachment).pSubpasses(subpass).pDependencies(dependency), null, handle), "render pass");
        return handle.get(0);
    }

    private static long createFramebuffer(VkDevice device, MemoryStack stack, long renderPass, long view) {
        var handle = stack.mallocLong(1);
        check(vkCreateFramebuffer(device, VkFramebufferCreateInfo.calloc(stack).sType$Default().renderPass(renderPass)
                .pAttachments(stack.longs(view)).width(SIZE).height(SIZE).layers(1), null, handle), "framebuffer");
        return handle.get(0);
    }

    private static long createPipelineCache(VkDevice device, MemoryStack stack, ByteBuffer data) {
        var info = VkPipelineCacheCreateInfo.calloc(stack).sType$Default();
        if (data != null && data.remaining() > 0) info.pInitialData(data);
        var handle = stack.mallocLong(1);
        check(vkCreatePipelineCache(device, info, null, handle), "pipeline cache");
        return handle.get(0);
    }

    private static ByteBuffer pipelineCacheData(VkDevice device, long cache, MemoryStack stack) {
        var size = stack.mallocPointer(1);
        check(vkGetPipelineCacheData(device, cache, size, null), "pipeline cache size");
        if (size.get(0) == 0 || size.get(0) > Integer.MAX_VALUE) return MemoryUtil.memAlloc(0);
        ByteBuffer data = MemoryUtil.memAlloc((int) size.get(0));
        check(vkGetPipelineCacheData(device, cache, size, data), "pipeline cache data");
        return data;
    }

    private static long createPipeline(VkDevice device, MemoryStack stack, long layout, long renderPass, long cache, int[] vertex, int[] fragment) {
        long vertexModule = shader(device, stack, vertex);
        long fragmentModule = shader(device, stack, fragment);
        try {
            var stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            stages.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(vertexModule).pName(stack.UTF8("main"));
            stages.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fragmentModule).pName(stack.UTF8("main"));
            var blend = VkPipelineColorBlendAttachmentState.calloc(1, stack);
            blend.get(0).colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT);
            var info = VkGraphicsPipelineCreateInfo.calloc(1, stack);
            info.get(0).sType$Default().pStages(stages).renderPass(renderPass).layout(layout)
                    .pVertexInputState(VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default())
                    .pInputAssemblyState(VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType$Default().topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST))
                    .pViewportState(VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default().viewportCount(1).scissorCount(1))
                    .pRasterizationState(VkPipelineRasterizationStateCreateInfo.calloc(stack).sType$Default()
                            .polygonMode(VK_POLYGON_MODE_FILL).cullMode(VK_CULL_MODE_NONE).frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE).lineWidth(1))
                    .pMultisampleState(VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default().rasterizationSamples(VK_SAMPLE_COUNT_1_BIT))
                    .pColorBlendState(VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default().pAttachments(blend))
                    .pDynamicState(VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default()
                            .pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR)));
            var handle = stack.mallocLong(1);
            check(vkCreateGraphicsPipelines(device, cache, info, null, handle), "pipeline");
            return handle.get(0);
        } finally {
            vkDestroyShaderModule(device, vertexModule, null);
            vkDestroyShaderModule(device, fragmentModule, null);
        }
    }

    private static long shader(VkDevice device, MemoryStack stack, int[] words) {
        ByteBuffer code = MemoryUtil.memAlloc(words.length * 4);
        code.asIntBuffer().put(words);
        try {
            var handle = stack.mallocLong(1);
            check(vkCreateShaderModule(device, VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code), null, handle), "shader");
            return handle.get(0);
        } finally {
            MemoryUtil.memFree(code);
        }
    }

    private static long createDescriptorPool(VkDevice device, MemoryStack stack) {
        var sizes = VkDescriptorPoolSize.calloc(2, stack);
        sizes.get(0).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(16);
        sizes.get(1).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(16);
        var handle = stack.mallocLong(1);
        check(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                .maxSets(16).pPoolSizes(sizes), null, handle), "descriptor pool");
        return handle.get(0);
    }

    private static long allocateSet(VkDevice device, MemoryStack stack, long pool, long layout) {
        var handle = stack.mallocLong(1);
        check(vkAllocateDescriptorSets(device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                .descriptorPool(pool).pSetLayouts(stack.longs(layout)), handle), "descriptor set");
        return handle.get(0);
    }

    private static void writeSet(VkDevice device, MemoryStack stack, long set, long buffer, int offset, long view, long sampler) {
        var bufferInfo = VkDescriptorBufferInfo.calloc(1, stack).buffer(buffer).offset(offset).range(16);
        var imageInfo = VkDescriptorImageInfo.calloc(1, stack).sampler(sampler).imageView(view)
                .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        var writes = VkWriteDescriptorSet.calloc(2, stack);
        writes.get(0).sType$Default().dstSet(set).dstBinding(0).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                .descriptorCount(1).pBufferInfo(bufferInfo);
        writes.get(1).sType$Default().dstSet(set).dstBinding(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                .descriptorCount(1).pImageInfo(imageInfo);
        vkUpdateDescriptorSets(device, writes, null);
    }

    private static long createCommandPool(VkDevice device, MemoryStack stack, int family) {
        var handle = stack.mallocLong(1);
        check(vkCreateCommandPool(device, VkCommandPoolCreateInfo.calloc(stack).sType$Default().queueFamilyIndex(family), null, handle), "command pool");
        return handle.get(0);
    }

    private static long allocateCommand(VkDevice device, MemoryStack stack, long pool) {
        var handle = stack.mallocPointer(1);
        check(vkAllocateCommandBuffers(device, VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                .commandPool(pool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1), handle), "command");
        return handle.get(0);
    }

    private static void check(int result, String operation) {
        if (result != VK_SUCCESS) throw new IllegalStateException(operation + " failed: " + result);
    }

    private static final class GpuImage implements AutoCloseable {
        final long handle;
        final long memory;
        final long view;
        private GpuImage(long handle, long memory, long view) {
            this.handle = handle;
            this.memory = memory;
            this.view = view;
        }
        static GpuImage create(VkPhysicalDevice physical, VkDevice device, VkPhysicalDeviceMemoryProperties memory, MemoryStack stack) {
            var handle = stack.mallocLong(1);
            var info = VkImageCreateInfo.calloc(stack).sType$Default().imageType(VK_IMAGE_TYPE_2D).format(FORMAT)
                    .mipLevels(1).arrayLayers(1).samples(VK_SAMPLE_COUNT_1_BIT).tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            info.extent().set(SIZE, SIZE, 1);
            check(vkCreateImage(device, info, null, handle), "image");
            long image = handle.get(0);
            var requirements = VkMemoryRequirements.malloc(stack);
            vkGetImageMemoryRequirements(device, image, requirements);
            int type = memoryType(memory, requirements.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            if (type < 0) type = memoryType(memory, requirements.memoryTypeBits(), 0);
            if (type < 0) throw new IllegalStateException("No image memory");
            long allocated = allocate(device, stack, requirements.size(), type);
            check(vkBindImageMemory(device, image, allocated, 0), "bind image");
            var view = stack.mallocLong(1);
            var viewInfo = VkImageViewCreateInfo.calloc(stack).sType$Default().image(image).viewType(VK_IMAGE_VIEW_TYPE_2D).format(FORMAT);
            viewInfo.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            check(vkCreateImageView(device, viewInfo, null, view), "image view");
            return new GpuImage(image, allocated, view.get(0));
        }
        void close(VkDevice device) {
            if (view != 0) vkDestroyImageView(device, view, null);
            if (handle != 0) vkDestroyImage(device, handle, null);
            if (memory != 0) vkFreeMemory(device, memory, null);
        }
        public void close() {}
    }

    private static final class Unavailable extends RuntimeException {
        Unavailable(String message) { super(message); }
    }
}
