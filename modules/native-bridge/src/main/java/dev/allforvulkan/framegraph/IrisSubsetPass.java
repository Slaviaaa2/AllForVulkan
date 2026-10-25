package dev.allforvulkan.framegraph;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.*;
import static org.lwjgl.vulkan.VK12.VK_API_VERSION_1_2;

/** Draws compiled shadow and gbuffer programs on a private Vulkan device.
 * One program's failure is recorded and the remaining programs still draw. */
public final class IrisSubsetPass {
    static final int SIZE = 8;
    private static final int FORMAT = VK_FORMAT_R8G8B8A8_UNORM;
    private static final int UNIFORM_BYTES = 256;
    private static final int VERTEX_FLOATS = 9;

    /** Three vertices, nine floats each: position xyz, UV, RGBA tint. The texture is one RGBA texel. */
    public record Mesh(byte[] texture, float[] vertices, int red, int green, int blue, int alpha) {
        public static Mesh smoke() {
            return new Mesh(new byte[] {64, (byte) 128, (byte) 192, (byte) 255}, new float[] {
                    -1f, -1f, 0f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 1f,
                    3f, -1f, 0f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 1f,
                    -1f, 3f, 0f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 1f
            }, 32, 64, 96, 255);
        }

        String invalid() {
            if (texture == null || texture.length != 4) return "texture must be 1x1 RGBA";
            if (vertices == null || vertices.length != VERTEX_FLOATS * 3) return "mesh must be three vertices";
            return null;
        }
    }

    public record Result(boolean skipped, String ran, String fallback, String detail) {
        static Result skip(String detail) { return new Result(true, "", "", detail); }
    }

    private IrisSubsetPass() {}

    public static Result execute(List<IrisSubset.Shader> shaders) {
        return execute(shaders, Mesh.smoke());
    }

    public static Result execute(List<IrisSubset.Shader> shaders, Mesh mesh) {
        String invalid = mesh == null ? "missing mesh" : mesh.invalid();
        if (invalid != null) return new Result(false, "", "mesh (" + invalid + ")", invalid);
        try {
            return run(shaders, mesh);
        } catch (Unavailable unavailable) {
            return Result.skip(unavailable.getMessage());
        }
    }

