package dev.allforvulkan.framegraph;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** CPU pipeline cache. A restored key is a hit and does not rebuild the module. */
public final class PipelineLibrary {
    public record Key(String shaders, int colorFormat, int samples) {}

    private final Map<Key, Integer> generations = new LinkedHashMap<>();
    private int hits, misses, next = 1;

    public int prepare(Key key) {
        Integer existing = generations.get(key);
        if (existing != null) { hits++; return existing; }
        misses++;
        generations.put(key, next);
        return next++;
    }

    public int hits() { return hits; }
    public int misses() { return misses; }
    public int size() { return generations.size(); }

    public byte[] snapshot() {
        var encoded = new StringBuilder("AFVPIPE1\n");
        for (var entry : generations.entrySet()) {
            encoded.append(entry.getValue()).append('\t')
                    .append(entry.getKey().shaders()).append('\t')
                    .append(entry.getKey().colorFormat()).append('\t')
                    .append(entry.getKey().samples()).append('\n');
        }
        return encoded.toString().getBytes(StandardCharsets.UTF_8);
    }

    public static PipelineLibrary restore(byte[] snapshot) {
        String text = new String(snapshot, StandardCharsets.UTF_8);
        if (!text.startsWith("AFVPIPE1\n")) throw new IllegalArgumentException("Unknown pipeline snapshot");
        var library = new PipelineLibrary();
        int max = 0;
        for (String line : text.substring("AFVPIPE1\n".length()).split("\n")) {
            if (line.isEmpty()) continue;
            String[] parts = line.split("\t");
            if (parts.length != 4) throw new IllegalArgumentException("Corrupt pipeline snapshot");
            int generation = Integer.parseInt(parts[0]);
            library.generations.put(new Key(parts[1], Integer.parseInt(parts[2]), Integer.parseInt(parts[3])), generation);
            max = Math.max(max, generation);
        }
        library.next = max + 1;
        return library;
    }

    public static String shaderIdentity(int[]... modules) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (int[] module : modules) digest.update(Arrays.toString(module).getBytes(StandardCharsets.UTF_8));
            var bytes = digest.digest();
            var hex = new StringBuilder();
            for (byte value : bytes) hex.append(String.format("%02x", value));
            return hex.toString();
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
