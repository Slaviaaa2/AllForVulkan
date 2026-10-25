package dev.allforvulkan.framegraph;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.util.Locale;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.*;
import static org.lwjgl.vulkan.VK12.VK_API_VERSION_1_2;

/** Draws one decoded shadow mesh with the subset program. The readback is bottom-row-first, like glGetTexImage.
 * The GL projection is flipped on Y so Vulkan's top-left framebuffer matches that order after the row flip.
 * Face culling stays off, matching Iris's shadow terrain pass. */
public final class ShadowMeshPass {
    private static final int FORMAT = VK_FORMAT_R8G8B8A8_UNORM;
    private static final int DEPTH_FORMAT = VK_FORMAT_D32_SFLOAT;
    private static final int VERTEX_FLOATS = 9;
    private static final int UNIFORM_BYTES = 256;

    /** Vertices are nine floats each. The texture is one RGBA mip. Matrices are column-major. */
    public record Draw(float[] vertices, byte[] texture, int textureWidth, int textureHeight,
                       float[] modelView, float[] projection, int size,
                       int clearRed, int clearGreen, int clearBlue, int clearAlpha) {
        String invalid() {
            if (vertices == null || vertices.length < VERTEX_FLOATS * 3 || vertices.length % VERTEX_FLOATS != 0)
                return "mesh needs complete vertices";
            if (texture == null || textureWidth < 1 || textureHeight < 1 || texture.length != textureWidth * textureHeight * 4)
                return "texture must be RGBA";
            if (textureWidth > 4096 || textureHeight > 4096) return "texture is larger than 4096";
            if (modelView == null || modelView.length != 16 || projection == null || projection.length != 16)
                return "matrices must be 4x4";
            if (size < 1 || size > 4096) return "shadow size is outside 1..4096";
            return null;
        }
    }

    public record Result(boolean skipped, byte[] rgba, String detail) {
        static Result skip(String detail) { return new Result(true, null, detail); }
        static Result fail(String detail) { return new Result(false, null, detail); }
    }

    private ShadowMeshPass() {}

    public static Result render(IrisSubset.Shader shader, Draw draw) {
        String invalid = draw == null ? "missing mesh" : draw.invalid();
        if (invalid != null) return Result.fail(invalid);
        if (shader == null || shader.vertex() == null || shader.vertex().length < 20 || shader.fragment() == null || shader.fragment().length < 20)
            return Result.fail("empty SPIR-V");
        try {
            return run(shader, draw);
        } catch (Unavailable unavailable) {
            return Result.skip(unavailable.getMessage());
        } catch (RuntimeException failure) {
            String message = failure.getMessage() == null ? failure.toString() : failure.getMessage();
            int breakAt = message.indexOf('\n');
            if (breakAt >= 0) message = message.substring(0, breakAt);
            return Result.fail(message.length() > 180 ? message.substring(0, 180) : message);
        }
    }

