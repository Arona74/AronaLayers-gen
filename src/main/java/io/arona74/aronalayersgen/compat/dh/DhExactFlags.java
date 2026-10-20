package io.arona74.aronalayersgen.compat.dh;

/**
 * Whether exact layer heights in Distant Horizons LODs are active (see DhLayerMesh).
 *
 * <p>Set by DhMixinPlugin while mixins load, long before the rest of the mod initialises, so this
 * class has no dependencies. Read by DistantHorizonsCompat: exact heights need the layer blocks
 * kept in DH's data, so the whole-block rounding stands aside when they are on.
 */
public final class DhExactFlags {
    private DhExactFlags() {}

    /** DH is installed at a supported version and dh_exact_layers is on: the DH mixins apply. */
    public static volatile boolean active = false;
}
