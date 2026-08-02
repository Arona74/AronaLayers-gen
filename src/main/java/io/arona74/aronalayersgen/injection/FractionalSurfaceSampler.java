package io.arona74.aronalayersgen.injection;

import io.arona74.aronalayersgen.LayerConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;

import java.util.HashMap;
import java.util.Map;

/**
 * Recovers the sub-block surface elevation that terrain generation throws away.
 *
 * <p>Vanilla terrain is not a heightmap: it is a continuous scalar field,
 * {@code finalDensity(x, y, z)}, that is solid where the value is positive and air
 * where it is not. The surface is the isosurface at zero, and a block is placed
 * wherever that field happens to be positive at an integer coordinate — so the
 * blocky surface is the field rounded up to the next whole block. The rounding is
 * the only thing that loses precision, and the field is still there afterwards, so
 * the discarded fraction can simply be read back.
 *
 * <p>For a column whose highest solid block is {@code topSolidY}, the field is
 * positive at {@code topSolidY} and non-positive at {@code topSolidY + 1}, so it
 * crosses zero somewhere between them. Interpolating the crossing gives
 * {@code t = d(topSolidY) / (d(topSolidY) − d(topSolidY + 1))}, and the true
 * surface sits at {@code topSolidY + t}.
 *
 * <p>{@code t} is what makes a step-free surface possible. Walking up a slope it
 * climbs 0 → 1, then the solid block steps up by one and it resets to 0, so
 * {@code layers = round(t * 8)} placed on top of the block yields a rendered height
 * of {@code topSolidY + 1 + t} — the real surface plus a constant one-block offset.
 * Constant offsets are invisible; the 1-block staircase they replace is not.
 *
 * <p>One aesthetic adjustment sits on top of that geometry, in {@link #layersFromFraction}:
 * counts are reduced by one, which keeps the top of the range at 7 — so no layer fills its
 * block and reads as solid terrain — and leaves the shallowest columns bare. It shifts the
 * offset to {@code ~0.875} and is a deliberate look, not a property of the derivation above.
 *
 * <p>Two caveats are worth knowing, because both are load-bearing for the guards below:
 *
 * <ul>
 *   <li><b>The field is not a distance function.</b> Its gradient varies with
 *       jaggedness, squeeze and the slide clamps, so {@code t} is monotonic in real
 *       elevation but not proportional to it. Layer thickness is therefore slightly
 *       biased rather than perfectly uniform. That is an aesthetic wobble, not an error.
 *   <li><b>Blocks were placed from an interpolated field.</b> Vanilla evaluates density
 *       only at the corners of 4×8×4 cells and trilinearly interpolates between them,
 *       while {@link DensityFunction#compute} returns the exact value. Sampling the exact
 *       field was measurably wrong in practice: on visibly flat beach the fractions swung
 *       half a block between adjacent columns, because the raw field still carries 3D
 *       noise that vanilla's lattice smooths away. This class therefore reproduces the
 *       lattice — see {@link #density} — rather than reading the field pointwise. Queries
 *       are still validated against the heightmap, since carvers and features run later.
 * </ul>
 *
 * <p>The validation doubles as free feature rejection. Anything the density field did
 * not create — a tree counted by {@code OCEAN_FLOOR}, a carved ravine, a structure —
 * fails the sign test at the heightmap top, so those columns are either resolved by
 * descending to the real ground or reported as {@link #NO_CROSSING} for the caller to
 * handle, and never silently mistaken for terrain.
 *
 * <p>Meaningful only for vanilla worldgen. ReTerraForged and Tellus replace the
 * router with their own pipeline, so callers must not use this there.
 */
public final class FractionalSurfaceSampler {

    /** Returned when the column has no usable zero crossing; callers must fall back. */
    public static final float NO_CROSSING = -1.0f;

    /** Layer granularity: 8 layers to a block, matching the layer block states. */
    private static final int LAYERS_PER_BLOCK = 8;

    /** A placed layer must never fill its block, or it reads as solid terrain rather than detail. */
    private static final int MAX_PLACED_LAYERS = LAYERS_PER_BLOCK - 1;

    /**
     * How far to descend looking for real ground under a feature-inflated heightmap.
     * Matches the foliage guard in {@link LayerPlacementHelper}, which walks down past
     * canopies for the same reason.
     */
    private static final int MAX_DESCENT = 64;

