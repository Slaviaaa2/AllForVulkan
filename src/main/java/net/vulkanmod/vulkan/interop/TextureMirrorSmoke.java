package net.vulkanmod.vulkan.interop;

import dev.allforvulkan.bridge.GlStateScope;
import dev.allforvulkan.bridge.GlTextureRegistry;
import net.vulkanmod.gl.VkGlTexture;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_BGRA;

/** CPU input -> Minecraft GL wrappers -> real Zink sampling -> shared Vk image. */
final class TextureMirrorSmoke implements AutoCloseable {
    private int texture;
    TextureMirrorSmoke() {
        try (var uploadState = VkGlTexture.preserveUploadState(); MemoryStack stack = MemoryStack.stackPush(); var state = new GlStateScope()) {
            texture = VkGlTexture.genTextureId();
            VkGlTexture.bindTexture(texture);
            VkGlTexture.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            VkGlTexture.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            VkGlTexture.texImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 2, 2, 0, GL_RGBA, GL_UNSIGNED_BYTE, 0L);
            // Exercise padded rows and skips through the same pointer overload NativeImage/mods use.
            var pixels = stack.calloc(4 * 4 * 4);
            int[] rgba = {0xFF00FFFF, 0xFFFF00FF, 0x00FFFFFF, 0xFFFFFFFF}; // RRGGBBAA
            for (int y = 0; y < 2; y++) for (int x = 0; x < 2; x++) {
                int color = rgba[y * 2 + x], offset = ((y + 1) * 4 + x + 1) * 4;
                pixels.put(offset, (byte) (color >>> 8));
                pixels.put(offset + 1, (byte) (color >>> 16));
                pixels.put(offset + 2, (byte) (color >>> 24));
                pixels.put(offset + 3, (byte) 255);
            }
            VkGlTexture.pixelStoreI(GL_UNPACK_ALIGNMENT, 1);
            VkGlTexture.pixelStoreI(GL_UNPACK_ROW_LENGTH, 4);
            VkGlTexture.pixelStoreI(GL_UNPACK_SKIP_ROWS, 1);
            VkGlTexture.pixelStoreI(GL_UNPACK_SKIP_PIXELS, 1);
            VkGlTexture.texSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 2, 2, GL_BGRA, GL_UNSIGNED_BYTE, MemoryUtil.memAddress(pixels));
            VkGlTexture.pixelStoreI(GL_UNPACK_ALIGNMENT, 4);
            VkGlTexture.pixelStoreI(GL_UNPACK_ROW_LENGTH, 0);
            VkGlTexture.pixelStoreI(GL_UNPACK_SKIP_ROWS, 0);
            VkGlTexture.pixelStoreI(GL_UNPACK_SKIP_PIXELS, 0);
            verifyVulkan(stack, rgba);
            // Compare both packed types and both channel formats against the actual GL driver.
            var packed = stack.malloc(4).putInt(0, 0x11223344);
            for (int format : new int[] {GL_RGBA, GL_BGRA}) for (int type : new int[] {
                    org.lwjgl.opengl.GL12.GL_UNSIGNED_INT_8_8_8_8, org.lwjgl.opengl.GL12.GL_UNSIGNED_INT_8_8_8_8_REV}) {
                VkGlTexture.texSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 1, 1, format, type, packed);
                int[] expected = rgba.clone();
                if (type == org.lwjgl.opengl.GL12.GL_UNSIGNED_INT_8_8_8_8_REV)
                    expected[0] = format == GL_RGBA ? 0x44332211 : 0x22334411;
                else expected[0] = format == GL_RGBA ? 0x11223344 : 0x33221144;
                verifyVulkan(stack, expected);
                verifyGl(stack, expected);
            }
            // PBO source positions and byte offsets must reach both backends unchanged.
            var pboData = stack.calloc(24);
            for (int i = 0; i < 4; i++) {
                int c = rgba[i], offset = 8 + i * 4;
                pboData.put(offset, (byte) (c >>> 24)).put(offset + 1, (byte) (c >>> 16))
                        .put(offset + 2, (byte) (c >>> 8)).put(offset + 3, (byte) 255);
            }
            pboData.position(4);
            int pbo = net.vulkanmod.gl.VkGlBuffer.glGenBuffers();
            try {
                net.vulkanmod.gl.VkGlBuffer.glBindBuffer(org.lwjgl.opengl.GL21.GL_PIXEL_UNPACK_BUFFER, pbo);
                net.vulkanmod.gl.VkGlBuffer.glBufferData(org.lwjgl.opengl.GL21.GL_PIXEL_UNPACK_BUFFER, pboData, org.lwjgl.opengl.GL15.GL_STATIC_DRAW);
                VkGlTexture.texSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 2, 2, GL_RGBA, GL_UNSIGNED_BYTE, 4L);
            } finally { net.vulkanmod.gl.VkGlBuffer.glDeleteBuffers(pbo); }
            verifyVulkan(stack, rgba);
            GlTextureRegistry.active().verifyDimensions(texture, 2, 2);
            net.vulkanmod.Initializer.LOGGER.info("AFV_TEXTURE_DUAL_UPLOAD_PASS: BGRA/skips, RGBA/BGRA packed+REV checked against real GL, PBO offsets verified in Vulkan");
        } catch (Throwable failure) { close(); throw failure; }
    }
    private void verifyGl(MemoryStack stack, int[] expected) {
        int pack = glGetInteger(org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        int alignment = glGetInteger(GL_PACK_ALIGNMENT), rows = glGetInteger(GL_PACK_ROW_LENGTH);
        int skipRows = glGetInteger(GL_PACK_SKIP_ROWS), skipPixels = glGetInteger(GL_PACK_SKIP_PIXELS);
        try {
            org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER, 0);
            glPixelStorei(GL_PACK_ALIGNMENT, 1); glPixelStorei(GL_PACK_ROW_LENGTH, 0);
            glPixelStorei(GL_PACK_SKIP_ROWS, 0); glPixelStorei(GL_PACK_SKIP_PIXELS, 0);
            var bytes = stack.malloc(16);
            glGetTexImage(GL_TEXTURE_2D, 0, GL_RGBA, GL_UNSIGNED_BYTE, bytes);
            for (int i = 0; i < 4; i++) for (int channel = 0; channel < 4; channel++)
                if ((bytes.get(i * 4 + channel) & 255) != (expected[i] >>> (24 - channel * 8) & 255))
                    throw new IllegalStateException("AFV_TEXTURE_GL_PACKED_FAIL: pixel " + i + " channel " + channel);
            GlTextureRegistry.check("verify packed GL texture input");
        } finally {
            glPixelStorei(GL_PACK_ALIGNMENT, alignment); glPixelStorei(GL_PACK_ROW_LENGTH, rows);
            glPixelStorei(GL_PACK_SKIP_ROWS, skipRows); glPixelStorei(GL_PACK_SKIP_PIXELS, skipPixels);
            org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER, pack);
        }
    }
    private void verifyVulkan(MemoryStack stack, int[] expected) {
        // Private 2x2 assertion at test setup, never product image transport.
        var bytes = stack.malloc(16);
        net.vulkanmod.render.texture.ImageUploadHelper.INSTANCE.submitCommands();
        net.vulkanmod.vulkan.texture.ImageUtil.downloadTexture(VkGlTexture.getTexture(texture).getVulkanImage(), MemoryUtil.memAddress(bytes));
        for (int i = 0; i < 4; i++) for (int channel = 0; channel < 4; channel++)
            if ((bytes.get(i * 4 + channel) & 255) != (expected[i] >>> (24 - channel * 8) & 255))
                throw new IllegalStateException("AFV_TEXTURE_VULKAN_FAIL: pixel " + i + " channel " + channel);
    }
    int texture() { return texture; }
    @Override public void close() { if (texture != 0) { VkGlTexture.glDeleteTextures(texture); texture = 0; } }
}
