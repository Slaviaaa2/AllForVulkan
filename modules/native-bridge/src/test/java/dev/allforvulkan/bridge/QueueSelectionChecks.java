package dev.allforvulkan.bridge;

import java.util.List;
import static dev.allforvulkan.bridge.QueueSelection.*;
import static org.lwjgl.vulkan.VK10.*;

/** Capability regressions that do not require owning each GPU/WSI topology. */
public final class QueueSelectionChecks {
    public static void main(String[] args) {
        var unified = List.of(family(VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT, true));
        expect(select(unified, false), new Selection(0, 0, 0, 0), false);
        // TRANSFER_BIT is optional on graphics/compute families.
        var dedicatedCompute = List.of(family(VK_QUEUE_GRAPHICS_BIT, true), family(VK_QUEUE_COMPUTE_BIT, false));
        expect(select(dedicatedCompute, false), new Selection(0, 0, 1, 1), false);
        var dedicated = List.of(family(VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT, true),
                family(VK_QUEUE_COMPUTE_BIT, false), family(VK_QUEUE_TRANSFER_BIT, false));
        expect(select(dedicated, false), new Selection(0, 0, 2, 1), false);

        var graphicsOnly = List.of(family(VK_QUEUE_GRAPHICS_BIT, true));
        expect(select(graphicsOnly, true), new Selection(0, 0, -1, -1), true);
        reject(select(graphicsOnly, false), false); // GL clients do not need AFV compute queues.
        var splitPresent = List.of(family(VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT, false), family(0, true));
        expect(select(splitPresent, false), new Selection(0, 1, 0, 0), false);
        expect(select(splitPresent, true), new Selection(0, 1, -1, -1), true);
        var combinedLater = List.of(family(VK_QUEUE_GRAPHICS_BIT, false), family(0, true),
                family(VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT, true));
        expect(select(combinedLater, true), new Selection(2, 2, -1, -1), true);

        var emptyFamily = List.of(new Family(VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT | VK_QUEUE_TRANSFER_BIT, 0, true),
                family(VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT, true));
        expect(select(emptyFamily, false), new Selection(1, 1, 1, 1), false);
        var headless = List.of(family(VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT, false), family(VK_QUEUE_COMPUTE_BIT, false));
        reject(select(headless, false), false); // Never guess that compute can present.
        reject(select(headless, true), true);
        reject(select(List.of(family(VK_QUEUE_COMPUTE_BIT, true)), false), false);
        reject(select(List.of(family(VK_QUEUE_COMPUTE_BIT, true)), true), true);
        reject(select(List.of(), false), false);
        reject(select(List.of(), true), true);
        // Probing an unsuitable first GPU must return normally, allowing a later candidate.
        int suitable = 0;
        for (var gpu : List.of(headless, unified)) if (select(gpu, false).suitable(false)) suitable++;
        if (suitable != 1) throw new AssertionError("An unsuitable GPU prevented a later suitable candidate");
        System.out.println("AFV_QUEUE_SELECTION_PASS: shared/split present, later combined, zero count, implicit transfer, dedicated queues, no guessed present, missing compute/graphics, later GPU");
    }
    private static Family family(int flags, boolean present) { return new Family(flags, 1, present); }
    private static void expect(Selection actual, Selection expected, boolean presentationOnly) {
        if (!actual.equals(expected) || !actual.suitable(presentationOnly))
            throw new AssertionError("Unexpected queue selection: " + actual + ", expected " + expected);
    }
    private static void reject(Selection selection, boolean presentationOnly) {
        if (selection.suitable(presentationOnly)) throw new AssertionError("Unsupported queues accepted: " + selection);
    }
}
