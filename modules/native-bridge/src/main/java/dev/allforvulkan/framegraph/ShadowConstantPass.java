package dev.allforvulkan.framegraph;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.util.Locale;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.VK_FORMAT_FEATURE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK12.VK_API_VERSION_1_2;

/**
 * One cached RGBA8 image of AFV-Shadow's clear color. The device is destroyed after the readback.
 * A later request for the same size returns those pixels and does not create another device.
 */
public final class ShadowConstantPass {
    public static final int RED = 128;
    public static final int GREEN = 64;
    public static final int BLUE = 192;
    public static final int ALPHA = 255;
    private static final int FORMAT = VK_FORMAT_R8G8B8A8_UNORM;
    private static final String VERTEX = """
            #version 450
            void main() {
                vec2 corner = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
                gl_Position = vec4(corner * 2.0 - 1.0, 0.0, 1.0);
            }
            """;
    private static final String FRAGMENT = """
            #version 450
            layout(location = 0) out vec4 color;
            void main() {
                color = vec4(0.5019607843137255, 0.25098039215686274, 0.7529411764705882, 1.0);
            }
            """;

    private static byte[] cached;
    private static int cachedSize;
    private static String detail = "";
    private static String failure;

    public record Result(byte[] rgba, String detail) {}

    private ShadowConstantPass() {}

    public static Result render(int size) {
        synchronized (ShadowConstantPass.class) {
            if (failure != null) return new Result(null, failure);
            if (cached != null && cachedSize == size) return new Result(cached, detail);
            if (size < 1 || size > 4096) return new Result(null, "shadow size " + size + " is outside 1..4096");
            try {
                cached = draw(size);
                cachedSize = size;
                return new Result(cached, detail);
            } catch (RuntimeException error) {
                failure = first(error.getMessage() == null ? error.toString() : error.getMessage());
                return new Result(null, failure);
            }
        }
    }

