package io.arona74.aronalayersgen.injection;

import io.arona74.aronalayersgen.AronaLayersGen;
import net.minecraft.world.gen.noise.NoiseConfig;

/**
 * Holds world-generation state for use during chunk generation.
 * Populated by ThreadedAnvilChunkStorageMixin when the world loads.
 */
public class RandomStateHolder {

    private static Object rtfRandomState = null;
    private static NoiseConfig vanillaNoiseConfig = null;
    private static long worldSeed = Long.MIN_VALUE;

    public static void setNoiseConfig(NoiseConfig noiseConfig) {
        vanillaNoiseConfig = noiseConfig;
    }

    public static void setWorldSeed(long seed) {
        worldSeed = seed;
        AronaLayersGen.LOGGER.info("[RandomStateHolder] Captured world seed: {}", seed);
    }

    public static long getWorldSeed() {
        return worldSeed;
    }

    public static NoiseConfig getNoiseConfig() {
        return vanillaNoiseConfig;
    }

    public static void setRandomState(Object randomState) {
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
