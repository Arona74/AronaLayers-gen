package io.arona74.aronalayersgen.injection;

import net.minecraft.world.level.levelgen.RandomState;

/**
 * Produces a synthetic cell height in [0..1] for each XZ position, analogous to
 * RTF's cell.height, using vanilla data available during chunk generation.
 *
 * Two signals combined:
 *   - surfaceNorm: terrain surface Y normalized to [0..1] by world height.
 *   - positionNoise: smooth bilinear value noise at 128-block scale, seeded from
 *                    world seed so each world has a unique pattern.
 *
 * cellHeight = surfaceNorm * 0.4 + positionNoise * 0.6
 *
 * <p>Despite the name and the {@code RandomState} constructor parameter, this does
 * <em>not</em> read vanilla's density functions: the parameter is unused, and
 * positionNoise is an independent seeded hash. So the dominant 60% of the result is
 * a decorative pattern uncorrelated with the landscape, while surfaceNorm changes by
 * only ~0.0026 per block over a 384-block world — meaning terrain alone needs roughly
 * a 48-block elevation change to complete one layer cycle at GRADIENT_SCALE=8. The
 * banding it produces is therefore mostly noise-driven rather than terrain-driven.
 *
 * <p>{@link FractionalSurfaceSampler} is the terrain-accurate alternative: it reads
 * the real density field and recovers actual sub-block elevation. This class is kept
 * for the existing vanilla_noise_router_layer_injection behaviour, and as the
 * per-column fallback for columns whose surface the density field cannot describe.
 */
public class VanillaCellHeightSampler {

    static final float SURFACE_WEIGHT = 0.4f;
    static final float NOISE_WEIGHT   = 0.6f;

    // 128-block feature size gives broader, more geological strata zones.
    private static final float NOISE_SCALE = 128.0f;
    private static final float INV_SCALE   = 1.0f / NOISE_SCALE;

    // World seed mixed into every hash so each world has a unique pattern.
    private final long seed;

    /** @param noiseConfig unused; kept so call sites need not change if this ever samples the router. */
    public VanillaCellHeightSampler(RandomState noiseConfig, long worldSeed) {
        this.seed = worldSeed;
    }

    /**
     * Returns a synthetic cell height in [0..1] combining normalized surface Y and
     * smooth position noise.
     *
     * @param floorY      ocean-floor surface Y (block below the injection candidate)
     * @param worldBottom chunk.getBottomY() (−64 in vanilla 1.18+)
     * @param worldHeight chunk.getTopY() − chunk.getBottomY() (384 in vanilla 1.18+)
     */
    public float getCellHeight(int x, int z, int floorY, int worldBottom, int worldHeight) {
        float surfaceNorm    = clamp01((float)(floorY - worldBottom) / worldHeight);
        float noiseVariation = getPositionNoise(x, z);
        return surfaceNorm * SURFACE_WEIGHT + noiseVariation * NOISE_WEIGHT;
    }

    /** Smooth value noise in [0..1] at NOISE_SCALE-block feature size. */
    public float getPositionNoise(int x, int z) {
        float nx = x * INV_SCALE;
        float nz = z * INV_SCALE;
        int   ix = floorInt(nx), iz = floorInt(nz);
        float fx = nx - ix,      fz = nz - iz;

        // Cubic smoothstep
        fx = fx * fx * (3.0f - 2.0f * fx);
        fz = fz * fz * (3.0f - 2.0f * fz);

        float v00 = hash2d(ix,     iz);
        float v10 = hash2d(ix + 1, iz);
        float v01 = hash2d(ix,     iz + 1);
        float v11 = hash2d(ix + 1, iz + 1);

        return lerp(lerp(v00, v10, fx), lerp(v01, v11, fx), fz);
    }

    private float hash2d(int x, int z) {
        long h = (long) x * 0x9E3779B97F4A7C15L ^ (long) z * 0x6C62272E07BB0142L ^ seed;
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= (h >>> 31);
        return (h & 0x7FFFFFFFL) * (1.0f / 0x80000000L);
    }

    private static float lerp(float a, float b, float t)  { return a + (b - a) * t; }
    private static int   floorInt(float v)                 { return v < 0 ? (int) v - 1 : (int) v; }
    private static float clamp01(float v)                  { return v < 0f ? 0f : (v > 1f ? 1f : v); }
}
