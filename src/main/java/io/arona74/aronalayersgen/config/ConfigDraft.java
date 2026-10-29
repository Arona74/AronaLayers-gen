package io.arona74.aronalayersgen.config;

import io.arona74.aronalayersgen.LayerConfig;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Edits made in the config screen, held apart from the live LayerConfig fields until they are
 * committed: worldgen threads read those fields mid-chunk, so a toggle must not reach them one click
 * at a time. One draft is shared by every tab of a screen session.
 */
public final class ConfigDraft {
    private final Map<ConfigOption<?>, Object> values = new IdentityHashMap<>();

    public ConfigDraft() {
        for (ConfigOption<?> option : LayerConfig.options()) {
            values.put(option, copyOf(option, option.get()));
        }
    }

    @SuppressWarnings("unchecked")
    public <T> T get(ConfigOption<T> option) {
        return (T) values.get(option);
    }

    public <T> void set(ConfigOption<T> option, T value) {
        values.put(option, value);
    }

    /** Whether every option this one depends on currently has the value it needs, transitively. */
    public boolean requirementsMet(ConfigOption<?> option) {
        for (ConfigOption.Requirement requirement : option.requirements) {
            if (!Objects.equals(values.get(requirement.option()), requirement.value())) return false;
            if (!requirementsMet(requirement.option())) return false;
        }
        return true;
    }

    /** The requirements of {@code option} that are not met, for the tooltip. */
    public List<ConfigOption.Requirement> unmetRequirements(ConfigOption<?> option) {
        List<ConfigOption.Requirement> unmet = new ArrayList<>();
        for (ConfigOption.Requirement requirement : option.requirements) {
            if (!Objects.equals(values.get(requirement.option()), requirement.value())) unmet.add(requirement);
        }
        return unmet;
    }

    public void resetToDefaults(ConfigTab tab) {
        for (ConfigOption<?> option : LayerConfig.options()) {
            if (option.tab == tab) values.put(option, copyOf(option, option.defaultValue));
        }
    }

    /**
     * Writes every changed value into LayerConfig and saves the file.
     * @return the options that changed
     */
    public List<ConfigOption<?>> commit() {
        List<ConfigOption<?>> changed = new ArrayList<>();
        for (ConfigOption<?> option : LayerConfig.options()) {
            if (!Objects.equals(option.get(), values.get(option))) {
                changed.add(option);
                apply(option);
            }
        }
        if (!changed.isEmpty()) {
            LayerConfig.save();
            LayerConfig.onValuesChanged();
        }
        return changed;
    }

    @SuppressWarnings("unchecked")
    private <T> void apply(ConfigOption<T> option) {
        option.set(option.copy((T) values.get(option)));
    }

    @SuppressWarnings("unchecked")
    private static <T> Object copyOf(ConfigOption<T> option, Object value) {
        return option.copy((T) value);
    }
}
