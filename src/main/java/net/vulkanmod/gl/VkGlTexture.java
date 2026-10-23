package net.vulkanmod.gl;

import it.unimi.dsi.fastutil.ints.Int2ReferenceOpenHashMap;
import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.texture.ImageUtil;
import net.vulkanmod.vulkan.texture.SamplerManager;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.vulkan.VK10.*;

public class VkGlTexture {
    private static int ID_COUNTER = 1;
    private static final Int2ReferenceOpenHashMap<VkGlTexture> map = new Int2ReferenceOpenHashMap<>();
    private static int boundTextureId = 0;
    private static VkGlTexture boundTexture;
    private static int activeTexture = 0;

    private static int unpackAlignment = 4;
    private static int unpackRowLength;
    private static int unpackSkipRows;
    private static int unpackSkipPixels;

    public static final class UploadState implements AutoCloseable {
        private final int texture = boundTextureId, unit = activeTexture;
        private final int alignment = unpackAlignment, rows = unpackRowLength, skipRows = unpackSkipRows, skipPixels = unpackSkipPixels;
        private UploadState() {}
        @Override public void close() {
            unpackAlignment = alignment; unpackRowLength = rows; unpackSkipRows = skipRows; unpackSkipPixels = skipPixels;
            activeTexture(GL30.GL_TEXTURE0 + unit);
            bindTexture(texture);
        }
    }
    public static UploadState preserveUploadState() { return new UploadState(); }

    public static void bindIdToImage(int id, VulkanImage vulkanImage) {
        VkGlTexture texture = map.get(id);
        texture.vulkanImage = vulkanImage;
    }

    public static int genTextureId() {
        var registry = dev.allforvulkan.bridge.GlTextureRegistry.active();
        int id = registry != null ? registry.create() : ID_COUNTER++;
        map.put(id, new VkGlTexture(id));
        return id;
    }

    public static void bindTexture(int id) {
        boundTextureId = id;
        boundTexture = map.get(id);
        if (dev.allforvulkan.bridge.GlTextureRegistry.active() != null)
            org.lwjgl.opengl.GL11.glBindTexture(GL11.GL_TEXTURE_2D, Math.max(id, 0));

        if (id <= 0)
            return;

        if (boundTexture == null)
            throw new NullPointerException("bound texture is null");

        VulkanImage vulkanImage = boundTexture.vulkanImage;
        if (vulkanImage != null)
            VTextureSelector.bindTexture(activeTexture, vulkanImage);
    }

    public static void glDeleteTextures(IntBuffer intBuffer) {
        for (int i = intBuffer.position(); i < intBuffer.limit(); i++) {
            glDeleteTextures(intBuffer.get(i));
        }
    }

    public static void glDeleteTextures(int i) {
        var registry = dev.allforvulkan.bridge.GlTextureRegistry.active();
        if (registry != null) registry.delete(i);
        VkGlTexture glTexture = map.remove(i);
        if (boundTexture == glTexture) { boundTexture = null; boundTextureId = 0; }
        VulkanImage image = glTexture != null ? glTexture.vulkanImage : null;
        if (image != null)
            MemoryManager.getInstance().addToFreeable(image);
    }

    public static void unregisterAlias(int id) {
        VkGlTexture texture = map.remove(id);
        if (texture == null) return;
        var registry = dev.allforvulkan.bridge.GlTextureRegistry.active();
        if (registry != null) registry.delete(id);
        if (boundTexture == texture) { boundTexture = null; boundTextureId = 0; }
    }

    public static VkGlTexture getTexture(int id) {
        if (id == 0)
            return null;

        return map.get(id);
    }

    public static void activeTexture(int i) {
        activeTexture = i - GL30.GL_TEXTURE0;
        if (dev.allforvulkan.bridge.GlTextureRegistry.active() != null)
            org.lwjgl.opengl.GL13.glActiveTexture(i);
        VTextureSelector.setActiveTexture(activeTexture);
    }

