package io.arona74.aronalayersgen.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import net.fabricmc.loader.api.FabricLoader;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * One key of layer_config.json: its type, default, valid range, where it is shown and when a change
 * takes effect. The value itself stays in a LayerConfig static field (read by hot worldgen paths),
 * which the option reaches through {@link #get()} and {@link #set}.
 *
 * <p>Everything that used to be written out by hand per key — parsing, saving, the file's comments,
 * the default — is derived from this, so a new option is one declaration in LayerConfig plus its
 * label and description in en_us.json.
 */
public final class ConfigOption<T> {

    public enum Kind { BOOLEAN, INT, DOUBLE, ENUM, STRING_LIST }

    /** When a changed value takes effect; shown in the tooltip and the file comment. */
    public enum Apply {
        LIVE,
        NEW_CHUNKS,
        RESTART,
        RESOURCE_RELOAD,
        DH_LODS;

        public String langKey() {
            return "config.aronalayersgen.apply." + name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * Which side reads the value. A client connected to a remote server cannot change SERVER options
     * from its own screen: worldgen runs on the server, against the server's file.
     */
    public enum Side { SERVER, CLIENT, BOTH }

    /** This option only matters while {@code option} has {@code value}. */
    public record Requirement(ConfigOption<?> option, Object value) {}

    public final String key;
    public final Kind kind;
    public final T defaultValue;
    public final ConfigTab tab;
    /** Sub-heading inside the tab, or null. */
    public final String group;
    public final Apply apply;
    public final Side side;
    /** Hidden from the screen unless "Show advanced" is on. */
    public final boolean advanced;
    public final double min;
    public final double max;
    public final double step;
    /** Any one of these mods enables the option; empty means no mod is needed. */
    public final List<String> mods;
    public final List<Requirement> requirements;
    private final T[] enumValues;
    private final Supplier<T> getter;
    private final Consumer<T> setter;
    private Boolean modPresent;

    private ConfigOption(Builder<T> b) {
        this.key = b.key;
        this.kind = b.kind;
        this.defaultValue = b.defaultValue;
        this.tab = b.tab;
        this.group = b.group;
        this.apply = b.apply;
        this.side = b.side;
        this.advanced = b.advanced;
        this.min = b.min;
        this.max = b.max;
        this.step = b.step;
        this.mods = List.copyOf(b.mods);
        this.requirements = List.copyOf(b.requirements);
        this.enumValues = b.enumValues;
        this.getter = b.getter;
        this.setter = b.setter;
    }

    public T get() {
        return getter.get();
    }

    public void set(T value) {
        setter.accept(value);
    }

    public void reset() {
        set(copy(defaultValue));
    }

    public T[] enumValues() {
        return enumValues;
    }

    public String langKey() {
        return "config.aronalayersgen.option." + key;
    }

    public String descriptionKey() {
        return langKey() + ".desc";
    }

    /** True when no mod is needed, or one of the listed mods is loaded. */
    public boolean modPresent() {
        if (modPresent == null) {
            modPresent = mods.isEmpty() || mods.stream().anyMatch(FabricLoader.getInstance()::isModLoaded);
        }
        return modPresent;
    }

    /** Lists are mutable; everything else is immutable and returned as is. */
    @SuppressWarnings("unchecked")
    public T copy(T value) {
        if (kind == Kind.STRING_LIST) return (T) new ArrayList<>((List<String>) value);
        return value;
    }

    // ========== JSON ==========

    /** Parses a file value, clamped into range. Throws on a value of the wrong type. */
    @SuppressWarnings("unchecked")
    public T fromJson(JsonElement element) {
        return switch (kind) {
            case BOOLEAN -> {
                if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
                    throw new IllegalArgumentException("expected true or false");
                }
                yield (T) Boolean.valueOf(element.getAsBoolean());
            }
            case INT -> (T) Integer.valueOf((int) clamp(Math.round(number(element))));
            case DOUBLE -> (T) Double.valueOf(clamp(number(element)));
            case ENUM -> {
                String name = element.getAsString().toUpperCase(Locale.ROOT);
                for (T value : enumValues) {
                    if (((Enum<?>) value).name().equals(name)) yield value;
                }
                throw new IllegalArgumentException("expected one of " + Arrays.toString(enumValues));
            }
            case STRING_LIST -> {
                if (!element.isJsonArray()) throw new IllegalArgumentException("expected a list of block ids");
                List<String> list = new ArrayList<>();
                for (JsonElement entry : element.getAsJsonArray()) list.add(entry.getAsString());
                yield (T) list;
            }
        };
    }

    @SuppressWarnings("unchecked")
    public JsonElement toJson(T value) {
        return switch (kind) {
            case BOOLEAN -> new JsonPrimitive((Boolean) value);
            case INT -> new JsonPrimitive((Integer) value);
            case DOUBLE -> new JsonPrimitive((Double) value);
            case ENUM -> new JsonPrimitive(((Enum<?>) value).name());
            case STRING_LIST -> {
                JsonArray array = new JsonArray();
                ((List<String>) value).forEach(array::add);
                yield array;
            }
        };
    }

    /** The value as the file and tooltips show it. */
    public String format(T value) {
        return toJson(value).toString().replace("\"", "");
    }

    private static double number(JsonElement element) {
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("expected a number");
        }
        return element.getAsDouble();
    }

    private double clamp(double value) {
        return Math.max(min, Math.min(max, value));
    }

    // ========== Builder ==========

    public static Builder<Boolean> bool(String key, boolean def, Supplier<Boolean> get, Consumer<Boolean> set) {
        return new Builder<>(key, Kind.BOOLEAN, def, get, set);
    }

    public static Builder<Integer> integer(String key, int def, int min, int max, Supplier<Integer> get, Consumer<Integer> set) {
        return new Builder<>(key, Kind.INT, def, get, set).range(min, max, 1);
    }

    public static Builder<Double> decimal(String key, double def, double min, double max, double step,
                                          Supplier<Double> get, Consumer<Double> set) {
        return new Builder<>(key, Kind.DOUBLE, def, get, set).range(min, max, step);
    }

    public static <E extends Enum<E>> Builder<E> choice(String key, E def, Supplier<E> get, Consumer<E> set) {
        Builder<E> b = new Builder<>(key, Kind.ENUM, def, get, set);
        b.enumValues = def.getDeclaringClass().getEnumConstants();
        return b;
    }

    public static Builder<List<String>> blockList(String key, List<String> def,
                                                  Supplier<List<String>> get, Consumer<List<String>> set) {
        return new Builder<>(key, Kind.STRING_LIST, List.copyOf(def), get, set);
    }

    public static final class Builder<T> {
        private final String key;
        private final Kind kind;
        private final T defaultValue;
        private final Supplier<T> getter;
        private final Consumer<T> setter;
        private ConfigTab tab;
        private String group;
        private Apply apply = Apply.NEW_CHUNKS;
        private Side side = Side.SERVER;
        private boolean advanced;
        private double min;
        private double max;
        private double step;
        private final List<String> mods = new ArrayList<>();
        private final List<Requirement> requirements = new ArrayList<>();
        private T[] enumValues;

        private Builder(String key, Kind kind, T def, Supplier<T> get, Consumer<T> set) {
            this.key = key;
            this.kind = kind;
            this.defaultValue = def;
            this.getter = get;
            this.setter = set;
        }

        private Builder<T> range(double min, double max, double step) {
            this.min = min;
            this.max = max;
            this.step = step;
            return this;
        }

        public Builder<T> in(ConfigTab tab, String group) {
            this.tab = tab;
            this.group = group;
            return this;
        }

        public Builder<T> apply(Apply apply) {
            this.apply = apply;
            return this;
        }

        public Builder<T> side(Side side) {
            this.side = side;
            return this;
        }

        public Builder<T> advanced() {
            this.advanced = true;
            return this;
        }

        public Builder<T> mods(String... ids) {
            mods.addAll(List.of(ids));
            return this;
        }

        public Builder<T> requires(ConfigOption<Boolean> option) {
            return requires(option, true);
        }

        public Builder<T> requiresOff(ConfigOption<Boolean> option) {
            return requires(option, false);
        }

        public <V> Builder<T> requires(ConfigOption<V> option, V value) {
            requirements.add(new Requirement(option, value));
            return this;
        }

        public ConfigOption<T> build() {
            if (tab == null) throw new IllegalStateException("No tab for option " + key);
            return new ConfigOption<>(this);
        }
    }
}