    /** Guards the division against a denormal gradient. The sign tests make it positive. */
    private static final double MIN_GRADIENT = 1.0e-10;

    /** Keeps t strictly below 1 so it stays a fraction of the block it belongs to. */
    private static final float MAX_FRACTION = 0.99999f;

    /** Cell dimensions when the generator's real NoiseSettings cannot be read (vanilla overworld values). */
    private static final int DEFAULT_CELL_WIDTH  = 4;
    private static final int DEFAULT_CELL_HEIGHT = 8;

    private final DensityFunction finalDensity;

    /**
     * What {@link #density} evaluates.
     *
     * <p>In exact mode this is the router with each {@code Interpolated} marker replaced by a
     * cell interpolator, matching how NoiseChunk builds it: vanilla interpolates several separate
     * sub-expressions and combines them with operations evaluated per block. Otherwise it is the
     * router itself, and the whole tree is interpolated as one unit.
     */
    private final DensityFunction sampleRoot;

    /** Whether {@link #sampleRoot} already applies interpolation internally. */
    private final boolean exactInterpolation;

    /** Interpolation lattice, mirroring the one NoiseChunk fills during generation. */
    private final int cellWidth;
    private final int cellHeight;
    private final int cellOriginY;

    /**
     * Corner samples, memoised for the lifetime of this sampler (one chunk).
     *
     * <p>Corners are shared by the eight cells meeting at them and by every column inside
     * each cell, so a 16x16 chunk needs on the order of 75 evaluations rather than two per
     * column — the interpolated path is cheaper than the direct one it replaces.
     */
    private final Map<Long, Double> cornerCache = new HashMap<>();

    public FractionalSurfaceSampler(RandomState randomState, int cellWidth, int cellHeight, int cellOriginY) {
        this.finalDensity = randomState.router().finalDensity();
        this.cellWidth    = Math.max(1, cellWidth);
        this.cellHeight   = Math.max(1, cellHeight);
        this.cellOriginY  = cellOriginY;
        this.exactInterpolation = LayerConfig.FRACTIONAL_SURFACE_EXACT_INTERPOLATION;
        this.sampleRoot = exactInterpolation
                ? wrapInterpolatedMarkers(finalDensity, this.cellWidth, this.cellHeight, this.cellOriginY)
                : finalDensity;
    }

    /**
     * Rebuilds the router with each {@code Interpolated} marker replaced by a cell interpolator.
     *
     * <p>This is what NoiseChunk does, and the difference from interpolating the whole tree is not
     * cosmetic. A measured world carries five Interpolated markers, so vanilla interpolates five
     * separate sub-expressions and evaluates everything combining them per block. Where those
     * combining operations are nonlinear — clamps, squeeze, multiplication, the slides —
     * {@code interp(f(a,b))} and {@code f(interp(a), interp(b))} are different functions. On gentle
     * terrain the two agree closely; on peaks, where the nonlinearities bite hardest, whole-tree
     * interpolation put the surface one to three blocks above the placed world.
     *
     * <p>The other marker types are left alone deliberately: Cache2D, CacheOnce and CacheAllInCell
     * are pure memoisation with no effect on a pointwise result, and FlatCache quantises to quart
     * resolution, which is a no-op at the cell corners this samples.
     */
    private static DensityFunction wrapInterpolatedMarkers(DensityFunction root,
                                                           int cellWidth, int cellHeight, int cellOriginY) {
        return root.mapAll(fn -> {
            if (fn instanceof DensityFunctions.MarkerOrMarked marker
                    && "Interpolated".equals(markerTypeName(marker))) {
                return new CellInterpolated(marker.wrapped(), cellWidth, cellHeight, cellOriginY);
            }
            return fn;
        });
    }

