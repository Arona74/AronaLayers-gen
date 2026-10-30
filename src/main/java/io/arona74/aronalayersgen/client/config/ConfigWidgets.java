package io.arona74.aronalayersgen.client.config;

import io.arona74.aronalayersgen.Compat;
import io.arona74.aronalayersgen.config.ConfigDraft;
import io.arona74.aronalayersgen.config.ConfigOption;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.world.level.block.Blocks;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Builds the widget for each option and keeps it in step with the draft: whether it is editable
 * depends on other options' values, so it is refreshed after every change. What an option does is
 * not a tooltip but the screen's preview panel ({@link ConfigPreview}), which asks here which
 * option is under the mouse and reads its {@link #description}. Only widget APIs common to every supported Minecraft version are used here; what differs
 * goes through {@link ScreenCompat}.
 */
public final class ConfigWidgets {
    public static final int WIDTH = 150;
    public static final int HEIGHT = 20;
    private static final int TEXT_COLOR = 0xFFE0E0E0;
    private static final int ERROR_COLOR = 0xFFFF5555;
    /** A value other than the option's default. */
    private static final int CHANGED_COLOR = 0xFFFFFF55;
    private static final Style CHANGED = Style.EMPTY.withColor(ChatFormatting.YELLOW);

    private final ConfigDraft draft;
    private final Font font;
    private final boolean remote;
    private final List<Bound> bound = new ArrayList<>();
    /** Labels beside block-list boxes: hovering one shows its option too. */
    private final List<Bound> labels = new ArrayList<>();

    private record Bound(ConfigOption<?> option, AbstractWidget widget) {}

    public ConfigWidgets(ConfigDraft draft, Font font) {
        this.draft = draft;
        this.font = font;
        // Connected to a server this client does not run: worldgen uses that server's file.
        Minecraft minecraft = Minecraft.getInstance();
        this.remote = minecraft.level != null && !minecraft.hasSingleplayerServer();
    }

    /** The control for {@code option}, ready to be laid out. */
    @SuppressWarnings("unchecked")
    public AbstractWidget create(ConfigOption<?> option) {
        AbstractWidget widget = switch (option.kind) {
            case BOOLEAN -> toggle((ConfigOption<Boolean>) option);
            case ENUM -> cycle(option);
            case INT, DOUBLE -> new OptionSlider((ConfigOption<Number>) option);
            case STRING_LIST -> blockList((ConfigOption<List<String>>) option);
        };
        bound.add(new Bound(option, widget));
        refresh(option, widget);
        return widget;
    }

    /** Lines of the warning on a world generation tab (ConfigTab.worldgen). */
    public static final int WORLDGEN_WARNING_LINES = 4;

    /**
     * One line of that warning: the first is its heading, kept as a row at the top of the option
     * list; the preview panel shows the whole of it until an option is hovered.
     */
    public static Component worldgenWarning(int line) {
        MutableComponent text = Component.translatable("config.aronalayersgen.worldgen_warning." + (line + 1));
        return line == 0 ? text.withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD) : text.withStyle(ChatFormatting.YELLOW);
    }

    /** The label shown above a block-list box, which has no label of its own. */
    public AbstractWidget label(ConfigOption<?> option) {
        AbstractWidget widget = new net.minecraft.client.gui.components.StringWidget(WIDTH, HEIGHT, label(option.langKey()), font);
        labels.add(new Bound(option, widget));
        return widget;
    }

    /** The option whose control or label is under the mouse, or null. */
    public ConfigOption<?> hovered() {
        for (Bound b : bound) if (b.widget().isHovered()) return b.option();
        for (Bound b : labels) if (b.widget().isHovered()) return b.option();
        return null;
    }

    /** The option whose control has keyboard focus, or null. */
    public ConfigOption<?> focused() {
        for (Bound b : bound) if (b.widget().isFocused()) return b.option();
        return null;
    }

    /** Re-evaluates every widget after a value changed. */
    public void refreshAll() {
        for (Bound b : bound) refresh(b.option(), b.widget());
    }

    private AbstractWidget toggle(ConfigOption<Boolean> option) {
        return ScreenCompat.cycle((Boolean value) -> shown(option, value,
                        value ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF), List.of(Boolean.TRUE, Boolean.FALSE), draft.get(option))
                .create(0, 0, WIDTH, HEIGHT, label(option.langKey()), (button, value) -> changed(option, value));
    }

    private <T> AbstractWidget cycle(ConfigOption<T> option) {
        return ScreenCompat.cycle((T value) -> shown(option, value, Component.literal(((Enum<?>) value).name())),
                        Arrays.asList(option.enumValues()), draft.get(option))
                .create(0, 0, WIDTH, HEIGHT, label(option.langKey()), (button, value) -> changed(option, value));
    }

    private AbstractWidget blockList(ConfigOption<List<String>> option) {
        EditBox box = new EditBox(font, 0, 0, WIDTH, HEIGHT, label(option.langKey()));
        box.setMaxLength(4096);
        box.setValue(String.join(", ", draft.get(option)));
        box.setHint(Component.translatable(option.ids == ConfigOption.Ids.BIOMES
                ? "config.aronalayersgen.biome_list_hint" : "config.aronalayersgen.list_hint").withStyle(ChatFormatting.DARK_GRAY));
        box.setResponder(text -> {
            List<String> ids = parseIds(text);
            box.setTextColor(listColor(option, ids));
            changed(option, ids);
        });
        box.setTextColor(listColor(option, draft.get(option)));
        return box;
    }

    private <T> void changed(ConfigOption<T> option, T value) {
        draft.set(option, value);
        refreshAll();
    }

    private void refresh(ConfigOption<?> option, AbstractWidget widget) {
        boolean blockedRemotely = remote && option.side == ConfigOption.Side.SERVER;
        widget.active = option.modPresent() && draft.requirementsMet(option) && !blockedRemotely;
    }

    /** A value as its widget shows it: yellow when it is not the option's default. */
    private static Component shown(ConfigOption<?> option, Object value, Component text) {
        return isDefault(option, value) ? text : text.copy().setStyle(CHANGED);
    }

    /** A list box's text colour: red for unknown ids, else yellow when changed from the default. */
    private static int listColor(ConfigOption<?> option, List<String> ids) {
        if (!unknownIds(option, ids).isEmpty()) return ERROR_COLOR;
        return isDefault(option, ids) ? TEXT_COLOR : CHANGED_COLOR;
    }

    /** Numbers compared by value, so 3 and 3.0 alike. */
    private static boolean isDefault(ConfigOption<?> option, Object value) {
        if (value instanceof Number a && option.defaultValue instanceof Number b) return a.doubleValue() == b.doubleValue();
        return Objects.equals(value, option.defaultValue);
    }

    /**
     * What the preview panel says about an option: its description, its default and when a change
     * applies, then anything that keeps it from being edited right now.
     */
    public <T> Component description(ConfigOption<T> option) {
        boolean blockedRemotely = remote && option.side == ConfigOption.Side.SERVER;
        MutableComponent text = Component.translatable(option.descriptionKey());
        text.append("\n\n").append(Component.translatable("config.aronalayersgen.tooltip.default",
                option.format(option.defaultValue)).withStyle(ChatFormatting.GRAY));
        ChatFormatting applyStyle = option.apply == ConfigOption.Apply.RESTART ? ChatFormatting.GOLD : ChatFormatting.GRAY;
        text.append("\n").append(Component.translatable(option.apply.langKey()).withStyle(applyStyle));

        if (!option.modPresent()) {
            text.append("\n").append(Component.translatable("config.aronalayersgen.tooltip.requires_mod",
                    String.join(" / ", option.mods)).withStyle(ChatFormatting.RED));
        }
        for (ConfigOption.Requirement requirement : draft.unmetRequirements(option)) {
            text.append("\n").append(Component.translatable("config.aronalayersgen.tooltip.requires",
                    Component.translatable(requirement.option().langKey()),
                    displayValue(requirement.value())).withStyle(ChatFormatting.RED));
        }
        if (blockedRemotely) {
            text.append("\n").append(Component.translatable("config.aronalayersgen.tooltip.remote").withStyle(ChatFormatting.YELLOW));
        }
        if (option.kind == ConfigOption.Kind.STRING_LIST) {
            @SuppressWarnings("unchecked")
            List<String> unknown = unknownIds(option, (List<String>) draft.get(option));
            if (!unknown.isEmpty()) {
                text.append("\n").append(Component.translatable("config.aronalayersgen.tooltip.unknown_blocks",
                        String.join(", ", unknown)).withStyle(ChatFormatting.RED));
            }
        }
        return text;
    }

    private static Component displayValue(Object value) {
        if (value instanceof Boolean b) return b ? Component.translatable("options.on") : Component.translatable("options.off");
        return Component.literal(String.valueOf(value));
    }

    private static Component label(String key) {
        return Component.translatable(key);
    }

    private static List<String> parseIds(String text) {
        List<String> ids = new ArrayList<>();
        for (String part : text.split(",")) {
            String id = part.trim();
            if (!id.isEmpty()) ids.add(id);
        }
        return ids;
    }

    private static List<String> unknownIds(ConfigOption<?> option, List<String> ids) {
        List<String> unknown = new ArrayList<>();
        if (option.ids == ConfigOption.Ids.BIOMES) return unknown;   // no registry outside a world
        for (String id : ids) {
            if (!id.equals("minecraft:air") && Compat.blockFromId(id) == Blocks.AIR) unknown.add(id);
        }
        return unknown;
    }

    /** A slider over an INT or DOUBLE option's range, snapped to its step. */
    private final class OptionSlider extends AbstractSliderButton {
        private final ConfigOption<Number> option;

        OptionSlider(ConfigOption<Number> option) {
            super(0, 0, WIDTH, HEIGHT, Component.empty(), toSlider(option, draft.get(option).doubleValue()));
            this.option = option;
            updateMessage();
        }

        private double current() {
            double raw = option.min + value * (option.max - option.min);
            double snapped = option.min + Math.round((raw - option.min) / option.step) * option.step;
            return Math.max(option.min, Math.min(option.max, snapped));
        }

        private Number currentValue() {
            double current = current();
            if (option.kind == ConfigOption.Kind.INT) return (int) Math.round(current);
            // Rounded to the step's decimals so 0.05 stays 0.05 rather than 0.05000000000000001.
            int decimals = Math.max(0, BigDecimal.valueOf(option.step).stripTrailingZeros().scale());
            return BigDecimal.valueOf(current).setScale(decimals, RoundingMode.HALF_UP).doubleValue();
        }

        @Override
        protected void updateMessage() {
            if (option == null) return; // called by the super constructor, before the field is set
            Number value = currentValue();
            setMessage(CommonComponents.optionNameValue(Component.translatable(option.langKey()),
                    shown(option, value, Component.literal(option.format(value)))));
        }

        @Override
        protected void applyValue() {
            changed(option, currentValue());
        }

        private static double toSlider(ConfigOption<Number> option, double value) {
            return option.max == option.min ? 0 : (value - option.min) / (option.max - option.min);
        }
    }
}
