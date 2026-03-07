package io.arona74.crlayers.injection;

import io.arona74.crlayers.CRLayers;

/**
 * Holds a reference to the RTFRandomState for use during chunk generation.
 * This is populated by ThreadedAnvilChunkStorageMixin when the world loads.
 */
public class RandomStateHolder {

    private static Object rtfRandomState = null;

    /**
     * Store the RandomState if it implements RTFRandomState.
     */
    public static void setRandomState(Object randomState) {
        if (randomState != null && isRTFRandomState(randomState)) {
            rtfRandomState = randomState;
            CRLayers.LOGGER.info("[RandomStateHolder] Captured RTFRandomState: {}", randomState.getClass().getName());
        }
    }

    /**
     * Get the stored RTFRandomState.
     */
    public static Object getRandomState() {
        return rtfRandomState;
    }

    /**
     * Check if we have an RTFRandomState available.
     */
    public static boolean hasRTFRandomState() {
        return rtfRandomState != null;
    }

    /**
     * Clear the stored state (for world unload).
     */
    public static void clear() {
        rtfRandomState = null;
    }

    private static boolean isRTFRandomState(Object obj) {
        if (obj == null) return false;

        // Check all interfaces
        for (Class<?> iface : obj.getClass().getInterfaces()) {
            if (iface.getName().contains("RTFRandomState")) {
                return true;
            }
        }

        // Check superclass interfaces
        Class<?> superClass = obj.getClass().getSuperclass();
        while (superClass != null && superClass != Object.class) {
            for (Class<?> iface : superClass.getInterfaces()) {
                if (iface.getName().contains("RTFRandomState")) {
                    return true;
                }
            }
            superClass = superClass.getSuperclass();
        }

        return false;
    }
}