    public static void texImage2D(int target, int level, int internalFormat, int width, int height, int border, int format, int type, long pixels) {
        if (checkParams(level, width, height))
            return;

        boundTexture.updateParams(level, width, height, internalFormat, type);
        boundTexture.allocateIfNeeded();
        var registry = dev.allforvulkan.bridge.GlTextureRegistry.active();
        if (registry != null) registry.allocate(boundTextureId, level, internalFormat, width, height, border, format, type);

        VTextureSelector.bindTexture(activeTexture, boundTexture.vulkanImage);

        texSubImage2D(target, level, 0, 0, width, height, format, type, pixels);
    }

    public static void texImage2D(int target, int level, int internalFormat, int width, int height, int border, int format, int type, @Nullable ByteBuffer pixels) {
        if (checkParams(level, width, height))
            return;

        boundTexture.updateParams(level, width, height, internalFormat, type);
        boundTexture.allocateIfNeeded();
        var registry = dev.allforvulkan.bridge.GlTextureRegistry.active();
        if (registry != null) registry.allocate(boundTextureId, level, internalFormat, width, height, border, format, type);

        VTextureSelector.bindTexture(activeTexture, boundTexture.vulkanImage);

        texSubImage2D(target, level, 0, 0, width, height, format, type, pixels);
    }

    private static boolean checkParams(int level, int width, int height) {
        if (width == 0 || height == 0)
            return true;

        return false;
    }

    public static void texSubImage2D(int target, int level, int xOffset, int yOffset, int width, int height, int format, int type, long pixels) {
        if (width == 0 || height == 0) return;
        VkGlBuffer buffer = VkGlBuffer.getPixelUnpackBufferBound();
        if (buffer != null) {
            if (pixels < 0 || pixels > buffer.data.capacity()) throw new IllegalArgumentException("Invalid unpack buffer offset");
            uploadFromAddress(level, xOffset, yOffset, width, height, format, type,
                    MemoryUtil.memAddress0(buffer.data) + pixels, buffer.data.capacity() - pixels);
        } else if (pixels != 0) uploadFromAddress(level, xOffset, yOffset, width, height, format, type, pixels, Long.MAX_VALUE);
    }

    public static void texSubImage2D(int target, int level, int xOffset, int yOffset, int width, int height, int format, int type, @Nullable ByteBuffer pixels) {
        if (width == 0 || height == 0) return;
        VkGlBuffer buffer = VkGlBuffer.getPixelUnpackBufferBound();
        if (buffer != null) {
            if (pixels != null) throw new IllegalStateException("CPU pixels supplied with a pixel unpack buffer bound");
            uploadFromAddress(level, xOffset, yOffset, width, height, format, type, MemoryUtil.memAddress0(buffer.data), buffer.data.capacity());
        } else if (pixels != null) uploadFromAddress(level, xOffset, yOffset, width, height, format, type,
                MemoryUtil.memAddress(pixels), pixels.remaining());
    }

    private static void uploadFromAddress(int level, int x, int y, int width, int height, int format, int type, long source, long available) {
        int required = TextureUpload.requiredBytes(width, height, format, type, unpackAlignment, unpackRowLength, unpackSkipRows, unpackSkipPixels);
        if (required > available) throw new IllegalArgumentException("Pixel upload exceeds source buffer: " + required + " > " + available);
        var registry = dev.allforvulkan.bridge.GlTextureRegistry.active();
        if (registry != null) registry.upload(boundTextureId, level, x, y, width, height, format, type, source,
                unpackAlignment, unpackRowLength, unpackSkipRows, unpackSkipPixels);
        try (TextureUpload upload = TextureUpload.decode(source, width, height, format, type, unpackAlignment,
                unpackRowLength, unpackSkipRows, unpackSkipPixels, boundTexture.vulkanImage.format)) {
            boundTexture.vulkanImage.uploadSubTextureAsync(level, width, height, x, y, 0, 0, 0, upload.bytes);
        }
    }