    private static byte[] draw(int size) {
        GlslCompiler.Output vertex = GlslCompiler.compile("shadow-constant.vert", VERTEX, true);
        GlslCompiler.Output fragment = GlslCompiler.compile("shadow-constant.frag", FRAGMENT, false);
        if (!vertex.ok()) throw new IllegalStateException(vertex.error());
        if (!fragment.ok()) throw new IllegalStateException(fragment.error());
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (VK.getInstanceVersionSupported() < VK_API_VERSION_1_2)
                throw new IllegalStateException("Vulkan 1.2 loader is unavailable");
            var instanceInfo = VkInstanceCreateInfo.calloc(stack).sType$Default()
                    .pApplicationInfo(VkApplicationInfo.calloc(stack).sType$Default()
                            .apiVersion(VK_API_VERSION_1_2).pApplicationName(stack.UTF8("AFV shadow")));
            var pointer = stack.mallocPointer(1);
            int created = vkCreateInstance(instanceInfo, null, pointer);
            if (created != VK_SUCCESS) throw new IllegalStateException("vkCreateInstance failed: " + created);
            var instance = new VkInstance(pointer.get(0), instanceInfo);
            VkDevice device = null;
            long deviceHandle = 0;
            long pipelineLayout = 0;
            long renderPass = 0;
            long pool = 0;
            try {
                var count = stack.ints(0);
                check(vkEnumeratePhysicalDevices(instance, count, null), "enumerate devices");
                if (count.get(0) == 0) throw new IllegalStateException("No Vulkan device");
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
                if (physical == null) throw new IllegalStateException("No Vulkan device can draw R8G8B8A8");
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
                pipelineLayout = createPipelineLayout(device, stack);
                renderPass = createRenderPass(device, stack);
                pool = createCommandPool(device, stack, family);
                byte[] pixels = submit(device, queue, memory, pool, pipelineLayout, renderPass, size,
                        vertex.spirv(), fragment.spirv());
                detail = "device=" + deviceName + ", size=" + size;
                return pixels;
            } finally {
                if (device != null) vkDeviceWaitIdle(device);
                if (pool != 0) vkDestroyCommandPool(device, pool, null);
                if (renderPass != 0) vkDestroyRenderPass(device, renderPass, null);
                if (pipelineLayout != 0) vkDestroyPipelineLayout(device, pipelineLayout, null);
                if (deviceHandle != 0) vkDestroyDevice(device, null);
                vkDestroyInstance(instance, null);
            }
        }
    }

    private static byte[] submit(VkDevice device, VkQueue queue, VkPhysicalDeviceMemoryProperties memory,
                                 long pool, long pipelineLayout, long renderPass, int size,
                                 byte[] vertexWords, byte[] fragmentWords) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long pipeline = 0;
            long framebuffer = 0;
            long readbackMemory = 0;
            long readback = 0;
            GpuImage target = null;
            try {
                pipeline = pipeline(device, stack, pipelineLayout, renderPass, vertexWords, fragmentWords);
                target = GpuImage.create(device, memory, stack, size);
                readback = createBuffer(device, stack, (long) size * size * 4, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
                readbackMemory = bind(device, stack, memory, readback,
                        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
                framebuffer = createFramebuffer(device, stack, renderPass, target.view, size);
                long command = allocateCommand(device, stack, pool);
                var buffer = new VkCommandBuffer(command, device);
                check(vkBeginCommandBuffer(buffer, VkCommandBufferBeginInfo.calloc(stack).sType$Default()), "begin shadow");
                barrier(buffer, stack, target, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                        0, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                        VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT);
                vkCmdBeginRenderPass(buffer, VkRenderPassBeginInfo.calloc(stack).sType$Default().renderPass(renderPass)
                        .framebuffer(framebuffer).renderArea(area -> area.extent().set(size, size)), VK_SUBPASS_CONTENTS_INLINE);
                vkCmdBindPipeline(buffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
                vkCmdSetViewport(buffer, 0, VkViewport.calloc(1, stack).x(0).y(0).width(size).height(size).minDepth(0).maxDepth(1));
                vkCmdSetScissor(buffer, 0, VkRect2D.calloc(1, stack).extent(extent -> extent.set(size, size)));
                vkCmdDraw(buffer, 3, 1, 0, 0);
                vkCmdEndRenderPass(buffer);
                barrier(buffer, stack, target, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT,
                        VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
                var region = VkBufferImageCopy.calloc(1, stack);
                region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                region.imageExtent().set(size, size, 1);
                vkCmdCopyImageToBuffer(buffer, target.handle, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, readback, region);
                check(vkEndCommandBuffer(buffer), "end shadow");
                submitAndWait(device, queue, stack, command);
                return pixels(device, readbackMemory, size);
            } finally {
                if (framebuffer != 0) vkDestroyFramebuffer(device, framebuffer, null);
                if (readbackMemory != 0) vkFreeMemory(device, readbackMemory, null);
                if (readback != 0) vkDestroyBuffer(device, readback, null);
                if (target != null) target.close(device);
                if (pipeline != 0) vkDestroyPipeline(device, pipeline, null);
            }
        }
    }

    private static byte[] pixels(VkDevice device, long memory, int size) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var mapped = stack.mallocPointer(1);
            check(vkMapMemory(device, memory, 0, (long) size * size * 4, 0, mapped), "map shadow");
            try {
                ByteBuffer buffer = MemoryUtil.memByteBuffer(mapped.get(0), size * size * 4);
                byte[] rgba = new byte[size * size * 4];
                buffer.get(rgba);
                for (int i = 0; i < rgba.length; i += 4) {
                    if ((rgba[i] & 255) != RED || (rgba[i + 1] & 255) != GREEN
                            || (rgba[i + 2] & 255) != BLUE || (rgba[i + 3] & 255) != ALPHA)
                        throw new IllegalStateException("pixel " + (i / 4) + " is "
                                + (rgba[i] & 255) + "," + (rgba[i + 1] & 255) + ","
                                + (rgba[i + 2] & 255) + "," + (rgba[i + 3] & 255));
                }
                return rgba;
            } finally {
                vkUnmapMemory(device, memory);
            }
        }
    }

    private static long pipeline(VkDevice device, MemoryStack stack, long layout, long renderPass,
                                 byte[] vertexWords, byte[] fragmentWords) {
        long vertexModule = 0;
        long fragmentModule = 0;
        try {
            vertexModule = shader(device, stack, vertexWords);
            fragmentModule = shader(device, stack, fragmentWords);
            var stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            stages.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(vertexModule).pName(stack.UTF8("main"));
            stages.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fragmentModule).pName(stack.UTF8("main"));
            var blend = VkPipelineColorBlendAttachmentState.calloc(1, stack);
            blend.get(0).colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT
                    | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT);
            var info = VkGraphicsPipelineCreateInfo.calloc(1, stack);
            info.get(0).sType$Default().pStages(stages).renderPass(renderPass).layout(layout)
                    .pVertexInputState(VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default())
                    .pInputAssemblyState(VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType$Default()
                            .topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST))
                    .pViewportState(VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default().viewportCount(1).scissorCount(1))
                    .pRasterizationState(VkPipelineRasterizationStateCreateInfo.calloc(stack).sType$Default()
                            .polygonMode(VK_POLYGON_MODE_FILL).cullMode(VK_CULL_MODE_NONE)
                            .frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE).lineWidth(1))
                    .pMultisampleState(VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default()
                            .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT))
                    .pColorBlendState(VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default().pAttachments(blend))
                    .pDynamicState(VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default()
                            .pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR)));
            var handle = MemoryUtil.memAllocLong(1);
            try {
                check(vkCreateGraphicsPipelines(device, VK_NULL_HANDLE, info, null, handle), "pipeline");
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

    private static void barrier(VkCommandBuffer command, MemoryStack stack, GpuImage image, int from, int to,
                                int srcAccess, int dstAccess, int srcStage, int dstStage) {
        var barrier = VkImageMemoryBarrier.calloc(1, stack).sType$Default().image(image.handle)
                .oldLayout(from).newLayout(to).srcAccessMask(srcAccess).dstAccessMask(dstAccess)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
        barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
        vkCmdPipelineBarrier(command, srcStage, dstStage, 0, null, null, barrier);
    }

    private static void submitAndWait(VkDevice device, VkQueue queue, MemoryStack stack, long command) {
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
            if (queues.get(index).queueCount() > 0 && (queues.get(index).queueFlags() & VK_QUEUE_GRAPHICS_BIT) != 0)
                return index;
        return -1;
    }

    private static boolean formatSupported(VkPhysicalDevice device, MemoryStack stack) {
        var properties = VkFormatProperties.malloc(stack);
        vkGetPhysicalDeviceFormatProperties(device, FORMAT, properties);
        int required = VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT | VK_FORMAT_FEATURE_TRANSFER_SRC_BIT;
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
        if (type < 0) throw new IllegalStateException("No memory for the shadow readback");
        var handle = stack.mallocLong(1);
        check(vkAllocateMemory(device, VkMemoryAllocateInfo.calloc(stack).sType$Default()
                .allocationSize(requirements.size()).memoryTypeIndex(type), null, handle), "allocate");
        check(vkBindBufferMemory(device, buffer, handle.get(0), 0), "bind buffer");
        return handle.get(0);
    }

    private static int memoryType(VkPhysicalDeviceMemoryProperties properties, int bits, int flags) {
        for (int index = 0; index < properties.memoryTypeCount(); index++)
            if ((bits & (1 << index)) != 0 && (properties.memoryTypes(index).propertyFlags() & flags) == flags)
                return index;
        if (flags == 0) for (int index = 0; index < properties.memoryTypeCount(); index++)
            if ((bits & (1 << index)) != 0) return index;
        return -1;
    }

    private static long createPipelineLayout(VkDevice device, MemoryStack stack) {
        var handle = stack.mallocLong(1);
        check(vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default(), null, handle), "pipeline layout");
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
        var handle = stack.mallocLong(1);
        check(vkCreateRenderPass(device, VkRenderPassCreateInfo.calloc(stack).sType$Default()
                .pAttachments(attachment).pSubpasses(subpass), null, handle), "render pass");
        return handle.get(0);
    }

    private static long createFramebuffer(VkDevice device, MemoryStack stack, long renderPass, long view, int size) {
        var handle = stack.mallocLong(1);
        check(vkCreateFramebuffer(device, VkFramebufferCreateInfo.calloc(stack).sType$Default().renderPass(renderPass)
                .pAttachments(stack.longs(view)).width(size).height(size).layers(1), null, handle), "framebuffer");
        return handle.get(0);
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

    private static String first(String message) {
        int breakAt = message.indexOf('\n');
        if (breakAt >= 0) message = message.substring(0, breakAt);
        return message.length() > 180 ? message.substring(0, 180) : message;
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

        static GpuImage create(VkDevice device, VkPhysicalDeviceMemoryProperties memory, MemoryStack stack, int size) {
            var handle = stack.mallocLong(1);
            var info = VkImageCreateInfo.calloc(stack).sType$Default().imageType(VK_IMAGE_TYPE_2D).format(FORMAT)
                    .mipLevels(1).arrayLayers(1).samples(VK_SAMPLE_COUNT_1_BIT).tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            info.extent().set(size, size, 1);
            check(vkCreateImage(device, info, null, handle), "image");
            long image = handle.get(0);
            var requirements = VkMemoryRequirements.malloc(stack);
            vkGetImageMemoryRequirements(device, image, requirements);
            int type = memoryType(memory, requirements.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            if (type < 0) type = memoryType(memory, requirements.memoryTypeBits(), 0);
            if (type < 0) throw new IllegalStateException("No image memory");
            var allocated = stack.mallocLong(1);
            check(vkAllocateMemory(device, VkMemoryAllocateInfo.calloc(stack).sType$Default()
                    .allocationSize(requirements.size()).memoryTypeIndex(type), null, allocated), "image memory");
            check(vkBindImageMemory(device, image, allocated.get(0), 0), "bind image");
            var view = stack.mallocLong(1);
            var viewInfo = VkImageViewCreateInfo.calloc(stack).sType$Default().image(image).viewType(VK_IMAGE_VIEW_TYPE_2D).format(FORMAT);
            viewInfo.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            check(vkCreateImageView(device, viewInfo, null, view), "image view");
            return new GpuImage(image, allocated.get(0), view.get(0));
        }

        void close(VkDevice device) {
            if (view != 0) vkDestroyImageView(device, view, null);
            if (handle != 0) vkDestroyImage(device, handle, null);
            if (memory != 0) vkFreeMemory(device, memory, null);
        }
    }
}
