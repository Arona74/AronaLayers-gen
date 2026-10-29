package com.terraformersmc.modmenu.api;

/**
 * Compile-only stand-in for Mod Menu's API interface, so no Mod Menu build is needed per Minecraft
 * version. Excluded from every jar (see each module's build.gradle): at runtime the class comes from
 * Mod Menu itself, and the entrypoint is only loaded when Mod Menu is installed.
 */
public interface ModMenuApi {
    default ConfigScreenFactory<?> getModConfigScreenFactory() {
        return screen -> null;
    }
}