    /**
     * Builds a sampler on the generator's own interpolation lattice.
     *
     * <p>Datapacks can change {@code size_horizontal}/{@code size_vertical}, so the cell
     * dimensions are read from the generator rather than assumed; guessing wrong would
     * misalign every cell and reintroduce exactly the jitter this interpolation removes.
     * Falls back to the vanilla overworld 4x8 lattice for non-noise generators.
     */
    public static FractionalSurfaceSampler create(RandomState randomState, ChunkGenerator generator, int worldMinY) {
        if (generator instanceof NoiseBasedChunkGenerator noiseGenerator) {
            try {
                NoiseSettings settings = noiseGenerator.generatorSettings().value().noiseSettings();
                return new FractionalSurfaceSampler(randomState,
                        settings.getCellWidth(), settings.getCellHeight(), settings.minY());
            } catch (Throwable ignored) {
                // Fall through to defaults rather than failing generation over a settings read.
            }
        }
        return new FractionalSurfaceSampler(randomState, DEFAULT_CELL_WIDTH, DEFAULT_CELL_HEIGHT, worldMinY);
    }

    /**
     * Absolute surface elevation for a column, or {@link Float#NaN} when the field does not
     * describe this column's surface.
     *
     * <p>Costs two density evaluations on an ordinary column. Descending only happens when
     * the field agrees that the start height is not terrain, which is the tree-and-structure
     * case, and bails immediately on carved columns.
     *
     * <p>{@code startY} may safely be higher than the real terrain — the descent walks down
     * to the first solid reading — but must not be below it, since a start inside rock reads
     * as carved and returns NaN. Callers probing outside a chunk, where no heightmap is
     * available, should therefore start deliberately high.
     *
     * @param startY where to begin looking, normally the column's top solid block
     * @param minY   world bottom, from {@code Compat.minY}
     */
    public float surfaceElevation(int worldX, int worldZ, int startY, int minY) {
        if (startY <= minY) return Float.NaN;

        double above = density(worldX, startY + 1, worldZ);

        // Positive above the surface means the field still calls that block solid while
        // the world does not: a carver or a structure cut the column after the fill step.
        // The field describes terrain that no longer exists here, so descending would only
        // burn evaluations walking through rock that reads solid all the way down.
        if (above > 0.0) return Float.NaN;

        int steps = 0;
        for (int y = startY; y > minY && steps < MAX_DESCENT; y--, steps++) {
            double here = density(worldX, y, worldZ);

            if (here > 0.0) {
                // First solid reading with air above it: the crossing is in this block.
                double gradient = here - above;
                if (gradient < MIN_GRADIENT) return Float.NaN;
                double t = here / gradient;
                if (t > MAX_FRACTION) t = MAX_FRACTION;
                return (float) (y + t);
            }

            above = here;
        }

        return Float.NaN;
    }

    /**
     * Fraction of {@code topSolidY} that the terrain field actually fills, in [0, 1).
     *
     * @return the fraction, or {@link #NO_CROSSING} if the field does not describe
     *         this column's surface
     */
    public float surfaceFraction(int worldX, int worldZ, int topSolidY, int minY) {
        float elevation = surfaceElevation(worldX, worldZ, topSolidY, minY);
        if (Float.isNaN(elevation)) return NO_CROSSING;
        return elevation - (float) Math.floor(elevation);
    }

    /**
     * Layer count for a fraction from {@link #surfaceFraction}, in [0, 7].
     *
     * <p>With {@code fractional_surface_reduce_layer_count} on, the raw count is reduced by one,
     * matching the noise-router and RTF paths: the bottom blanks {@code round == 1} as well as
     * {@code round == 0}, so columns with {@code t < 3/16} stay bare — roughly 19% of them — and
     * the rendered surface sits a constant {@code ~0.875} above the true one rather than a clean
     * {@code 1.0}. Off, every column with any fill gets a layer and the offset is a clean
     * {@code 1.0}, at the cost of near-total coverage and thicker layers throughout.
     *
     * <p>Either way the result is capped so a layer never fills its whole block, since a
     * full-height layer is indistinguishable from solid ground. That cap is independent of the
     * reduction, which is why turning the reduction off does not bring block-height layers back.
     *
     * <p>No terrain-shape exceptions. Two were tried — suppressing steep slopes, then columns
     * standing above their neighbours — and neither caught the isolated blocks they were aimed
     * at, because the cause was never the count. It was a fraction measured in a different block
     * from the one being layered; callers guard that by comparing the crossing block against the
     * block the layer sits on. The second rule went on to bare a correct column and open a step
     * of its own, which is what a heuristic layered over a misdiagnosis tends to do.
     */
    public static int layersFromFraction(float fraction) {
        int layers = rawLayers(fraction);
        if (LayerConfig.FRACTIONAL_SURFACE_REDUCE_LAYER_COUNT) layers--;
        if (layers < 0) return 0;
        return Math.min(MAX_PLACED_LAYERS, layers);
    }

