package dev.allforvulkan.bridge;

import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.EXTMemoryObject.*;
import static org.lwjgl.opengl.EXTMemoryObjectFD.*;
import static org.lwjgl.opengl.EXTSemaphore.glGenSemaphoresEXT;
import static org.lwjgl.opengl.EXTSemaphore.glDeleteSemaphoresEXT;
import static org.lwjgl.opengl.EXTSemaphore.glWaitSemaphoreEXT;
import static org.lwjgl.opengl.EXTSemaphore.glSignalSemaphoreEXT;
import static org.lwjgl.opengl.EXTSemaphore.GL_LAYOUT_GENERAL_EXT;
import static org.lwjgl.opengl.EXTSemaphoreFD.glImportSemaphoreFdEXT;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.*;
import static org.lwjgl.vulkan.KHRExternalMemoryFd.*;
import static org.lwjgl.vulkan.KHRExternalSemaphoreFd.*;

/** Exportable image on a borrowed game device. All operations belong to the GL/render thread.
 * The caller must complete GPU work before close; this class never owns/destroys the device. */
public final class SharedImage implements AutoCloseable {
    private final Thread owner = Thread.currentThread();
    private final VkDevice device;
    private final int width, height, aspect;
    private long image, memory, toGl, fromGl;
    private int glMemory, glToGl, glFromGl, texture, framebuffer;
    private boolean closed;
    private int layout = VK_IMAGE_LAYOUT_UNDEFINED;

