package io.arona74.aronalayersgen;

/**
 * Helpers for the canonical {@code "namespace:path"} id strings that the shared
 * sources pass around.
 *
 * <p>Shared code cannot name Minecraft's id type at all: 1.21.11 renamed
 * {@code ResourceLocation} to {@code Identifier}, and Java has no type aliases.
 * Parsing and registry lookups therefore live in {@link Compat} (which is
 * per-version), while the purely textual operations live here (which is not).
 */
public final class Ids {
    private Ids() {}

    /** Build a canonical id string. */
    public static String of(String namespace, String path) {
        return namespace + ":" + path;
    }

    /** Path part of a canonical id, i.e. everything after the colon. */
    public static String path(String id) {
        if (id == null) return null;
        int colon = id.indexOf(':');
        return colon < 0 ? id : id.substring(colon + 1);
    }

    /** Namespace part of a canonical id; "minecraft" when the id carries no colon. */
    public static String namespace(String id) {
        if (id == null) return null;
        int colon = id.indexOf(':');
        return colon < 0 ? "minecraft" : id.substring(0, colon);
    }
}
