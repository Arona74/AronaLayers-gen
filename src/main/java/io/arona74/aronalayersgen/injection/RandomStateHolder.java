package io.arona74.aronalayersgen.injection;

import io.arona74.aronalayersgen.AronaLayersGen;

/**
 * Holds a reference to the RTFRandomState for use during chunk generation.
 * Populated by ThreadedAnvilChunkStorageMixin when the world loads.
 */
public class RandomStateHolder {

    private static Object rtfRandomState = null;

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