    public static void texParameteri(int target, int pName, int param) {
        if (target != GL11.GL_TEXTURE_2D)
            throw new UnsupportedOperationException("target != GL_TEXTURE_2D not supported");

        if (boundTexture == null)
            return;

        var registry = dev.allforvulkan.bridge.GlTextureRegistry.active();
        if (registry != null) registry.parameter(boundTextureId, pName, param);
        switch (pName) {
            case GL30.GL_TEXTURE_MAX_LEVEL -> boundTexture.setMaxLevel(param);
            case GL30.GL_TEXTURE_MAX_LOD -> boundTexture.setMaxLod(param);
            case GL30.GL_TEXTURE_MIN_LOD -> {}
            case GL30.GL_TEXTURE_LOD_BIAS -> {}

            case GL11.GL_TEXTURE_MAG_FILTER -> boundTexture.setMagFilter(param);
            case GL11.GL_TEXTURE_MIN_FILTER -> boundTexture.setMinFilter(param);

            case GL11.GL_TEXTURE_WRAP_S, GL11.GL_TEXTURE_WRAP_T -> boundTexture.setClamp(param);

            default -> {}
        }

        //TODO
    }

    public static int getTexParameteri(int target, int pName) {
        if (target != GL11.GL_TEXTURE_2D)
            throw new UnsupportedOperationException("target != GL_TEXTURE_2D not supported");

        if (boundTexture == null)
            return -1;

        return switch (pName) {
            case GL11.GL_TEXTURE_INTERNAL_FORMAT -> GlUtil.getGlFormat(boundTexture.vulkanImage.format);
            case GL11.GL_TEXTURE_WIDTH -> boundTexture.vulkanImage.width;
            case GL11.GL_TEXTURE_HEIGHT -> boundTexture.vulkanImage.height;

            case GL30.GL_TEXTURE_MAX_LEVEL -> boundTexture.maxLevel;
            case GL30.GL_TEXTURE_MAX_LOD -> boundTexture.maxLod;

            case GL11.GL_TEXTURE_MAG_FILTER -> boundTexture.magFilter;
            case GL11.GL_TEXTURE_MIN_FILTER -> boundTexture.minFilter;

            default -> -1;
        };
    }

    public static int getTexLevelParameter(int target, int level, int pName) {
        if (target != GL11.GL_TEXTURE_2D)
            throw new UnsupportedOperationException("target != GL_TEXTURE_2D not supported");

        if (boundTexture == null)
            return -1;

        return switch (pName) {
            case GL11.GL_TEXTURE_INTERNAL_FORMAT -> GlUtil.getGlFormat(boundTexture.vulkanImage.format);
            case GL11.GL_TEXTURE_WIDTH -> boundTexture.vulkanImage.width;
            case GL11.GL_TEXTURE_HEIGHT -> boundTexture.vulkanImage.height;

            default -> -1;
        };
    }

    public static void pixelStoreI(int pName, int value) {
        if (value < 0) throw new IllegalArgumentException("Negative pixel-store parameter");
        switch (pName) {
            case GL11.GL_UNPACK_ALIGNMENT -> {
                if (value != 1 && value != 2 && value != 4 && value != 8) throw new IllegalArgumentException("Invalid unpack alignment");
                unpackAlignment = value;
            }
            case GL11.GL_UNPACK_ROW_LENGTH -> unpackRowLength = value;
            case GL11.GL_UNPACK_SKIP_ROWS -> unpackSkipRows = value;
            case GL11.GL_UNPACK_SKIP_PIXELS -> unpackSkipPixels = value;
        }
    }

    public static void generateMipmap(int target) {
        if (target != GL11.GL_TEXTURE_2D)
            throw new UnsupportedOperationException("target != GL_TEXTURE_2D not supported");

        var registry = dev.allforvulkan.bridge.GlTextureRegistry.active();
        if (registry != null) registry.mipmap(boundTextureId);
        // Vulkan mip generation remains on its existing backend path.
    }

    public static void getTexImage(int tex, int level, int format, int type, long pixels) {
        VulkanImage image = boundTexture.vulkanImage;

        VkGlBuffer buffer = VkGlBuffer.getPixelPackBufferBound();
        long ptr;
        if (buffer != null) {
            buffer.data.position((int) pixels);

            ptr = MemoryUtil.memAddress(buffer.data);
        } else {
            ptr = pixels;
        }


        ImageUtil.downloadTexture(image, ptr);
    }

    public static void setVulkanImage(int id, VulkanImage vulkanImage) {
        VkGlTexture texture = map.get(id);

        texture.vulkanImage = vulkanImage;
    }

    public static VkGlTexture getBoundTexture() {
        return boundTexture;
    }

    public final int id;
    VulkanImage vulkanImage;

