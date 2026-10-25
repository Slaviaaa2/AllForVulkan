package dev.allforvulkan.validation;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/** RGBA shadow readback. The center half is the region a shadow comparison may require. */
public final class ShadowImage {
    public static final int TOLERANCE = 8;

    public record Loaded(int width, int height, byte[] rgba) {}

    private ShadowImage() {}

    public static void write(Path path, int width, int height, byte[] rgba) throws IOException {
        if (rgba.length != width * height * 4) throw new IllegalArgumentException("RGBA size does not match " + width + "x" + height);
        ByteBuffer header = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(0x53564641).putInt(width).putInt(height);
        Files.write(path, concat(header.array(), rgba));
    }

    public static Loaded read(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length < 12) throw new IllegalArgumentException("Shadow image is truncated");
        ByteBuffer header = ByteBuffer.wrap(bytes, 0, 12).order(ByteOrder.LITTLE_ENDIAN);
        int magic = header.getInt();
        int width = header.getInt();
        int height = header.getInt();
        if (magic != 0x53564641) throw new IllegalArgumentException("Not an AFV shadow image");
        if (width < 1 || height < 1 || bytes.length != 12 + width * height * 4)
            throw new IllegalArgumentException("Shadow image size is inconsistent");
        byte[] rgba = new byte[width * height * 4];
        System.arraycopy(bytes, 12, rgba, 0, rgba.length);
        return new Loaded(width, height, rgba);
    }

    /** Pixels in the center half whose channels differ by more than the tolerance. */
    public static int centerMismatches(byte[] left, byte[] right, int width, int height, int tolerance) {
        if (left.length != width * height * 4 || right.length != left.length)
            throw new IllegalArgumentException("RGBA size does not match " + width + "x" + height);
        int x0 = width / 4;
        int y0 = height / 4;
        int x1 = width - x0;
        int y1 = height - y0;
        int mismatches = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int index = (y * width + x) * 4;
                if (channel(left[index], right[index]) > tolerance
                        || channel(left[index + 1], right[index + 1]) > tolerance
                        || channel(left[index + 2], right[index + 2]) > tolerance
                        || channel(left[index + 3], right[index + 3]) > tolerance)
                    mismatches++;
            }
        }
        return mismatches;
    }

    public static int centerPixels(int width, int height) {
        return (width - width / 4 - width / 4) * (height - height / 4 - height / 4);
    }

    /** The Vulkan image when its center matches, otherwise the GL image. A null Vulkan image keeps GL. */
    public static byte[] replacement(byte[] gl, byte[] vulkan, int width, int height) {
        if (gl == null) return null;
        if (vulkan == null || vulkan.length != gl.length) return gl;
        if (centerMismatches(gl, vulkan, width, height, TOLERANCE) != 0) return gl;
        return vulkan;
    }

    private static int channel(byte left, byte right) {
        return Math.abs((left & 0xff) - (right & 0xff));
    }

    private static byte[] concat(byte[] header, byte[] rgba) {
        byte[] all = new byte[header.length + rgba.length];
        System.arraycopy(header, 0, all, 0, header.length);
        System.arraycopy(rgba, 0, all, header.length, rgba.length);
        return all;
    }
}
