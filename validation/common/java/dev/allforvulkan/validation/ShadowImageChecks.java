package dev.allforvulkan.validation;

import java.nio.file.Files;
import java.nio.file.Path;

/** Center-half shadow comparison without a client or a GPU. */
public final class ShadowImageChecks {
    public static void main(String[] args) throws Exception {
        int width = 32;
        int height = 32;
        byte[] left = fill(width, height, (byte) 128, (byte) 64, (byte) 192, (byte) 255);
        byte[] right = left.clone();
        require(ShadowImage.centerMismatches(left, right, width, height, ShadowImage.TOLERANCE) == 0, "identical center differed");
        right[0] = 0;
        require(ShadowImage.centerMismatches(left, right, width, height, ShadowImage.TOLERANCE) == 0, "corner counted as center");
        int center = ((height / 2) * width + (width / 2)) * 4;
        right[center] = 0;
        require(ShadowImage.centerMismatches(left, right, width, height, ShadowImage.TOLERANCE) == 1, "center pixel was ignored");
        right[center] = (byte) (128 - ShadowImage.TOLERANCE);
        require(ShadowImage.centerMismatches(left, right, width, height, ShadowImage.TOLERANCE) == 0, "tolerance was exclusive");
        Path path = Files.createTempFile("afv-shadow", ".rgba");
        ShadowImage.write(path, width, height, left);
        var loaded = ShadowImage.read(path);
        require(loaded.width() == width && loaded.height() == height, "header was not preserved");
        require(ShadowImage.centerMismatches(left, loaded.rgba(), width, height, 0) == 0, "round trip changed pixels");
        byte[] other = fill(width, height, (byte) 128, (byte) 64, (byte) 192, (byte) 255);
        other[0] = 1;
        require(ShadowImage.replacement(left, other, width, height) == other, "matching center was not replaced");
        byte[] white = fill(width, height, (byte) 255, (byte) 255, (byte) 255, (byte) 255);
        require(ShadowImage.replacement(white, other, width, height) == white, "mismatched center replaced the GL image");
        require(ShadowImage.replacement(left, null, width, height) == left, "missing Vulkan image replaced the GL image");
        System.out.println("AFV_SHADOW_IMAGE_PASS: center half, tolerance, round trip, fallback");
    }

    private static byte[] fill(int width, int height, byte r, byte g, byte b, byte a) {
        byte[] rgba = new byte[width * height * 4];
        for (int i = 0; i < rgba.length; i += 4) {
            rgba[i] = r;
            rgba[i + 1] = g;
            rgba[i + 2] = b;
            rgba[i + 3] = a;
        }
        return rgba;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
