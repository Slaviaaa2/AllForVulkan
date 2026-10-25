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

    /** Pixels with no sample within one texel whose channels are inside the tolerance.
     * A one-pixel raster shift between GL and Vulkan still matches. A different shape does not. */
    public static int nearMismatches(byte[] left, byte[] right, int width, int height, int tolerance) {
        if (left.length != width * height * 4 || right.length != left.length)
            throw new IllegalArgumentException("RGBA size does not match " + width + "x" + height);
        int mismatches = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (!near(left, right, width, height, x, y, tolerance)) mismatches++;
            }
        }
        return mismatches;
    }

    /** Up to {@code limit} GL pixels that have no Vulkan sample within one texel. */
    public static String nearMismatchSummary(byte[] left, byte[] right, int width, int height, int tolerance, int limit) {
        if (left == null || right == null || left.length != right.length) return " unavailable";
        StringBuilder summary = new StringBuilder();
        int found = 0;
        for (int y = 0; y < height && found < limit; y++) {
            for (int x = 0; x < width && found < limit; x++) {
                if (near(left, right, width, height, x, y, tolerance)) continue;
                int index = (y * width + x) * 4;
                summary.append(" (").append(x).append(',').append(y).append(" gl=")
                        .append(left[index] & 255).append(',').append(left[index + 1] & 255).append(',')
                        .append(left[index + 2] & 255).append(',').append(left[index + 3] & 255)
                        .append(" vk=").append(right[index] & 255).append(',').append(right[index + 1] & 255).append(',')
                        .append(right[index + 2] & 255).append(',').append(right[index + 3] & 255).append(')');
                found++;
            }
        }
        return summary.toString();
    }

    /** The Vulkan image when every pixel has a neighbor within tolerance, otherwise the GL image. */
    public static byte[] replacementNear(byte[] gl, byte[] vulkan, int width, int height) {
        if (gl == null) return null;
        if (vulkan == null || vulkan.length != gl.length) return gl;
        if (nearMismatches(gl, vulkan, width, height, TOLERANCE) != 0) return gl;
        return vulkan;
    }

    private static boolean near(byte[] left, byte[] right, int width, int height, int x, int y, int tolerance) {
        int index = (y * width + x) * 4;
        for (int dy = -1; dy <= 1; dy++) {
            int yy = y + dy;
            if (yy < 0 || yy >= height) continue;
            for (int dx = -1; dx <= 1; dx++) {
                int xx = x + dx;
                if (xx < 0 || xx >= width) continue;
                int other = (yy * width + xx) * 4;
                if (channel(left[index], right[other]) <= tolerance
                        && channel(left[index + 1], right[other + 1]) <= tolerance
                        && channel(left[index + 2], right[other + 2]) <= tolerance
                        && channel(left[index + 3], right[other + 3]) <= tolerance)
                    return true;
            }
        }
        return false;
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