    int width, height;
    int vkFormat;

    boolean needsUpdate = false;
    int maxLevel = 0;
    int maxLod = 0;
    int minFilter, magFilter = GL11.GL_LINEAR;

    boolean clamp = true;

    public VkGlTexture(int id) {
        this.id = id;
    }

    void updateParams(int level, int width, int height, int internalFormat, int type) {
        if (level > this.maxLevel) {
            this.maxLevel = level;

            this.needsUpdate = true;
        }

        if (level == 0) {
            int vkFormat = GlUtil.vulkanFormat(internalFormat, type);

            if (this.vulkanImage == null || this.width != width || this.height != height || vkFormat != vulkanImage.format) {
                this.width = width;
                this.height = height;
                this.vkFormat = vkFormat;

                this.needsUpdate = true;
            }
        }
    }

    void allocateIfNeeded() {
        if (needsUpdate) {
            allocateImage(width, height, vkFormat);
            updateSampler();

            needsUpdate = false;
        }
    }

    void allocateImage(int width, int height, int vkFormat) {
        if (this.vulkanImage != null)
            this.vulkanImage.free();

        if (VulkanImage.isDepthFormat(vkFormat)) {
            this.vulkanImage = VulkanImage.createDepthImage(
                    vkFormat, width, height,
                    VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
                    false, true);
        }
        else {
            this.vulkanImage = new VulkanImage.Builder(width, height)
                    .setName(String.format("GlTexture %d", this.id))
                    .setMipLevels(maxLevel + 1)
                    .setFormat(vkFormat)
                    .addUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT)
                    .createVulkanImage();
        }
    }

    void updateSampler() {
        if (vulkanImage == null)
            return;

        byte samplerFlags;
        samplerFlags = clamp ? SamplerManager.CLAMP_BIT : 0;
        samplerFlags |= magFilter == GL11.GL_LINEAR ? SamplerManager.LINEAR_FILTERING_BIT : 0;

        samplerFlags |= switch (minFilter) {
            case GL11.GL_LINEAR_MIPMAP_LINEAR -> SamplerManager.USE_MIPMAPS_BIT | SamplerManager.MIPMAP_LINEAR_FILTERING_BIT;
            case GL11.GL_NEAREST_MIPMAP_NEAREST -> SamplerManager.USE_MIPMAPS_BIT;
            default -> 0;
        };

        vulkanImage.updateTextureSampler(maxLod, samplerFlags);
    }

    void generateMipmaps() {
        //TODO test
        ImageUtil.generateMipmaps(vulkanImage);
    }

    void setMaxLevel(int l) {
        if (l < 0)
            throw new IllegalStateException("max level cannot be < 0.");

        if (maxLevel != l) {
            maxLevel = l;
            needsUpdate = true;
        }
    }

    void setMaxLod(int l) {
        if (l < 0)
            throw new IllegalStateException("max level cannot be < 0.");

        if (maxLod != l) {
            maxLod = l;
            updateSampler();
        }
    }

    void setMagFilter(int v) {
        switch (v) {
            case GL11.GL_LINEAR, GL11.GL_NEAREST -> {
            }

            default -> throw new IllegalArgumentException("illegal mag filter value: " + v);
        }

        this.magFilter = v;
        updateSampler();
    }

    void setMinFilter(int v) {
        switch (v) {
            case GL11.GL_LINEAR, GL11.GL_NEAREST,
                 GL11.GL_LINEAR_MIPMAP_LINEAR, GL11.GL_NEAREST_MIPMAP_LINEAR,
                 GL11.GL_LINEAR_MIPMAP_NEAREST, GL11.GL_NEAREST_MIPMAP_NEAREST -> {
            }

            default -> throw new IllegalArgumentException("illegal min filter value: " + v);
        }

        this.minFilter = v;
        updateSampler();
    }

    void setClamp(int v) {
        if (v == GL30.GL_CLAMP_TO_EDGE) {
            this.clamp = true;
        } else {
            this.clamp = false;
        }

        updateSampler();
    }

    public VulkanImage getVulkanImage() {
        return vulkanImage;
    }

    public void setVulkanImage(VulkanImage vulkanImage) {
        this.vulkanImage = vulkanImage;
    }

}