    private static Result run(List<IrisSubset.Shader> shaders, Mesh mesh) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (VK.getInstanceVersionSupported() < VK_API_VERSION_1_2)
                throw new Unavailable("Vulkan 1.2 loader is unavailable");
            var instanceInfo = VkInstanceCreateInfo.calloc(stack).sType$Default()
                    .pApplicationInfo(VkApplicationInfo.calloc(stack).sType$Default()
                            .apiVersion(VK_API_VERSION_1_2).pApplicationName(stack.UTF8("AFV subset")));
            var pointer = stack.mallocPointer(1);
            check(vkCreateInstance(instanceInfo, null, pointer), "vkCreateInstance");
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
            GpuImage texture = null;
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
                if (physical == null) throw new Unavailable("No Vulkan device can sample and draw R8G8B8A8");
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
                sampler = createSampler(device, stack);
                descriptorLayout = createDescriptorLayout(device, stack);
                pipelineLayout = createPipelineLayout(device, stack, descriptorLayout);
                renderPass = createRenderPass(device, stack);
                uniform = createBuffer(device, stack, UNIFORM_BYTES, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
                uniformMemory = bind(device, stack, memory, uniform, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
                writeUniforms(device, uniformMemory);
                vertices = createBuffer(device, stack, 3L * VERTEX_FLOATS * 4, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);
                vertexMemory = bind(device, stack, memory, vertices, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
                writeVertices(device, vertexMemory, mesh.vertices());
                texture = GpuImage.create(device, memory, stack, 1, VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT);
                pool = createCommandPool(device, stack, family);
                uploadTexture(device, queue, memory, pool, texture, mesh.texture());
                var ran = new ArrayList<String>();
                var fallback = new ArrayList<String>();
                for (var shader : shaders) {
                    String failure = draw(device, queue, memory, pool, sampler, descriptorLayout, pipelineLayout,
                            renderPass, uniform, vertices, texture, shader, mesh);
                    if (failure == null) ran.add(shader.name());
                    else fallback.add(shader.name() + " (" + failure + ")");
                }
                String detail = "device=" + deviceName + ", ran=" + String.join(",", ran)
                        + ", fallback=" + String.join("; ", fallback);
                return new Result(false, String.join(",", ran), String.join("; ", fallback), detail);
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

    /** Null when the program drew texture 64,128,192,255 times tint 0.5. */
    private static String draw(VkDevice device, VkQueue queue, VkPhysicalDeviceMemoryProperties memory,
                               long pool, long sampler, long descriptorLayout, long pipelineLayout, long renderPass,
                               long uniform, long vertices, GpuImage texture, IrisSubset.Shader shader, Mesh mesh) {
        if (shader.vertex() == null || shader.vertex().length < 20 || shader.fragment() == null || shader.fragment().length < 20)
            return "empty SPIR-V";
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long pipeline = 0;
            long framebuffer = 0;
            long readbackMemory = 0;
            long readback = 0;
            long descriptorPool = 0;
            GpuImage target = null;
            try {
                pipeline = pipeline(device, stack, pipelineLayout, renderPass, shader.vertex(), shader.fragment());
                if (pipeline == 0) return "pipeline creation failed";
                target = GpuImage.create(device, memory, stack, SIZE,
                        VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
                readback = createBuffer(device, stack, SIZE * SIZE * 4L, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
                readbackMemory = bind(device, stack, memory, readback, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
                descriptorPool = createDescriptorPool(device, stack);
                long set = allocateSet(device, stack, descriptorPool, descriptorLayout);
                writeSet(device, stack, set, uniform, texture.view, sampler);
                framebuffer = createFramebuffer(device, stack, renderPass, target.view);
                long command = allocateCommand(device, stack, pool);
                var buffer = new VkCommandBuffer(command, device);
                check(vkBeginCommandBuffer(buffer, VkCommandBufferBeginInfo.calloc(stack).sType$Default()), "begin subset");
                barrier(buffer, stack, target, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                        0, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT);
                vkCmdBeginRenderPass(buffer, VkRenderPassBeginInfo.calloc(stack).sType$Default().renderPass(renderPass)
                        .framebuffer(framebuffer).renderArea(area -> area.extent().set(SIZE, SIZE)), VK_SUBPASS_CONTENTS_INLINE);
                vkCmdBindPipeline(buffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
                vkCmdBindDescriptorSets(buffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, 0, stack.longs(set), null);
                vkCmdBindVertexBuffers(buffer, 0, stack.longs(vertices), stack.longs(0));
                vkCmdSetViewport(buffer, 0, VkViewport.calloc(1, stack).x(0).y(0).width(SIZE).height(SIZE).minDepth(0).maxDepth(1));
                vkCmdSetScissor(buffer, 0, VkRect2D.calloc(1, stack).extent(extent -> extent.set(SIZE, SIZE)));
                vkCmdDraw(buffer, 3, 1, 0, 0);
                vkCmdEndRenderPass(buffer);
                barrier(buffer, stack, target, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT,
                        VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
                var region = VkBufferImageCopy.calloc(1, stack);
                region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                region.imageExtent().set(SIZE, SIZE, 1);
                vkCmdCopyImageToBuffer(buffer, target.handle, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, readback, region);
                check(vkEndCommandBuffer(buffer), "end subset");
                submit(device, queue, stack, command);
                return pixels(device, readbackMemory, mesh);
            } catch (RuntimeException failure) {
                String message = failure.getMessage() == null ? failure.toString() : failure.getMessage();
                int breakAt = message.indexOf('\n');
                if (breakAt >= 0) message = message.substring(0, breakAt);
                return message.length() > 180 ? message.substring(0, 180) : message;
            } finally {
                if (framebuffer != 0) vkDestroyFramebuffer(device, framebuffer, null);
                if (descriptorPool != 0) vkDestroyDescriptorPool(device, descriptorPool, null);
                if (readbackMemory != 0) vkFreeMemory(device, readbackMemory, null);
                if (readback != 0) vkDestroyBuffer(device, readback, null);
                if (target != null) target.close(device);
                if (pipeline != 0) vkDestroyPipeline(device, pipeline, null);
            }
        }
    }

    private static void uploadTexture(VkDevice device, VkQueue queue, VkPhysicalDeviceMemoryProperties memory,
                                      long pool, GpuImage texture, byte[] texel) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long staging = createBuffer(device, stack, 4, VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
            long stagingMemory = bind(device, stack, memory, staging, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
            try {
                var mapped = stack.mallocPointer(1);
                check(vkMapMemory(device, stagingMemory, 0, 4, 0, mapped), "map texture");
                MemoryUtil.memByteBuffer(mapped.get(0), 4).put(texel);
                vkUnmapMemory(device, stagingMemory);
                long command = allocateCommand(device, stack, pool);
                var buffer = new VkCommandBuffer(command, device);
                check(vkBeginCommandBuffer(buffer, VkCommandBufferBeginInfo.calloc(stack).sType$Default()), "begin upload");
                barrier(buffer, stack, texture, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        0, VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
                var region = VkBufferImageCopy.calloc(1, stack);
                region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                region.imageExtent().set(1, 1, 1);
                vkCmdCopyBufferToImage(buffer, staging, texture.handle, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
                barrier(buffer, stack, texture, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
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
                            .polygonMode(VK_POLYGON_MODE_FILL).cullMode(VK_CULL_MODE_NONE).frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE).lineWidth(1))
                    .pMultisampleState(VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default().rasterizationSamples(VK_SAMPLE_COUNT_1_BIT))
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

    private static void writeUniforms(VkDevice device, long memory) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var mapped = stack.mallocPointer(1);
            check(vkMapMemory(device, memory, 0, UNIFORM_BYTES, 0, mapped), "map uniforms");
            ByteBuffer bytes = MemoryUtil.memByteBuffer(mapped.get(0), UNIFORM_BYTES);
            for (int matrix = 0; matrix < 3; matrix++) {
                for (int column = 0; column < 4; column++) {
                    for (int row = 0; row < 4; row++) bytes.putFloat(column == row ? 1f : 0f);
                }
            }
            vkUnmapMemory(device, memory);
        }
    }

    private static void writeVertices(VkDevice device, long memory, float[] vertices) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var mapped = stack.mallocPointer(1);
            check(vkMapMemory(device, memory, 0, 3L * VERTEX_FLOATS * 4, 0, mapped), "map vertices");
            ByteBuffer bytes = MemoryUtil.memByteBuffer(mapped.get(0), 3 * VERTEX_FLOATS * 4);
            for (float value : vertices) bytes.putFloat(value);
            vkUnmapMemory(device, memory);
        }
    }

    private static String pixels(VkDevice device, long memory, Mesh mesh) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var mapped = stack.mallocPointer(1);
            check(vkMapMemory(device, memory, 0, SIZE * SIZE * 4L, 0, mapped), "map readback");
            try {
                ByteBuffer pixels = MemoryUtil.memByteBuffer(mapped.get(0), SIZE * SIZE * 4);
                for (int i = 0; i < SIZE * SIZE; i++) {
                    int r = pixels.get(i * 4) & 0xff;
                    int g = pixels.get(i * 4 + 1) & 0xff;
                    int b = pixels.get(i * 4 + 2) & 0xff;
                    int a = pixels.get(i * 4 + 3) & 0xff;
                    if (r != mesh.red() || g != mesh.green() || b != mesh.blue() || a != mesh.alpha())
                        return "pixel " + i + " is " + r + "," + g + "," + b + "," + a;
                }
                return null;
            } finally {
                vkUnmapMemory(device, memory);
            }
        }
    }

    private static void barrier(VkCommandBuffer command, MemoryStack stack, GpuImage image, int from, int to,
                                int srcAccess, int dstAccess, int srcStage, int dstStage) {
        var barrier = VkImageMemoryBarrier.calloc(1, stack).sType$Default().image(image.handle)
                .oldLayout(from).newLayout(to).srcAccessMask(srcAccess).dstAccessMask(dstAccess)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
        barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
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
        var properties = VkFormatProperties.malloc(stack);
        vkGetPhysicalDeviceFormatProperties(device, FORMAT, properties);
        int required = VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT | VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT
                | VK_FORMAT_FEATURE_TRANSFER_SRC_BIT | VK_FORMAT_FEATURE_TRANSFER_DST_BIT;
        return (properties.optimalTilingFeatures() & required) == required;
    }

    private static long createBuffer(VkDevice device, MemoryStack stack, long size, int usage) {
        var handle = stack.mallocLong(1);
        check(vkCreateBuffer(device, VkBufferCreateInfo.calloc(stack).sType$Default().size(size).usage(usage)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE), null, handle), "create buffer");
        return handle.get(0);
    }

    private static long bind(VkDevice device, MemoryStack stack, VkPhysicalDeviceMemoryProperties memory, long buffer, int flags) {
        var requirements = VkMemoryRequirements.malloc(stack);
        vkGetBufferMemoryRequirements(device, buffer, requirements);
        int type = memoryType(memory, requirements.memoryTypeBits(), flags);
        if (type < 0) throw new Unavailable("No memory for a subset buffer");
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
        bindings.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1)
                .stageFlags(VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT);
        bindings.get(1).binding(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1)
                .stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
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

    private static long createDescriptorPool(VkDevice device, MemoryStack stack) {
        var sizes = VkDescriptorPoolSize.calloc(2, stack);
        sizes.get(0).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(4);
        sizes.get(1).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(4);
        var handle = stack.mallocLong(1);
        check(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                .maxSets(4).pPoolSizes(sizes), null, handle), "descriptor pool");
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

    private static final class GpuImage {
        final long handle;
        final long memory;
        final long view;

        private GpuImage(long handle, long memory, long view) {
            this.handle = handle;
            this.memory = memory;
            this.view = view;
        }

        static GpuImage create(VkDevice device, VkPhysicalDeviceMemoryProperties memory, MemoryStack stack, int size, int usage) {
            var handle = stack.mallocLong(1);
            var info = VkImageCreateInfo.calloc(stack).sType$Default().imageType(VK_IMAGE_TYPE_2D).format(FORMAT)
                    .mipLevels(1).arrayLayers(1).samples(VK_SAMPLE_COUNT_1_BIT).tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(usage).sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            info.extent().set(size, size, 1);
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
    }

    private static final class Unavailable extends RuntimeException {
        Unavailable(String message) { super(message); }
    }
}