    /**
     * The count for a block the field fills completely, i.e. a fraction of 1.
     *
     * <p>Used where the field puts the surface above the block being layered rather than
     * inside it. There is no partial fill to measure — the block is full — so the layer takes
     * the top of the range and the column sits level with its neighbours.
     */
    public static int fullBlockLayers() {
        return MAX_PLACED_LAYERS;
    }

    /**
     * Describes the marker structure of the density tree. Diagnostic only.
     *
     * <p>Vanilla's NoiseChunk does not evaluate the router pointwise: it walks the tree and
     * replaces each marker with a caching or interpolating wrapper — Interpolated becomes a
     * trilinear interpolator over the cell lattice, FlatCache holds 2D terms at quart resolution,
     * the rest are pure memoisation. This class reproduces only the outermost interpolation, on
     * the assumption that the root is an Interpolated marker and everything below it is either
     * pure caching or already quart-aligned at the corners we sample.
     *
     * <p>That assumption is what this reports on. If the root is not Interpolated, or markers
     * appear that change values rather than merely cache them, the reconstruction is structurally
     * wrong rather than merely imprecise — which would explain an error that survives on terrain
     * with a flat vertical gradient.
     */
    public String describeMarkers() {
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        finalDensity.mapAll(fn -> {
            if (fn instanceof DensityFunctions.MarkerOrMarked marker) {
                counts.merge(markerTypeName(marker), 1, Integer::sum);
            }
            return fn;
        });
        String root = (finalDensity instanceof DensityFunctions.MarkerOrMarked m)
                ? markerTypeName(m)
                : finalDensity.getClass().getSimpleName() + "(not a marker)";
        return "root=" + root + " markers=" + counts;
    }

