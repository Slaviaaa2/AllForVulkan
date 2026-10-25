package dev.allforvulkan.framegraph;

/** Persistent mapped uniform slices. A frame may be written again only after retire. */
public final class UniformRing {
    private final int frames;
    private final int sliceBytes;
    private final int alignment;
    private final boolean[] inflight;

    public UniformRing(int frames, int sliceBytes, int alignment) {
        if (frames < 1 || sliceBytes < 1 || alignment < 1 || Integer.bitCount(alignment) != 1)
            throw new IllegalArgumentException("Uniform ring needs frames, a slice, and a power-of-two alignment");
        this.frames = frames;
        this.sliceBytes = sliceBytes;
        this.alignment = alignment;
        inflight = new boolean[frames];
    }

    public int frames() { return frames; }
    public int sliceBytes() { return sliceBytes; }
    public int stride() { return (sliceBytes + alignment - 1) & ~(alignment - 1); }
    public int bytes() { return stride() * frames; }

    public int offset(int frame) {
        if (frame < 0 || frame >= frames) throw new IllegalArgumentException("Uniform frame out of range");
        return frame * stride();
    }

    /** Reserves the slice. Fails if that frame is still in flight. */
    public int acquire(int frame) {
        int offset = offset(frame);
        if (inflight[frame]) throw new IllegalStateException("Uniform slice " + frame + " is still in flight");
        inflight[frame] = true;
        return offset;
    }

    public void retire(int frame) {
        offset(frame);
        inflight[frame] = false;
    }
}
