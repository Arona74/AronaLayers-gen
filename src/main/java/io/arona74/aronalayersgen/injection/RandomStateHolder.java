package io.arona74.aronalayersgen.injection;

import io.arona74.aronalayersgen.AronaLayersGen;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * Holds world-generation state for use during chunk generation.
 * Populated by ThreadedAnvilChunkStorageMixin when the world loads.
 */
public class RandomStateHolder {

    private static Object rtfRandomState = null;
    private static RandomState vanillaNoiseConfig = null;
    private static long worldSeed = Long.MIN_VALUE;

    /**
     * Captures the vanilla RandomState, keeping only the first.
     *
     * <p>ChunkMap is constructed once per dimension — Overworld, then Nether, then End —
     * so overwriting on every call left this holding the <em>End's</em> RandomState, whose
     * density functions describe End terrain. Evaluating those at Overworld coordinates
     * makes the field disagree with the ground everywhere.
     *
     * <p>This went unnoticed for a long time because the only consumer back then was a
     * cell-height sampler that ignored the RandomState entirely; {@link FractionalSurfaceSampler}
     * is the first to actually read it. Same keep-the-first rule as {@link #setRandomState}, and
     * for the same reason — see the note there.
     *
     * <p>Prefer {@link #noiseConfigFor(ServerLevel)}: this global is only a fallback for
     * callers with no level in hand.
     */
    public static void setNoiseConfig(RandomState noiseConfig) {
        if (vanillaNoiseConfig != null) {
            AronaLayersGen.LOGGER.debug("[RandomStateHolder] Ignoring additional RandomState (keeping the first, Overworld's)");
            return;
        }
        vanillaNoiseConfig = noiseConfig;
    }

    /**
     * The RandomState of the level actually being generated.
     *
     * <p>Dimension-correct by construction, unlike the global above, which can only ever
     * hold one dimension's. Falls back to the global when no level is available.
     */
    public static RandomState noiseConfigFor(ServerLevel level) {
        if (level == null) return vanillaNoiseConfig;
        try {
            RandomState state = level.getChunkSource().randomState();
            if (state != null) return state;
        } catch (Throwable t) {
            AronaLayersGen.LOGGER.debug("[RandomStateHolder] Could not read RandomState from level, using captured one", t);
        }
        return vanillaNoiseConfig;
    }

    public static void setWorldSeed(long seed) {
        worldSeed = seed;
        AronaLayersGen.LOGGER.info("[RandomStateHolder] Captured world seed: {}", seed);
    }

    public static long getWorldSeed() {
        return worldSeed;
    }

    public static RandomState getNoiseConfig() {
        return vanillaNoiseConfig;
    }

    public static void setRandomState(Object randomState) {
        if (rtfRandomState != null) {
            // Only keep the first RTFRandomState captured. On NeoForge, ChunkMap is constructed
            // for every dimension (Overworld first, then Nether, End). ETcomehome only creates
            // generatorContext for the Overworld (reterraforged$isRTFDimension = true), so later
            // dimensions would overwrite the Overworld's state with a null-generatorContext one.
            AronaLayersGen.LOGGER.debug("[RandomStateHolder] Ignoring additional RTFRandomState (keeping Overworld's): {}", randomState.getClass().getName());
            return;
        }
        if (randomState != null && isRTFRandomState(randomState)) {
            rtfRandomState = randomState;
            AronaLayersGen.LOGGER.info("[RandomStateHolder] Captured RTFRandomState: {}", randomState.getClass().getName());
        }
    }

    public static Object getRandomState() {
        return rtfRandomState;
    }

    public static boolean hasRTFRandomState() {
        return rtfRandomState != null;
    }

    public static void clear() {
        rtfRandomState = null;
        vanillaNoiseConfig = null;
        worldSeed = Long.MIN_VALUE;
    }

    private static boolean isRTFRandomState(Object obj) {
        if (obj == null) return false;

        for (Class<?> iface : obj.getClass().getInterfaces()) {
            if (iface.getName().contains("RTFRandomState")) return true;
        }

        Class<?> superClass = obj.getClass().getSuperclass();
        while (superClass != null && superClass != Object.class) {
            for (Class<?> iface : superClass.getInterfaces()) {
                if (iface.getName().contains("RTFRandomState")) return true;
            }
            superClass = superClass.getSuperclass();
        }

        return false;
    }
}