    private static Result run(IrisSubset.Shader shader, Draw draw) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (VK.getInstanceVersionSupported() < VK_API_VERSION_1_2)
                throw new Unavailable("Vulkan 1.2 loader is unavailable");
            var instanceInfo = VkInstanceCreateInfo.calloc(stack).sType$Default()
                    .pApplicationInfo(VkApplicationInfo.calloc(stack).sType$Default()
                            .apiVersion(VK_API_VERSION_1_2).pApplicationName(stack.UTF8("AFV shadow mesh")));
            var pointer = stack.mallocPointer(1);
            int created = vkCreateInstance(instanceInfo, null, pointer);
            if (created != VK_SUCCESS) throw new Unavailable("vkCreateInstance failed: " + created);
            var instance = new VkInstance(pointer.get(0), instanceInfo);
            long deviceHandle = 0;
            VkDevice device = null;
            long sampler = 0;
            long descriptorLayout = 0;
            long pipelineLayout = 0;
            long renderPass = 0;
            long uniform = 0;
            long uniformMemory = 0;
            long vertices = 0;
            long vertexMemory = 0;
            long pool = 0;
            Image texture = null;
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
                    if (!preferred.isBlank() && !name.toLowerCase(Locale.ROOT).contains(preferred.toLowerCase(Locale.ROOT)))
                        continue;
                    int queue = graphicsFamily(candidate, stack);
                    if (queue < 0 || !formatSupported(candidate, stack)) continue;
                    physical = candidate;
                    deviceName = name;
                    family = queue;
                    if (!preferred.isBlank() || name.toLowerCase(Locale.ROOT).contains("llvmpipe")) break;
                }
                if (physical == null) throw new Unavailable("No Vulkan device can draw a shadow mesh");
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
                sampler = sampler(device, stack);
                descriptorLayout = descriptorLayout(device, stack);
                pipelineLayout = pipelineLayout(device, stack, descriptorLayout);
                renderPass = renderPass(device, stack);
                uniform = buffer(device, stack, UNIFORM_BYTES, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
                uniformMemory = bind(device, stack, memory, uniform, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
                writeUniforms(device, uniformMemory, draw.modelView(), flippedProjection(draw.projection()));
                long vertexBytes = (long) draw.vertices().length * 4;
                vertices = buffer(device, stack, vertexBytes, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);
                vertexMemory = bind(device, stack, memory, vertices, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
                writeVertices(device, vertexMemory, draw.vertices());
                texture = Image.color(device, memory, stack, draw.textureWidth(), draw.textureHeight(),
                        VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT);
                pool = commandPool(device, stack, family);
                upload(device, queue, memory, pool, texture, draw.texture(), draw.textureWidth(), draw.textureHeight());
                byte[] rgba = draw(device, queue, memory, pool, sampler, descriptorLayout, pipelineLayout, renderPass,
                        uniform, vertices, texture, shader, draw);
                return new Result(false, rgba, "device=" + deviceName + ", vertices=" + (draw.vertices().length / VERTEX_FLOATS)
                        + ", size=" + draw.size());
            } finally {
                if (device != null) vkDeviceWaitIdle(device);
                if (pool != 0) vkDestroyCommandPool(device, pool, null);
                if (texture != null) texture.close(device);
                if (vertexMemory != 0) vkFreeMemory(device, vertexMemory, null);
                if (vertices != 0) vkDestroyBuffer(device, vertices, null);
                if (uniformMemory != 0) vkFreeMemory(device, uniformMemory, null);
                if (uniform != 0) vkDestroyBuffer(device, uniform, null);
                if (renderPass != 0) vkDestroyRenderPass(device, renderPass, null);
                if (pipelineLayout != 0) vkDestroyPipelineLayout(device, pipelineLayout, null);
                if (descriptorLayout != 0) vkDestroyDescriptorSetLayout(device, descriptorLayout, null);
                if (sampler != 0) vkDestroySampler(device, sampler, null);
                if (deviceHandle != 0) vkDestroyDevice(device, null);
                vkDestroyInstance(instance, null);
            }
        }
    }

    private static float[] flippedProjection(float[] projection) {
        float[] flipped = projection.clone();
        flipped[1] = -flipped[1];
        flipped[5] = -flipped[5];
        flipped[9] = -flipped[9];
        flipped[13] = -flipped[13];
        return flipped;
    }

    private static byte[] draw(VkDevice device, VkQueue queue, VkPhysicalDeviceMemoryProperties memory, long pool,
                               long sampler, long descriptorLayout, long pipelineLayout, long renderPass,
                               long uniform, long vertices, Image texture, IrisSubset.Shader shader, Draw draw) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long pipeline = 0;
            long framebuffer = 0;
            long readbackMemory = 0;
            long readback = 0;
            long descriptorPool = 0;
            Image target = null;
            Image depth = null;
            try {
                pipeline = pipeline(device, stack, pipelineLayout, renderPass, shader.vertex(), shader.fragment());
                if (pipeline == 0) throw new IllegalStateException("pipeline creation failed");
                int size = draw.size();
                target = Image.color(device, memory, stack, size, size, VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
                depth = Image.depth(device, memory, stack, size);
                readback = buffer(device, stack, (long) size * size * 4, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
                readbackMemory = bind(device, stack, memory, readback, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
                descriptorPool = descriptorPool(device, stack);
                long set = allocateSet(device, stack, descriptorPool, descriptorLayout);
                writeSet(device, stack, set, uniform, texture.view, sampler);
                framebuffer = framebuffer(device, stack, renderPass, target.view, depth.view, size);
                long command = command(device, stack, pool);
                var buffer = new VkCommandBuffer(command, device);
                check(vkBeginCommandBuffer(buffer, VkCommandBufferBeginInfo.calloc(stack).sType$Default()), "begin mesh");
                barrier(buffer, stack, target.handle, VK_IMAGE_ASPECT_COLOR_BIT, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                        0, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT);
                barrier(buffer, stack, depth.handle, VK_IMAGE_ASPECT_DEPTH_BIT, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,
                        0, VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT);
                var clear = VkClearValue.calloc(2, stack);
                clear.get(0).color().float32(0, draw.clearRed() / 255f).float32(1, draw.clearGreen() / 255f)
                        .float32(2, draw.clearBlue() / 255f).float32(3, draw.clearAlpha() / 255f);
                clear.get(1).depthStencil().depth(1f);
                vkCmdBeginRenderPass(buffer, VkRenderPassBeginInfo.calloc(stack).sType$Default().renderPass(renderPass)
                        .framebuffer(framebuffer).renderArea(area -> area.extent().set(size, size)).pClearValues(clear), VK_SUBPASS_CONTENTS_INLINE);
                vkCmdBindPipeline(buffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
                vkCmdBindDescriptorSets(buffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, 0, stack.longs(set), null);
                vkCmdBindVertexBuffers(buffer, 0, stack.longs(vertices), stack.longs(0));
                vkCmdSetViewport(buffer, 0, VkViewport.calloc(1, stack).x(0).y(0).width(size).height(size).minDepth(0).maxDepth(1));
                vkCmdSetScissor(buffer, 0, VkRect2D.calloc(1, stack).extent(extent -> extent.set(size, size)));
                vkCmdDraw(buffer, draw.vertices().length / VERTEX_FLOATS, 1, 0, 0);
                vkCmdEndRenderPass(buffer);
                barrier(buffer, stack, target.handle, VK_IMAGE_ASPECT_COLOR_BIT, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT,
                        VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
                var region = VkBufferImageCopy.calloc(1, stack);
                region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                region.imageExtent().set(size, size, 1);
                vkCmdCopyImageToBuffer(buffer, target.handle, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, readback, region);
                check(vkEndCommandBuffer(buffer), "end mesh");
                submit(device, queue, stack, command);
                return flipRows(read(device, readbackMemory, size), size);
            } finally {
                if (framebuffer != 0) vkDestroyFramebuffer(device, framebuffer, null);
                if (descriptorPool != 0) vkDestroyDescriptorPool(device, descriptorPool, null);
                if (readbackMemory != 0) vkFreeMemory(device, readbackMemory, null);
                if (readback != 0) vkDestroyBuffer(device, readback, null);
                if (depth != null) depth.close(device);
                if (target != null) target.close(device);
                if (pipeline != 0) vkDestroyPipeline(device, pipeline, null);
            }
        }
    }

    private static byte[] read(VkDevice device, long memory, int size) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var mapped = stack.mallocPointer(1);
            check(vkMapMemory(device, memory, 0, (long) size * size * 4, 0, mapped), "map readback");
            try {
                ByteBuffer pixels = MemoryUtil.memByteBuffer(mapped.get(0), size * size * 4);
                byte[] rgba = new byte[size * size * 4];
                pixels.get(rgba);
                return rgba;
            } finally {
                vkUnmapMemory(device, memory);
            }
        }
    }

    /** Vulkan's first row is the top. glGetTexImage's first row is the bottom. */
    private static byte[] flipRows(byte[] rgba, int size) {
        int stride = size * 4;
        byte[] flipped = new byte[rgba.length];
        for (int y = 0; y < size; y++)
            System.arraycopy(rgba, y * stride, flipped, (size - 1 - y) * stride, stride);
        return flipped;
    }

    private static void upload(VkDevice device, VkQueue queue, VkPhysicalDeviceMemoryProperties memory, long pool,
                               Image texture, byte[] rgba, int width, int height) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long staging = buffer(device, stack, rgba.length, VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
            long stagingMemory = bind(device, stack, memory, staging, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
            try {
                var mapped = stack.mallocPointer(1);
                check(vkMapMemory(device, stagingMemory, 0, rgba.length, 0, mapped), "map texture");
                MemoryUtil.memByteBuffer(mapped.get(0), rgba.length).put(rgba);
                vkUnmapMemory(device, stagingMemory);
                long command = command(device, stack, pool);
                var buffer = new VkCommandBuffer(command, device);
                check(vkBeginCommandBuffer(buffer, VkCommandBufferBeginInfo.calloc(stack).sType$Default()), "begin upload");
                barrier(buffer, stack, texture.handle, VK_IMAGE_ASPECT_COLOR_BIT, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        0, VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
                var region = VkBufferImageCopy.calloc(1, stack);
                region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                region.imageExtent().set(width, height, 1);
                vkCmdCopyBufferToImage(buffer, staging, texture.handle, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
                barrier(buffer, stack, texture.handle, VK_IMAGE_ASPECT_COLOR_BIT, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                        VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT);
                check(vkEndCommandBuffer(buffer), "end upload");
                submit(device, queue, stack, command);
            } finally {
                vkDestroyBuffer(device, staging, null);
                vkFreeMemory(device, stagingMemory, null);
            }
        }
    }

    private static long pipeline(VkDevice device, MemoryStack stack, long layout, long renderPass, byte[] vertex, byte[] fragment) {
        long vertexModule = 0;
        long fragmentModule = 0;
        try {
            vertexModule = shader(device, stack, vertex);
            fragmentModule = shader(device, stack, fragment);
            var stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            stages.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(vertexModule).pName(stack.UTF8("main"));
            stages.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fragmentModule).pName(stack.UTF8("main"));
            var bindings = VkVertexInputBindingDescription.calloc(1, stack);
            bindings.get(0).binding(0).stride(VERTEX_FLOATS * 4).inputRate(VK_VERTEX_INPUT_RATE_VERTEX);
            var attributes = VkVertexInputAttributeDescription.calloc(3, stack);
            attributes.get(0).location(0).binding(0).format(VK_FORMAT_R32G32B32_SFLOAT).offset(0);
            attributes.get(1).location(1).binding(0).format(VK_FORMAT_R32G32_SFLOAT).offset(12);
            attributes.get(2).location(2).binding(0).format(VK_FORMAT_R32G32B32A32_SFLOAT).offset(20);
            var blend = VkPipelineColorBlendAttachmentState.calloc(1, stack);
            blend.get(0).colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT);
            var info = VkGraphicsPipelineCreateInfo.calloc(1, stack);
            info.get(0).sType$Default().pStages(stages).renderPass(renderPass).layout(layout)
                    .pVertexInputState(VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default()
                            .pVertexBindingDescriptions(bindings).pVertexAttributeDescriptions(attributes))
                    .pInputAssemblyState(VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType$Default().topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST))
                    .pViewportState(VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default().viewportCount(1).scissorCount(1))
                    .pRasterizationState(VkPipelineRasterizationStateCreateInfo.calloc(stack).sType$Default()
                            // Iris disables face culling for shadow terrain, so both windings are drawn.
                            .polygonMode(VK_POLYGON_MODE_FILL).cullMode(VK_CULL_MODE_NONE)
                            .frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE).lineWidth(1))
                    .pMultisampleState(VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default().rasterizationSamples(VK_SAMPLE_COUNT_1_BIT))
                    .pDepthStencilState(VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType$Default()
                            .depthTestEnable(true).depthWriteEnable(true).depthCompareOp(VK_COMPARE_OP_LESS_OR_EQUAL))
                    .pColorBlendState(VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default().pAttachments(blend))
                    .pDynamicState(VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default()
                            .pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR)));
            var handle = MemoryUtil.memAllocLong(1);
            try {
                int code = vkCreateGraphicsPipelines(device, VK_NULL_HANDLE, info, null, handle);
                if (code != VK_SUCCESS) return 0;
                return handle.get(0);
            } finally {
                MemoryUtil.memFree(handle);
            }
        } finally {
            if (vertexModule != 0) vkDestroyShaderModule(device, vertexModule, null);
            if (fragmentModule != 0) vkDestroyShaderModule(device, fragmentModule, null);
        }
    }

    private static long shader(VkDevice device, MemoryStack stack, byte[] words) {
        ByteBuffer code = MemoryUtil.memAlloc(words.length);
        try {
            code.put(words).flip();
            var handle = stack.mallocLong(1);
            check(vkCreateShaderModule(device, VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code), null, handle), "shader");
            return handle.get(0);
        } finally {
            MemoryUtil.memFree(code);
        }
    }

    private static void writeUniforms(VkDevice device, long memory, float[] modelView, float[] projection) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var mapped = stack.mallocPointer(1);
            check(vkMapMemory(device, memory, 0, UNIFORM_BYTES, 0, mapped), "map uniforms");
            ByteBuffer bytes = MemoryUtil.memByteBuffer(mapped.get(0), UNIFORM_BYTES);
            for (float value : modelView) bytes.putFloat(value);
            for (float value : projection) bytes.putFloat(value);
            for (int column = 0; column < 4; column++)
                for (int row = 0; row < 4; row++) bytes.putFloat(column == row ? 1f : 0f);
            vkUnmapMemory(device, memory);
        }
    }

    private static void writeVertices(VkDevice device, long memory, float[] vertices) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long bytes = (long) vertices.length * 4;
            var mapped = stack.mallocPointer(1);
            check(vkMapMemory(device, memory, 0, bytes, 0, mapped), "map vertices");
            ByteBuffer buffer = MemoryUtil.memByteBuffer(mapped.get(0), (int) bytes);
            for (float value : vertices) buffer.putFloat(value);
            vkUnmapMemory(device, memory);
        }
    }

    private static void barrier(VkCommandBuffer command, MemoryStack stack, long image, int aspect, int from, int to,
                                int srcAccess, int dstAccess, int srcStage, int dstStage) {
        var barrier = VkImageMemoryBarrier.calloc(1, stack).sType$Default().image(image)
                .oldLayout(from).newLayout(to).srcAccessMask(srcAccess).dstAccessMask(dstAccess)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
        barrier.subresourceRange().aspectMask(aspect).levelCount(1).layerCount(1);
        vkCmdPipelineBarrier(command, srcStage, dstStage, 0, null, null, barrier);
    }

    private static void submit(VkDevice device, VkQueue queue, MemoryStack stack, long command) {
        var fence = stack.mallocLong(1);
        check(vkCreateFence(device, VkFenceCreateInfo.calloc(stack).sType$Default(), null, fence), "fence");
        try {
            check(vkQueueSubmit(queue, VkSubmitInfo.calloc(stack).sType$Default().pCommandBuffers(stack.pointers(command)), fence.get(0)), "submit");
            check(vkWaitForFences(device, fence, true, 10_000_000_000L), "wait");
        } finally {
            vkDestroyFence(device, fence.get(0), null);
        }
    }

    private static int graphicsFamily(VkPhysicalDevice device, MemoryStack stack) {
        var count = stack.ints(0);
        vkGetPhysicalDeviceQueueFamilyProperties(device, count, null);
        var queues = VkQueueFamilyProperties.malloc(count.get(0), stack);
        vkGetPhysicalDeviceQueueFamilyProperties(device, count, queues);
        for (int index = 0; index < queues.capacity(); index++)
            if (queues.get(index).queueCount() > 0 && (queues.get(index).queueFlags() & VK_QUEUE_GRAPHICS_BIT) != 0) return index;
        return -1;
    }

    private static boolean formatSupported(VkPhysicalDevice device, MemoryStack stack) {
        var color = VkFormatProperties.malloc(stack);
        vkGetPhysicalDeviceFormatProperties(device, FORMAT, color);
        int colorFeatures = VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT | VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT
                | VK_FORMAT_FEATURE_TRANSFER_SRC_BIT | VK_FORMAT_FEATURE_TRANSFER_DST_BIT;
        var depth = VkFormatProperties.malloc(stack);
        vkGetPhysicalDeviceFormatProperties(device, DEPTH_FORMAT, depth);
        return (color.optimalTilingFeatures() & colorFeatures) == colorFeatures
                && (depth.optimalTilingFeatures() & VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT) != 0;
    }

    private static long buffer(VkDevice device, MemoryStack stack, long size, int usage) {
        var handle = stack.mallocLong(1);
        check(vkCreateBuffer(device, VkBufferCreateInfo.calloc(stack).sType$Default().size(size).usage(usage)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE), null, handle), "create buffer");
        return handle.get(0);
    }

    private static long bind(VkDevice device, MemoryStack stack, VkPhysicalDeviceMemoryProperties memory, long buffer, int flags) {
        var requirements = VkMemoryRequirements.malloc(stack);
        vkGetBufferMemoryRequirements(device, buffer, requirements);
        int type = memoryType(memory, requirements.memoryTypeBits(), flags);
        if (type < 0) throw new Unavailable("No memory for a shadow mesh buffer");
        long allocated = allocate(device, stack, requirements.size(), type);
        check(vkBindBufferMemory(device, buffer, allocated, 0), "bind buffer");
        return allocated;
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

    private static long sampler(VkDevice device, MemoryStack stack) {
        var handle = stack.mallocLong(1);
        check(vkCreateSampler(device, VkSamplerCreateInfo.calloc(stack).sType$Default()
                .magFilter(VK_FILTER_NEAREST).minFilter(VK_FILTER_NEAREST)
                .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).minLod(0).maxLod(0), null, handle), "sampler");
        return handle.get(0);
    }

    private static long descriptorLayout(VkDevice device, MemoryStack stack) {
        var bindings = VkDescriptorSetLayoutBinding.calloc(2, stack);
        bindings.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1)
                .stageFlags(VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT);
        bindings.get(1).binding(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1)
                .stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
        var handle = stack.mallocLong(1);
        check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings), null, handle), "descriptor layout");
        return handle.get(0);
    }

    private static long pipelineLayout(VkDevice device, MemoryStack stack, long descriptorLayout) {
        var handle = stack.mallocLong(1);
        check(vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                .pSetLayouts(stack.longs(descriptorLayout)), null, handle), "pipeline layout");
        return handle.get(0);
    }

    private static long renderPass(VkDevice device, MemoryStack stack) {
        var attachments = VkAttachmentDescription.calloc(2, stack);
        attachments.get(0).format(FORMAT).samples(VK_SAMPLE_COUNT_1_BIT)
                .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                .initialLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL).finalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        attachments.get(1).format(DEPTH_FORMAT).samples(VK_SAMPLE_COUNT_1_BIT)
                .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                .initialLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL).finalLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
        var color = VkAttachmentReference.calloc(1, stack);
        color.get(0).attachment(0).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        var depth = VkAttachmentReference.calloc(stack).attachment(1).layout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
        var subpass = VkSubpassDescription.calloc(1, stack);
        subpass.get(0).pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS).colorAttachmentCount(1).pColorAttachments(color).pDepthStencilAttachment(depth);
        var dependency = VkSubpassDependency.calloc(1, stack);
        dependency.get(0).srcSubpass(VK_SUBPASS_EXTERNAL).dstSubpass(0)
                .srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT)
                .dstStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT)
                .dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);
        var handle = stack.mallocLong(1);
        check(vkCreateRenderPass(device, VkRenderPassCreateInfo.calloc(stack).sType$Default()
                .pAttachments(attachments).pSubpasses(subpass).pDependencies(dependency), null, handle), "render pass");
        return handle.get(0);
    }

    private static long framebuffer(VkDevice device, MemoryStack stack, long renderPass, long color, long depth, int size) {
        var handle = stack.mallocLong(1);
        check(vkCreateFramebuffer(device, VkFramebufferCreateInfo.calloc(stack).sType$Default().renderPass(renderPass)
                .pAttachments(stack.longs(color, depth)).width(size).height(size).layers(1), null, handle), "framebuffer");
        return handle.get(0);
    }

    private static long descriptorPool(VkDevice device, MemoryStack stack) {
        var sizes = VkDescriptorPoolSize.calloc(2, stack);
        sizes.get(0).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1);
        sizes.get(1).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1);
        var handle = stack.mallocLong(1);
        check(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                .maxSets(1).pPoolSizes(sizes), null, handle), "descriptor pool");
        return handle.get(0);
    }

    private static long allocateSet(VkDevice device, MemoryStack stack, long pool, long layout) {
        var handle = stack.mallocLong(1);
        check(vkAllocateDescriptorSets(device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                .descriptorPool(pool).pSetLayouts(stack.longs(layout)), handle), "descriptor set");
        return handle.get(0);
    }

    private static void writeSet(VkDevice device, MemoryStack stack, long set, long buffer, long view, long sampler) {
        var bufferInfo = VkDescriptorBufferInfo.calloc(1, stack).buffer(buffer).offset(0).range(192);
        var imageInfo = VkDescriptorImageInfo.calloc(1, stack).sampler(sampler).imageView(view)
                .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        var writes = VkWriteDescriptorSet.calloc(2, stack);
        writes.get(0).sType$Default().dstSet(set).dstBinding(0).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                .descriptorCount(1).pBufferInfo(bufferInfo);
        writes.get(1).sType$Default().dstSet(set).dstBinding(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                .descriptorCount(1).pImageInfo(imageInfo);
        vkUpdateDescriptorSets(device, writes, null);
    }

    private static long commandPool(VkDevice device, MemoryStack stack, int family) {
        var handle = stack.mallocLong(1);
        check(vkCreateCommandPool(device, VkCommandPoolCreateInfo.calloc(stack).sType$Default().queueFamilyIndex(family), null, handle), "command pool");
        return handle.get(0);
    }

    private static long command(VkDevice device, MemoryStack stack, long pool) {
        var handle = stack.mallocPointer(1);
        check(vkAllocateCommandBuffers(device, VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                .commandPool(pool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1), handle), "command");
        return handle.get(0);
    }

    private static void check(int result, String operation) {
        if (result != VK_SUCCESS) throw new IllegalStateException(operation + " failed: " + result);
    }

    private static final class Image {
        final long handle;
        final long memory;
        final long view;

        private Image(long handle, long memory, long view) {
            this.handle = handle;
            this.memory = memory;
            this.view = view;
        }

        static Image color(VkDevice device, VkPhysicalDeviceMemoryProperties memory, MemoryStack stack, int width, int height, int usage) {
            return create(device, memory, stack, width, height, FORMAT, usage, VK_IMAGE_ASPECT_COLOR_BIT);
        }

        static Image depth(VkDevice device, VkPhysicalDeviceMemoryProperties memory, MemoryStack stack, int size) {
            return create(device, memory, stack, size, size, DEPTH_FORMAT, VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT, VK_IMAGE_ASPECT_DEPTH_BIT);
        }

        private static Image create(VkDevice device, VkPhysicalDeviceMemoryProperties memory, MemoryStack stack,
                                    int width, int height, int format, int usage, int aspect) {
            var handle = stack.mallocLong(1);
            var info = VkImageCreateInfo.calloc(stack).sType$Default().imageType(VK_IMAGE_TYPE_2D).format(format)
                    .mipLevels(1).arrayLayers(1).samples(VK_SAMPLE_COUNT_1_BIT).tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(usage).sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            info.extent().set(width, height, 1);
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
            var viewInfo = VkImageViewCreateInfo.calloc(stack).sType$Default().image(image).viewType(VK_IMAGE_VIEW_TYPE_2D).format(format);
            viewInfo.subresourceRange().aspectMask(aspect).levelCount(1).layerCount(1);
            check(vkCreateImageView(device, viewInfo, null, view), "image view");
            return new Image(image, allocated, view.get(0));
        }

        void close(VkDevice device) {
            if (view != 0) vkDestroyImageView(device, view, null);
            if (handle != 0) vkDestroyImage(device, handle, null);
            if (memory != 0) vkFreeMemory(device, memory, null);
        }
    }

    private static final class Unavailable extends RuntimeException {
        Unavailable(String message) { super(message); }
    }
}
