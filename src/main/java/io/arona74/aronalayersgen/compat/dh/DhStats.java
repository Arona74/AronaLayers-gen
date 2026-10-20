package io.arona74.aronalayersgen.compat.dh;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;

/**
 * Timings of the exact-height hooks against the DH work they sit in, for {@code /algdebug dh}.
 * Names no DH type, so the command can read it whether or not DH is installed.
 */
public final class DhStats {
    private DhStats() {}

    public static final class Timer {
        final String name;
        final LongAdder calls = new LongAdder();
        final LongAdder nanos = new LongAdder();
        final LongAdder failures = new LongAdder();

        Timer(String name) {
            this.name = name;
        }

        public void add(long startNanos) {
            calls.increment();
            nanos.add(System.nanoTime() - startNanos);
        }

        public void fail() {
            failures.increment();
        }

        public void count() {
            calls.increment();
        }
    }

    /** Switches for {@code /algdebug dh skip}, to measure generation speed without each hook. */
    public static volatile boolean skipRefine = false;
    /** Off switch for the column view trim in mesh builds (ColumnBufferMixin), to compare. */
    public static volatile boolean skipTrim = false;

    private static final List<Timer> TIMERS = new ArrayList<>();

    /** DH turning LOD data into render data (transformFullDataToRenderSource), without our pass. */
    public static final Timer DH_TRANSFORM = timer("DH LOD -> render data");
    /** Our pass on the render data. */
    public static final Timer REFINE = timer("  + our refine");
    /** DH building a section's mesh (makeLodRenderData), our vertex packing included. */
    public static final Timer MESH = timer("DH mesh build");
    /** Of which: texture tile lookups that reached DH's synchronized registry (the rest are cached per build). */
    public static final Timer TEXTURES = timer("  of which: texture lookups");
    /** Of which: DH's greedy quad merge. */
    public static final Timer MERGE = timer("  of which: quad merge");
    /** Vertical slots per column before and after our refine, summed over refined sections; the widest seen. */
    public static final LongAdder SLOTS_BEFORE = new LongAdder();
    public static final LongAdder SLOTS_AFTER = new LongAdder();
    public static final java.util.concurrent.atomic.LongAccumulator SLOTS_MAX = new java.util.concurrent.atomic.LongAccumulator(Math::max, 0);
    /** Layer caps inserted and columns that held at least one, summed over refined sections. */
    public static final LongAdder CAPS = new LongAdder();
    public static final LongAdder CAP_COLUMNS = new LongAdder();
    /** Columns whose cap list grew past DH's slots, and the most caps one column held. */
    public static final java.util.concurrent.atomic.LongAccumulator CAPS_MAX_COLUMN = new java.util.concurrent.atomic.LongAccumulator(Math::max, 0);
    /** Quads left after merging, summed over mesh builds. */
    public static final LongAdder QUADS = new LongAdder();
    /** Block state wrappers DH created (our opacity hook runs on each). */
    public static final Timer WRAPPERS = timer("DH block wrappers created");

    private static volatile long since = System.nanoTime();

    /** Per-thread start time for hooks split across HEAD and RETURN injects. */
    public static final ThreadLocal<long[]> STARTS = ThreadLocal.withInitial(() -> new long[2]);
    public static final int SLOT_TRANSFORM = 0, SLOT_MESH = 1;

    private static Timer timer(String name) {
        Timer timer = new Timer(name);
        TIMERS.add(timer);
        return timer;
    }

    public static List<String> report() {
        double seconds = (System.nanoTime() - since) / 1e9;
        List<String> lines = new ArrayList<>();
        lines.add(String.format("[ALG dh] exact layers %s, last %.0f s (thread time, summed over DH's workers):",
            DhExactFlags.active ? "on" : "off", seconds));
        if (skipRefine || skipTrim) {
            lines.add("  skipping:" + (skipRefine ? " refine" : "") + (skipTrim ? " trim" : ""));
        }
        for (Timer timer : TIMERS) {
            long calls = timer.calls.sum();
            long nanos = timer.nanos.sum();
            long failures = timer.failures.sum();
            if (timer == WRAPPERS) {
                lines.add(String.format("  %-28s %d", timer.name, calls));
                continue;
            }
            lines.add(String.format("  %-28s %8d calls  %9.1f ms total  %7.3f ms each  (%.1f/s)%s",
                timer.name, calls, nanos / 1e6, calls == 0 ? 0.0 : nanos / 1e6 / calls, calls / Math.max(seconds, 1e-9),
                failures == 0 ? "" : "  FAILED " + failures));
        }
        long refined = REFINE.calls.sum();
        if (refined > 0) {
            lines.add(String.format("  slots per column: %.1f before refine, %.1f after (widest %d)",
                SLOTS_BEFORE.sum() / (double) refined, SLOTS_AFTER.sum() / (double) refined, SLOTS_MAX.get()));
            lines.add(String.format("  caps per section: %.0f in %.0f columns (most in one column: %d)",
                CAPS.sum() / (double) refined, CAP_COLUMNS.sum() / (double) refined, CAPS_MAX_COLUMN.get()));
        }
        long builds = MESH.calls.sum();
        lines.add(String.format("  %-28s %d", "quads per mesh build", builds == 0 ? 0 : QUADS.sum() / builds));
        return lines;
    }

    public static void reset() {
        for (Timer timer : TIMERS) {
            timer.calls.reset();
            timer.nanos.reset();
            timer.failures.reset();
        }
        QUADS.reset();
        SLOTS_BEFORE.reset();
        SLOTS_AFTER.reset();
        SLOTS_MAX.reset();
        CAPS.reset();
        CAP_COLUMNS.reset();
        CAPS_MAX_COLUMN.reset();
        since = System.nanoTime();
    }
}
