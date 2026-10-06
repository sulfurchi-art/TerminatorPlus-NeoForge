package net.nuggetmc.tplus.utils;

/** Test-only timings. The production checkout is not instrumented. */
public final class PerfProbe {
    public static final int AI = 0, ENTITY = 1, TARGET = 2, ARROW = 3, PEARL = 4, PATH = 5, CHUNKS = 6;
    public static boolean enabled;
    public static int mode;
    public static int tick;
    public static Thread owner;
    public static final long[] nanos = new long[7];
    public static final long[] calls = new long[7];

    private PerfProbe() { }

    public static long begin() { return enabled ? System.nanoTime() : 0; }

    public static void end(int category, long start) {
        if (start == 0) return;
        if (Thread.currentThread() != owner) throw new IllegalStateException("Game method used off the server thread");
        nanos[category] += System.nanoTime() - start;
        calls[category]++;
    }

    public static void clearTick() {
        java.util.Arrays.fill(nanos, 0);
        java.util.Arrays.fill(calls, 0);
    }
}
