package dev.allforvulkan.bridge;

import java.nio.ByteBuffer;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;
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

/** Actual cross-VkDevice GPU image sharing, not a Java-copy simulation. */
public final class ExternalImageProbe {
    static void run() {
        var caps = GL.getCapabilities();
        if (!caps.GL_EXT_memory_object_fd || !caps.GL_EXT_semaphore_fd)
            throw new IllegalStateException("Zink lacks external memory/semaphore FD support");
        byte[] uuid = new byte[GL_UUID_SIZE_EXT];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer bytes = stack.malloc(GL_UUID_SIZE_EXT);
            glGetUnsignedBytei_vEXT(GL_DEVICE_UUID_EXT, 0, bytes);
            bytes.get(uuid);
        }
        // Recreate at two sizes to exercise ownership and destruction across resize.
        for (int size : new int[] {16, 31}) {
            try (VulkanExternalImage image = new VulkanExternalImage(uuid, size, size)) {
                roundTrip(image, size);
            }
        }
        System.out.println("AFV_EXTERNAL_IMAGE_PASS: UUID match, opaque FD image, bidirectional semaphores, repeated frames, resize");
    }

    private static void roundTrip(VulkanExternalImage image, int size) {
        int memory = glCreateMemoryObjectsEXT(), toGl = 0, fromGl = 0, texture = 0, framebuffer = 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            glMemoryObjectParameteriEXT(memory, GL_DEDICATED_MEMORY_OBJECT_EXT, GL_TRUE);
            try (OwnedFd fd = new OwnedFd(image.exportMemoryFd())) {
                glImportMemoryFdEXT(memory, image.allocationSize(), GL_HANDLE_TYPE_OPAQUE_FD_EXT, fd.value());
                check("import Vulkan image memory");
                fd.transferred();
            }
            toGl = importSemaphore(image, true);
            fromGl = importSemaphore(image, false);
            texture = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, texture);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_TILING_EXT, GL_OPTIMAL_TILING_EXT);
            glTexStorageMem2DEXT(GL_TEXTURE_2D, 1, GL_RGBA8, size, size, memory, 0);
            check("bind external texture storage");
            framebuffer = glGenFramebuffers();
            glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
            if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) throw new IllegalStateException("Imported image FBO is incomplete");
            for (int frame = 0; frame < 8; frame++) {
                image.releaseToGl();
                glWaitSemaphoreEXT(toGl, stack.mallocInt(0), stack.ints(texture), stack.ints(GL_LAYOUT_GENERAL_EXT));
                ByteBuffer pixel = stack.malloc(4);
                glReadPixels(0, 0, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
                if (pixel.get(0) != 0 || (pixel.get(1) & 255) != 255 || pixel.get(2) != 0 || (pixel.get(3) & 255) != 255)
                    throw new IllegalStateException("Zink did not see the Vulkan green image");
                glClearColor(1, 0, 1, 1);
                glClear(GL_COLOR_BUFFER_BIT);
                glSignalSemaphoreEXT(fromGl, stack.mallocInt(0), stack.ints(texture), stack.ints(GL_LAYOUT_GENERAL_EXT));
                glFlush();
                check("external semaphore round trip");
                image.acquireFromGlAndVerify();
            }
            System.out.println("EXTERNAL_IMAGE_FRAMES=8 SIZE=" + size + "x" + size);
        } finally {
            // Drain this probe's GL queue also on exceptional/partial initialization.
            // A one-time teardown wait is not used in any product frame path.
            glFinish();
            // The Vk acquire fence has completed before destroying shared objects.
            if (framebuffer != 0) glDeleteFramebuffers(framebuffer);
            if (texture != 0) glDeleteTextures(texture);
            if (toGl != 0) glDeleteSemaphoresEXT(toGl);
            if (fromGl != 0) glDeleteSemaphoresEXT(fromGl);
            glDeleteMemoryObjectsEXT(memory);
        }
    }
    private static int importSemaphore(VulkanExternalImage image, boolean toGl) {
        int semaphore = glGenSemaphoresEXT();
        try (OwnedFd fd = new OwnedFd(image.exportSemaphoreFd(toGl))) {
            glImportSemaphoreFdEXT(semaphore, GL_HANDLE_TYPE_OPAQUE_FD_EXT, fd.value());
            check("import Vulkan semaphore");
            fd.transferred();
            return semaphore;
        } catch (Throwable failure) {
            glDeleteSemaphoresEXT(semaphore);
            throw failure;
        }
    }
    private static void check(String operation) {
        int error = glGetError();
        if (error != GL_NO_ERROR) throw new IllegalStateException(operation + ": GL error 0x" + Integer.toHexString(error));
    }
}