    public SharedImage(VkPhysicalDevice physical, VkDevice device, int width, int height) {
        this(physical, device, width, height, VK_FORMAT_R8G8B8A8_UNORM);
    }
    public SharedImage(VkPhysicalDevice physical, VkDevice device, int width, int height, int format) {
        int glFormat = switch (format) {
            case VK_FORMAT_R8G8B8A8_UNORM -> GL_RGBA8;
            case VK_FORMAT_R8G8B8A8_SRGB -> org.lwjgl.opengl.GL21.GL_SRGB8_ALPHA8;
            case VK_FORMAT_D16_UNORM -> org.lwjgl.opengl.GL14.GL_DEPTH_COMPONENT16;
            case VK_FORMAT_X8_D24_UNORM_PACK32 -> org.lwjgl.opengl.GL14.GL_DEPTH_COMPONENT24;
            case VK_FORMAT_D24_UNORM_S8_UINT -> GL_DEPTH24_STENCIL8;
            case VK_FORMAT_D32_SFLOAT -> GL_DEPTH_COMPONENT32F;
            case VK_FORMAT_D32_SFLOAT_S8_UINT -> GL_DEPTH32F_STENCIL8;
            default -> throw new IllegalArgumentException("Unsupported shared format " + format);
        };
        aspect = switch (format) {
            case VK_FORMAT_D16_UNORM, VK_FORMAT_X8_D24_UNORM_PACK32, VK_FORMAT_D32_SFLOAT -> VK_IMAGE_ASPECT_DEPTH_BIT;
            case VK_FORMAT_D24_UNORM_S8_UINT, VK_FORMAT_D32_SFLOAT_S8_UINT -> VK_IMAGE_ASPECT_DEPTH_BIT | VK_IMAGE_ASPECT_STENCIL_BIT;
            default -> VK_IMAGE_ASPECT_COLOR_BIT;
        };
        this.device = device;
        this.width = width;
        this.height = height;
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid shared image size");
        var caps = GL.getCapabilities();
        if (!caps.GL_EXT_memory_object_fd || !caps.GL_EXT_semaphore_fd)
            throw new IllegalStateException("Zink lacks opaque FD memory/semaphore sharing");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var id = VkPhysicalDeviceIDProperties.calloc(stack).sType$Default();
            vkGetPhysicalDeviceProperties2(physical, VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(id.address()));
            var uuid = stack.malloc(GL_UUID_SIZE_EXT);
            glGetUnsignedBytei_vEXT(GL_DEVICE_UUID_EXT, 0, uuid);
            for (int i = 0; i < GL_UUID_SIZE_EXT; i++)
                if (uuid.get(i) != id.deviceUUID(i)) throw new IllegalStateException("Game Vulkan device and Zink UUID differ");
            var external = VkPhysicalDeviceExternalImageFormatInfo.calloc(stack).sType$Default().handleType(VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT);
            var properties = VkExternalImageFormatProperties.calloc(stack).sType$Default();
            var output = VkImageFormatProperties2.calloc(stack).sType$Default().pNext(properties.address());
            int usage = (aspect == VK_IMAGE_ASPECT_COLOR_BIT ? VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT : VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT) | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
            check(vkGetPhysicalDeviceImageFormatProperties2(physical, VkPhysicalDeviceImageFormatInfo2.calloc(stack).sType$Default()
                    .pNext(external.address()).format(format).type(VK_IMAGE_TYPE_2D)
                    .tiling(VK_IMAGE_TILING_OPTIMAL).usage(usage), output), "shared image format support " + format);
            if ((properties.externalMemoryProperties().externalMemoryFeatures() & VK_EXTERNAL_MEMORY_FEATURE_EXPORTABLE_BIT) == 0)
                throw new IllegalStateException("Opaque FD export unsupported for format " + format);
            var semProperties = VkExternalSemaphoreProperties.calloc(stack).sType$Default();
            vkGetPhysicalDeviceExternalSemaphoreProperties(physical, VkPhysicalDeviceExternalSemaphoreInfo.calloc(stack).sType$Default()
                    .handleType(VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_FD_BIT), semProperties);
            if ((semProperties.externalSemaphoreFeatures() & VK_EXTERNAL_SEMAPHORE_FEATURE_EXPORTABLE_BIT) == 0)
                throw new IllegalStateException("Opaque FD semaphore export is unsupported");
            var handle = stack.mallocLong(1);
            var externalImage = VkExternalMemoryImageCreateInfo.calloc(stack).sType$Default().handleTypes(VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT);
            var create = VkImageCreateInfo.calloc(stack).sType$Default().pNext(externalImage.address()).imageType(VK_IMAGE_TYPE_2D)
                    .format(format).mipLevels(1).arrayLayers(1).samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL).usage(usage).sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            create.extent().set(width, height, 1);
            check(vkCreateImage(device, create, null, handle), "shared image");
            image = handle.get(0);
            var requirements = VkMemoryRequirements.calloc(stack);
            vkGetImageMemoryRequirements(device, image, requirements);
            var memoryProperties = VkPhysicalDeviceMemoryProperties.calloc(stack);
            vkGetPhysicalDeviceMemoryProperties(physical, memoryProperties);
            int type = -1;
            for (int i = 0; i < memoryProperties.memoryTypeCount(); i++)
                if ((requirements.memoryTypeBits() & (1 << i)) != 0 && (memoryProperties.memoryTypes(i).propertyFlags() & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) != 0) { type = i; break; }
            if (type < 0) throw new IllegalStateException("No shared image device memory type");
            var dedicated = VkMemoryDedicatedAllocateInfo.calloc(stack).sType$Default().image(image);
            var export = VkExportMemoryAllocateInfo.calloc(stack).sType$Default().pNext(dedicated.address()).handleTypes(VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT);
            check(vkAllocateMemory(device, VkMemoryAllocateInfo.calloc(stack).sType$Default().pNext(export.address())
                    .allocationSize(requirements.size()).memoryTypeIndex(type), null, handle), "shared image memory");
            memory = handle.get(0);
            check(vkBindImageMemory(device, image, memory, 0), "bind shared image");
            toGl = createSemaphore(stack);
            fromGl = createSemaphore(stack);
            glMemory = glCreateMemoryObjectsEXT();
            glMemoryObjectParameteriEXT(glMemory, GL_DEDICATED_MEMORY_OBJECT_EXT, GL_TRUE);
            var fdResult = stack.mallocInt(1);
            check(vkGetMemoryFdKHR(device, VkMemoryGetFdInfoKHR.calloc(stack).sType$Default().memory(memory)
                    .handleType(VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT), fdResult), "export shared memory FD");
            try (OwnedFd fd = new OwnedFd(fdResult.get(0))) {
                glImportMemoryFdEXT(glMemory, requirements.size(), GL_HANDLE_TYPE_OPAQUE_FD_EXT, fd.value());
                checkGl("import game image memory");
                fd.transferred();
            }
            glToGl = importSemaphore(toGl, stack);
            glFromGl = importSemaphore(fromGl, stack);
            texture = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, texture);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_TILING_EXT, GL_OPTIMAL_TILING_EXT);
            glTexStorageMem2DEXT(GL_TEXTURE_2D, 1, glFormat, width, height, glMemory, 0);
            framebuffer = glGenFramebuffers();
            glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
            int attachment = aspect == VK_IMAGE_ASPECT_COLOR_BIT ? GL_COLOR_ATTACHMENT0
                    : (aspect & VK_IMAGE_ASPECT_STENCIL_BIT) != 0 ? GL_DEPTH_STENCIL_ATTACHMENT : GL_DEPTH_ATTACHMENT;
            glFramebufferTexture2D(GL_FRAMEBUFFER, attachment, GL_TEXTURE_2D, texture, 0);
            if (aspect != VK_IMAGE_ASPECT_COLOR_BIT) { glDrawBuffer(GL_NONE); glReadBuffer(GL_NONE); }
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) throw new IllegalStateException("Shared game image FBO incomplete");
            checkGl("create shared game image FBO");
        } catch (Throwable failure) {
            close();
            throw failure;
        }
    }

    private long createSemaphore(MemoryStack stack) {
        var export = VkExportSemaphoreCreateInfo.calloc(stack).sType$Default().handleTypes(VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_FD_BIT);
        var result = stack.mallocLong(1);
        check(vkCreateSemaphore(device, VkSemaphoreCreateInfo.calloc(stack).sType$Default().pNext(export.address()), null, result), "shared semaphore");
        return result.get(0);
    }
    private int importSemaphore(long semaphore, MemoryStack stack) {
        var result = stack.mallocInt(1);
        check(vkGetSemaphoreFdKHR(device, VkSemaphoreGetFdInfoKHR.calloc(stack).sType$Default().semaphore(semaphore)
                .handleType(VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_FD_BIT), result), "export game semaphore FD");
        int glSemaphore = glGenSemaphoresEXT();
        try (OwnedFd fd = new OwnedFd(result.get(0))) {
            glImportSemaphoreFdEXT(glSemaphore, GL_HANDLE_TYPE_OPAQUE_FD_EXT, fd.value());
            checkGl("import game semaphore");
            fd.transferred();
            return glSemaphore;
        } catch (Throwable failure) { glDeleteSemaphoresEXT(glSemaphore); throw failure; }
    }
    public long image() { assertOwner(); return image; }
    public long readyForGl() { assertOwner(); return toGl; }
    public long readyForVulkan() { assertOwner(); return fromGl; }
    public int width() { return width; }
    public int height() { return height; }

    /** Local graphics ownership: prepare an image for a GPU copy from the scene. */
    public void prepareTransferDestination(VkCommandBuffer command) {
        assertOwner();
        barrier(command, layout, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                layout == VK_IMAGE_LAYOUT_UNDEFINED ? 0 : VK_ACCESS_TRANSFER_READ_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED,
                layout == VK_IMAGE_LAYOUT_UNDEFINED ? VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT : VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT);
        layout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    }
    /** Record release on the game's graphics queue, then signal readyForGl in that submission. */
    public void release(VkCommandBuffer command, int family) {
        assertOwner();
        int access = layout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL ? VK_ACCESS_TRANSFER_WRITE_BIT
                : layout == VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL ? VK_ACCESS_TRANSFER_READ_BIT : 0;
        barrier(command, layout, VK_IMAGE_LAYOUT_GENERAL, access, 0, family, VK_QUEUE_FAMILY_EXTERNAL,
                layout == VK_IMAGE_LAYOUT_UNDEFINED ? VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT : VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
        layout = VK_IMAGE_LAYOUT_GENERAL;
    }
    /** Wait in the GL queue and return the sampled texture, without changing framebuffer state. */
    public int beginGlRead() {
        assertOwner();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            glWaitSemaphoreEXT(glToGl, stack.mallocInt(0), stack.ints(texture), stack.ints(GL_LAYOUT_GENERAL_EXT));
        }
        return texture;
    }
    /** Wait in the GL queue; returns this real GL FBO for the producer. No CPU wait/readback. */
    public int beginGlWrite() {
        beginGlRead();
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
        glViewport(0, 0, width, height);
        return framebuffer;
    }
    /** Wait and return the destination for a named blit, without binding any FBO. */
    public int beginGlCopy() {
        beginGlRead();
        return framebuffer;
    }
    public void endGlRead() { endGlWrite(); }
    public void endGlWrite() {
        assertOwner();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            glSignalSemaphoreEXT(glFromGl, stack.mallocInt(0), stack.ints(texture), stack.ints(GL_LAYOUT_GENERAL_EXT));
            glFlush();
            checkGl("submit shared game image");
        }
    }
    /** Record acquire in a submission waiting on readyForVulkan at TRANSFER. */
    public void acquire(VkCommandBuffer command, int family) {
        assertOwner();
        barrier(command, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, 0, VK_ACCESS_TRANSFER_READ_BIT,
                VK_QUEUE_FAMILY_EXTERNAL, family, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
        layout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
    }
    private void barrier(VkCommandBuffer command, int oldLayout, int newLayout, int srcAccess, int dstAccess,
                         int srcFamily, int dstFamily, int srcStage, int dstStage) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var barrier = VkImageMemoryBarrier.calloc(1, stack).sType$Default().image(image).oldLayout(oldLayout).newLayout(newLayout)
                    .srcAccessMask(srcAccess).dstAccessMask(dstAccess).srcQueueFamilyIndex(srcFamily).dstQueueFamilyIndex(dstFamily);
            barrier.subresourceRange().aspectMask(aspect).levelCount(1).layerCount(1);
            vkCmdPipelineBarrier(command, srcStage, dstStage, 0, null, null, barrier);
        }
    }
    private void assertOwner() {
        if (closed || Thread.currentThread() != owner) throw new IllegalStateException("Shared image used outside its owning render thread/lifetime");
    }
    public static void check(int result, String operation) {
        if (result != VK_SUCCESS) throw new IllegalStateException(operation + ": Vulkan result " + result);
    }
    private static void checkGl(String operation) {
        int error = glGetError();
        if (error != GL_NO_ERROR) throw new IllegalStateException(operation + ": GL error 0x" + Integer.toHexString(error));
    }
    @Override public void close() {
        if (closed) return;
        assertOwner();
        closed = true;
        if (framebuffer != 0) glDeleteFramebuffers(framebuffer);
        if (texture != 0) glDeleteTextures(texture);
        if (glToGl != 0) glDeleteSemaphoresEXT(glToGl);
        if (glFromGl != 0) glDeleteSemaphoresEXT(glFromGl);
        if (glMemory != 0) glDeleteMemoryObjectsEXT(glMemory);
        if (toGl != 0) vkDestroySemaphore(device, toGl, null);
        if (fromGl != 0) vkDestroySemaphore(device, fromGl, null);
        if (image != 0) vkDestroyImage(device, image, null);
        if (memory != 0) vkFreeMemory(device, memory, null);
    }
}
