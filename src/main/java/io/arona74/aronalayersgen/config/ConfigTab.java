package io.arona74.aronalayersgen.config;

import java.util.Locale;

/**
 * The groups options are sorted into: a tab of the config screen, and a section of layer_config.json.
 * Declaration order is the order of both.
 *
 * <p>Tabs are split by what a change touches. World generation tabs shape the terrain of chunks as
 * they generate, so a change there never reaches chunks already generated and leaves seams in an
 * existing world; the screen opens them with a warning saying so. The others act on the world as
 * it is drawn or played.
 */
public enum ConfigTab {
    WORLDGEN(true),
    STRUCTURES(true),
    VISUALS(false),
    GAMEPLAY(false),
    DEBUG(false);

    /** Shown only when this mod is loaded, or always when null. */
    public final String requiredMod;

    /** Options here shape newly generated terrain (see the class comment). */
    public final boolean worldgen;

    ConfigTab(boolean worldgen) {
        this(worldgen, null);
    }

    ConfigTab(boolean worldgen, String requiredMod) {
        this.worldgen = worldgen;
        this.requiredMod = requiredMod;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String langKey() {
        return "config.aronalayersgen.tab." + id();
    }
}
