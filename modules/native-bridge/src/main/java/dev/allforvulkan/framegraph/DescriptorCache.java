package dev.allforvulkan.framegraph;

import java.util.LinkedHashMap;
import java.util.Map;

/** Reuses a descriptor slot for an identical buffer range, view, sampler and layout. */
public final class DescriptorCache {
    public record Key(long buffer, long offset, long range, long view, long sampler, int layout) {}

    private final Map<Key, Integer> slots = new LinkedHashMap<>();
    private int hits;

    public int slot(Key key) {
        Integer existing = slots.get(key);
        if (existing != null) { hits++; return existing; }
        int slot = slots.size();
        slots.put(key, slot);
        return slot;
    }

    public int hits() { return hits; }
    public int misses() { return slots.size(); }
}
