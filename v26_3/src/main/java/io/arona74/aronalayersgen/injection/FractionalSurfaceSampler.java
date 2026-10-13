package io.arona74.aronalayersgen.injection;

import io.arona74.aronalayersgen.LayerConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;

import java.util.HashMap;
import java.util.Map;

/**
 * 26.3 version of the sub-block surface recovery. Same contract and public API as the shared
 * (pre-26.3) class — see that file for the full derivation: the surface is the zero crossing of
 * {@code finalDensity} between the column's top solid block and the block above it, and the
 * fraction of the top block it fills becomes the layer count.
 *
 * <p>What changed in 26.3, and why this file is so much shorter:
 * <ul>
 *   <li>Density functions are now compiled into {@link DensitySampler}s owned by the
 *       {@link RandomState}; {@code compute(FunctionContext)}, {@code mapAll}, the
 *       {@code MarkerOrMarked}/{@code Interpolated} markers and {@code RandomState.router()} are gone.
 *   <li>Terrain generation no longer interpolates a 4x8x4 cell lattice. {@code NoiseBasedChunkGenerator}
 *       samples {@code settings.noiseRouter().finalDensity()} over a one-block-step
 *       {@code DensityVolume(16, height, 16)}, i.e. at every block. So sampling the same function
 *       pointwise through the same RandomState reproduces exactly what the fill saw — the lattice
 *       reconstruction the old class needed (and its exact-interpolation option) has nothing to
 *       reproduce here.
 * </ul>
 */
public final class FractionalSurfaceSampler {

    /** Returned when the column has no usable zero crossing; callers must fall back. */
    public static final float NO_CROSSING = -1.0f;

    private static final int LAYERS_PER_BLOCK = 8;
    private static final int MAX_PLACED_LAYERS = LAYERS_PER_BLOCK - 1;
    private static final int MAX_DESCENT = 64;
    private static final double MIN_GRADIENT = 1.0e-10;
    private static final float MAX_FRACTION = 0.99999f;

    /** Compiled finalDensity, or null when the generator has no noise router (never describes a surface). */
    private final DensitySampler sampler;

    /** Point samples memoised for the lifetime of this sampler (one chunk). */
    private final Map<Long, Double> cache = new HashMap<>();

    private FractionalSurfaceSampler(DensitySampler sampler) {
        this.sampler = sampler;
    }

    /**
     * Builds a sampler over the generator's own {@code finalDensity}, compiled by the RandomState the
     * fill used. Non-noise generators get a sampler that reports no crossing anywhere.
     */
    public static FractionalSurfaceSampler create(RandomState randomState, ChunkGenerator generator, int worldMinY) {
        if (randomState != null && generator instanceof NoiseBasedChunkGenerator noiseGenerator) {
            try {
                DensityFunction finalDensity = noiseGenerator.generatorSettings().value().noiseRouter().finalDensity();
                return new FractionalSurfaceSampler(randomState.getSampler(finalDensity));
            } catch (Throwable ignored) {
                // Fall through rather than failing generation over a settings read.
            }
        }
        return new FractionalSurfaceSampler(null);
    }

    public float surfaceElevation(int worldX, int worldZ, int startY, int minY) {
        if (sampler == null || startY <= minY) return Float.NaN;

        double above = density(worldX, startY + 1, worldZ);
        if (above > 0.0) return Float.NaN; // carved / structure-cut column

        int steps = 0;
        for (int y = startY; y > minY && steps < MAX_DESCENT; y--, steps++) {
            double here = density(worldX, y, worldZ);
            if (here > 0.0) {
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

    public float surfaceFraction(int worldX, int worldZ, int topSolidY, int minY) {
        float elevation = surfaceElevation(worldX, worldZ, topSolidY, minY);
        if (Float.isNaN(elevation)) return NO_CROSSING;
        return elevation - (float) Math.floor(elevation);
    }

    public static int layersFromFraction(float fraction) {
        int layers = rawLayers(fraction);
        if (LayerConfig.FRACTIONAL_SURFACE_REDUCE_LAYER_COUNT) layers--;
        if (layers < 0) return 0;
        return Math.min(MAX_PLACED_LAYERS, layers);
    }

    public static int fullBlockLayers() {
        return MAX_PLACED_LAYERS;
    }

    /** Diagnostic: 26.3 has no density markers — the router is compiled and sampled per block. */
    public String describeMarkers() {
        return sampler == null
            ? "no noise router (non-noise generator)"
            : "compiled sampler (26.3: no markers, sampled per block like the fill)";
    }

    public static final double[] GRADIENT_BUCKETS = {0.01, 0.02, 0.03, 0.05, 0.10, 0.25, 0.50, 1.00};

    public int gradientBucketOf(int worldX, int worldZ, int topSolidY) {
        double g = verticalGradientAt(worldX, worldZ, topSolidY);
        for (int i = 0; i < GRADIENT_BUCKETS.length; i++) {
            if (g < GRADIENT_BUCKETS[i]) return i;
        }
        return GRADIENT_BUCKETS.length;
    }

    public double verticalGradientAt(int worldX, int worldZ, int topSolidY) {
        return Math.abs(density(worldX, topSolidY, worldZ) - density(worldX, topSolidY + 1, worldZ));
    }

    public boolean isGradientUnreliable(int worldX, int worldZ, int topSolidY) {
        return verticalGradientAt(worldX, worldZ, topSolidY) < LayerConfig.FRACTIONAL_SURFACE_MIN_GRADIENT;
    }

    public double fieldDensityAt(int worldX, int worldY, int worldZ) {
        return density(worldX, worldY, worldZ);
    }

    public boolean isFieldSolid(int worldX, int worldY, int worldZ) {
        return density(worldX, worldY, worldZ) > 0.0;
    }

    public static final int FIELD_RUN_PROBE = 8;
    private static final int MAX_OVERFULL_RUN = 2;

    public boolean isOverfull(int worldX, int worldZ, int topSolidY) {
        int run = fieldSolidRunAbove(worldX, worldZ, topSolidY, FIELD_RUN_PROBE);
        return run > 0 && run <= MAX_OVERFULL_RUN;
    }

    public int fieldSolidRunAbove(int worldX, int worldZ, int topSolidY, int limit) {
        for (int run = 0; run < limit; run++) {
            if (!isFieldSolid(worldX, topSolidY + 1 + run, worldZ)) return run;
        }
        return limit;
    }

    public static int rawLayers(float fraction) {
        return Math.round(fraction * LAYERS_PER_BLOCK);
    }

    private double density(int x, int y, int z) {
        if (sampler == null) return -1.0;
        long key = BlockPos.asLong(x, y, z);
        Double cached = cache.get(key);
        if (cached != null) return cached;
        double value = sampler.sampleValue(SamplerContext.EMPTY_UNCACHED, x, y, z);
        cache.put(key, value);
        return value;
    }
}