    /**
     * Marker type name, read reflectively.
     *
     * <p>{@code Marker.Type} is a public enum nested inside a package-private class, so it cannot
     * be named from here even though {@code MarkerOrMarked.type()} is public. The method handle
     * comes from the public interface, so no access override is needed.
     */
    private static String markerTypeName(Object marker) {
        try {
            // The method cannot be looked up by name: the mod compiles against Mojang mappings but
            // runs against intermediary, so "type" does not exist at runtime. Find it by shape
            // instead — the only zero-argument method on the interface returning an enum.
            for (java.lang.reflect.Method m : DensityFunctions.MarkerOrMarked.class.getMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType().isEnum()) {
                    Object type = m.invoke(marker);
                    return (type instanceof Enum<?> e) ? e.name() : String.valueOf(type);
                }
            }
        } catch (Throwable ignored) {
            // Diagnostic only; never fail generation over it.
        }
        return "unknown";
    }

    /**
     * Distribution of vertical gradients across a chunk, for choosing the reliability threshold.
     *
     * <p>The threshold separating terrain whose surface is well determined from terrain where it
     * is not cannot be reasoned about from first principles — it depends on how steeply density
     * falls through the surface, which varies by biome and elevation. Measuring the distribution
     * on terrain known to work and terrain known not to gives the separation directly.
     */
    public static final double[] GRADIENT_BUCKETS = {0.01, 0.02, 0.03, 0.05, 0.10, 0.25, 0.50, 1.00};

    public int gradientBucketOf(int worldX, int worldZ, int topSolidY) {
        double g = verticalGradientAt(worldX, worldZ, topSolidY);
        for (int i = 0; i < GRADIENT_BUCKETS.length; i++) {
            if (g < GRADIENT_BUCKETS[i]) return i;
        }
        return GRADIENT_BUCKETS.length;
    }

    /**
     * Vertical density change per block at the column's surface.
     *
     * <p>This is the denominator the crossing is divided by, so it is exactly what determines how
     * far a given error in the field moves the computed surface. A large value means the surface
     * is pinned down tightly; a small one means it is barely determined at all.
     */
    public double verticalGradientAt(int worldX, int worldZ, int topSolidY) {
        return Math.abs(density(worldX, topSolidY, worldZ) - density(worldX, topSolidY + 1, worldZ));
    }

    /**
     * Whether the field is too flat here to say where the surface is.
     *
     * <p>Consulted only on columns where the field and the placed world already disagree, so it
     * cannot take layers away from terrain that produced a valid crossing.
     */
    public boolean isGradientUnreliable(int worldX, int worldZ, int topSolidY) {
        return verticalGradientAt(worldX, worldZ, topSolidY) < LayerConfig.FRACTIONAL_SURFACE_MIN_GRADIENT;
    }

    /**
     * Raw interpolated field value at a position. Diagnostic only.
     *
     * <p>The magnitude distinguishes a marginal disagreement from a structural one: a value just
     * over zero where the world has no block means the lattice and the placed world are splitting
     * hairs, while a firmly positive one means they disagree about the terrain itself.
     */
    public double fieldDensityAt(int worldX, int worldY, int worldZ) {
        return density(worldX, worldY, worldZ);
    }

    /** Whether the field calls this position solid, regardless of what block the world has. */
    public boolean isFieldSolid(int worldX, int worldY, int worldZ) {
        return density(worldX, worldY, worldZ) > 0.0;
    }

    /** How far above the ground to probe the field when classifying a column with no crossing. */
    public static final int FIELD_RUN_PROBE = 8;

    /**
     * How far the field may overshoot the world's ground and still count as a full block.
     *
     * <p>Two, because that is what the terrain shows. A jagged-peaks chunk measured 65 of its 67
     * crossing-less columns at a run of exactly two, one at three, one at five, and none at eight
     * or more — no carving, just the field sitting a block or two above the placed world, the same
     * disagreement that produced the standing columns and the pits. A single-block tolerance sent
     * all 65 to the noise-router fallback, whose height is mostly seeded noise, and the bands that
     * produced were the visible artifact on the slopes.
     *
     * <p>Kept small so genuine carving still falls back: a cave or ravine leaves the field solid
     * for many blocks, well clear of this.
     */
    private static final int MAX_OVERFULL_RUN = 2;

    /**
     * Whether the field overshoots the world's ground by a block or two rather than describing
     * terrain that was carved away.
     *
     * <p>Either way there is no crossing at the anchor, because the field calls the block above
     * the ground solid. The difference is depth: a short run means the block below is simply full
     * and deserves the top of the range, a long one means the world removed terrain the field
     * still describes, where no sub-block height can be recovered.
     */
    public boolean isOverfull(int worldX, int worldZ, int topSolidY) {
        int run = fieldSolidRunAbove(worldX, worldZ, topSolidY, FIELD_RUN_PROBE);
        return run > 0 && run <= MAX_OVERFULL_RUN;
    }

    /**
     * How many blocks above {@code topSolidY} the field keeps calling solid, up to {@code limit}.
     *
     * <p>Separates the two reasons a column can have no crossing. A one- or two-block run is the
     * field disagreeing with the placed world about where the surface sits, and the block below is
     * simply full. A long run means the world removed terrain the field still describes — a carver
     * or a structure — and no sub-block height can be recovered there.
     */
    public int fieldSolidRunAbove(int worldX, int worldZ, int topSolidY, int limit) {
        for (int run = 0; run < limit; run++) {
            if (!isFieldSolid(worldX, topSolidY + 1 + run, worldZ)) return run;
        }
        return limit;
    }

    /** The unadjusted count, 0..8. Exposed so callers can report what the rules acted on. */
    public static int rawLayers(float fraction) {
        return Math.round(fraction * LAYERS_PER_BLOCK);
    }

    /**
     * Caps a count so it can never fill its whole block.
     *
     * <p>{@link #layersFromFraction} already lands at or below this by construction; the cap
     * exists for the noise-router fallback, which skips its own reduction on snowy columns
     * and could otherwise emit a full-height layer.
     */
    public static int capPlacedLayers(int layers) {
        return Math.min(MAX_PLACED_LAYERS, layers);
    }

    /**
     * Density as generation saw it: sampled at the corners of the enclosing cell and
     * trilinearly interpolated.
     *
     * <p>Calling {@link DensityFunction#compute} directly returns the exact field, which
     * still carries the high-frequency 3D noise that vanilla's 4x8x4 lattice smooths away.
     * Reading that detail produced surface fractions swinging half a block between adjacent
     * columns on visibly flat ground — detail the blocks never contained. Interpolating the
     * same way vanilla did makes the surface piecewise-linear within each cell, which is
     * what turns the fractions into usable gradients.
     */
    private double density(int x, int y, int z) {
        if (exactInterpolation) {
            return sampleRoot.compute(new DensityFunction.SinglePointContext(x, y, z));
        }
        int x0 = Math.floorDiv(x, cellWidth) * cellWidth;
        int z0 = Math.floorDiv(z, cellWidth) * cellWidth;
        int y0 = cellOriginY + Math.floorDiv(y - cellOriginY, cellHeight) * cellHeight;

        int x1 = x0 + cellWidth;
        int y1 = y0 + cellHeight;
        int z1 = z0 + cellWidth;

        double fx = (double) (x - x0) / cellWidth;
        double fy = (double) (y - y0) / cellHeight;
        double fz = (double) (z - z0) / cellWidth;

        double d00 = lerp(fx, corner(x0, y0, z0), corner(x1, y0, z0));
        double d10 = lerp(fx, corner(x0, y1, z0), corner(x1, y1, z0));
        double d01 = lerp(fx, corner(x0, y0, z1), corner(x1, y0, z1));
        double d11 = lerp(fx, corner(x0, y1, z1), corner(x1, y1, z1));

        return lerp(fz, lerp(fy, d00, d10), lerp(fy, d01, d11));
    }

    private double corner(int x, int y, int z) {
        long key = BlockPos.asLong(x, y, z);
        Double cached = cornerCache.get(key);
        if (cached != null) return cached;
        double value = finalDensity.compute(new DensityFunction.SinglePointContext(x, y, z));
        cornerCache.put(key, value);
        return value;
    }

    private static double lerp(double t, double a, double b) {
        return a + t * (b - a);
    }

    /**
     * Trilinear interpolation over the generator's cell lattice, applied to one sub-expression.
     *
     * <p>Corner values are memoised for the lifetime of the enclosing sampler, which is one chunk.
     * Corners are shared between the eight cells meeting at them and by every column inside each
     * cell, so the cost stays close to a pointwise evaluation despite sampling eight per query.
     */
    private static final class CellInterpolated implements DensityFunction.SimpleFunction {
        private final DensityFunction wrapped;
        private final int cellWidth;
        private final int cellHeight;
        private final int cellOriginY;
        private final Map<Long, Double> corners = new HashMap<>();

        CellInterpolated(DensityFunction wrapped, int cellWidth, int cellHeight, int cellOriginY) {
            this.wrapped     = wrapped;
            this.cellWidth   = cellWidth;
            this.cellHeight  = cellHeight;
            this.cellOriginY = cellOriginY;
        }

        @Override
        public double compute(DensityFunction.FunctionContext context) {
            int x = context.blockX();
            int y = context.blockY();
            int z = context.blockZ();

            int x0 = Math.floorDiv(x, cellWidth) * cellWidth;
            int z0 = Math.floorDiv(z, cellWidth) * cellWidth;
            int y0 = cellOriginY + Math.floorDiv(y - cellOriginY, cellHeight) * cellHeight;

            int x1 = x0 + cellWidth;
            int y1 = y0 + cellHeight;
            int z1 = z0 + cellWidth;

            double fx = (double) (x - x0) / cellWidth;
            double fy = (double) (y - y0) / cellHeight;
            double fz = (double) (z - z0) / cellWidth;

            double d00 = lerp(fx, corner(x0, y0, z0), corner(x1, y0, z0));
            double d10 = lerp(fx, corner(x0, y1, z0), corner(x1, y1, z0));
            double d01 = lerp(fx, corner(x0, y0, z1), corner(x1, y0, z1));
            double d11 = lerp(fx, corner(x0, y1, z1), corner(x1, y1, z1));

            return lerp(fz, lerp(fy, d00, d10), lerp(fy, d01, d11));
        }

        private double corner(int x, int y, int z) {
            long key = BlockPos.asLong(x, y, z);
            Double cached = corners.get(key);
            if (cached != null) return cached;
            double value = wrapped.compute(new DensityFunction.SinglePointContext(x, y, z));
            corners.put(key, value);
            return value;
        }

        @Override public double minValue() { return wrapped.minValue(); }
        @Override public double maxValue() { return wrapped.maxValue(); }

        @Override
        public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            throw new UnsupportedOperationException("sampling wrapper is never serialised");
        }
    }
}
