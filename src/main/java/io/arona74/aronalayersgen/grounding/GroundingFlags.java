package io.arona74.aronalayersgen.grounding;

/**
 * State the shape mixin reads on every call, kept in a class with no static dependencies.
 *
 * <p>Vanilla queries block shapes while bootstrapping {@code Blocks}, long before mods initialise.
 * Touching PlantGrounding there would initialise LayerConfig, which logs through AronaLayersGen,
 * whose static fields construct our own blocks out of a {@code Blocks} class that has not finished
 * initialising. So nothing is classified until {@link #ready} is set from onInitialize; until then
 * every state answers "not grounded" without caching it.
 */
public final class GroundingFlags {
    private GroundingFlags() {}

    /** Set once the mod has initialised and the config is loaded. */
    public static volatile boolean ready = false;

    /** Bumped when the config reloads, so every state's cached classification is redone. */
    public static volatile int epoch = 1;
}
