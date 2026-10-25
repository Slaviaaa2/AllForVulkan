package dev.allforvulkan.framegraph;

/** Lavapipe image of AFV-Shadow's clear color, plus the decision to keep a mismatched GL image. */
public final class ShadowConstantPassChecks {
    public static void main(String[] args) {
        var image = ShadowConstantPass.render(32);
        if (image.rgba() == null) {
            if (Boolean.getBoolean("afv.requireCompositeDevice")) throw new IllegalStateException(image.detail());
            System.out.println("AFV_SHADOW_CONSTANT_SKIP: " + image.detail());
            return;
        }
        var again = ShadowConstantPass.render(32);
        if (again.rgba() != image.rgba()) throw new IllegalStateException("The second render created another image");
        System.out.println("AFV_SHADOW_CONSTANT_PASS: " + image.detail());
    }
}
