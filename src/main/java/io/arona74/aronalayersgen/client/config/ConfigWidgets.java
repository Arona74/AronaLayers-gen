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
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.level.block.Blocks;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Builds the widget for each option and keeps it in step with the draft: whether it is editable
 * and what its tooltip says depend on other options' values, so both are refreshed after every
 * change. Only widget APIs common to every supported Minecraft version are used here; what differs
 * goes through {@link ScreenCompat}.
 */
public final class ConfigWidgets {
    public static final int WIDTH = 150;
    public static final int HEIGHT = 20;
    private static final int TEXT_COLOR = 0xFFE0E0E0;
    private static final int ERROR_COLOR = 0xFFFF5555;

    private final ConfigDraft draft;
    private final Font font;
    private final boolean remote;
    private final List<Bound> bound = new ArrayList<>();

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

    /** Rows of the warning that opens a world generation tab (ConfigTab.worldgen). */
    public static final int WORLDGEN_WARNING_LINES = 4;

    /**
     * One row of that warning. The option list's rows are a single line high, so the text is split
     * into short lines rather than wrapped.
     */
    public static Component worldgenWarning(int line) {
        MutableComponent text = Component.translatable("config.aronalayersgen.worldgen_warning." + (line + 1));
        return line == 0 ? text.withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD) : text.withStyle(ChatFormatting.YELLOW);
    }

    /** The label shown left of a block-list box, which has no label of its own. */
    public AbstractWidget label(ConfigOption<?> option) {
        return new net.minecraft.client.gui.components.StringWidget(WIDTH, HEIGHT, label(option.langKey()), font);
    }

    /** Re-evaluates every widget after a value changed. */
    public void refreshAll() {
        for (Bound b : bound) refresh(b.option(), b.widget());
    }

    private AbstractWidget toggle(ConfigOption<Boolean> option) {
        return ScreenCompat.onOff(draft.get(option))
                .create(0, 0, WIDTH, HEIGHT, label(option.langKey()), (button, value) -> changed(option, value));
    }

    private <T> AbstractWidget cycle(ConfigOption<T> option) {
        return ScreenCompat.cycle((T value) -> Component.literal(((Enum<?>) value).name()), draft.get(option))
                .withValues(Arrays.asList(option.enumValues()))
                .create(0, 0, WIDTH, HEIGHT, label(option.langKey()), (button, value) -> changed(option, value));
    }

    private AbstractWidget blockList(ConfigOption<List<String>> option) {
        EditBox box = new EditBox(font, 0, 0, WIDTH, HEIGHT, label(option.langKey()));
        box.setMaxLength(4096);
        box.setValue(String.join(", ", draft.get(option)));
        box.setHint(Component.translatable("config.aronalayersgen.list_hint").withStyle(ChatFormatting.DARK_GRAY));
        box.setResponder(text -> {
            List<String> ids = parseIds(text);
            box.setTextColor(unknownIds(ids).isEmpty() ? TEXT_COLOR : ERROR_COLOR);
            changed(option, ids);
        });
        box.setTextColor(unknownIds(draft.get(option)).isEmpty() ? TEXT_COLOR : ERROR_COLOR);
        return box;
    }

    private <T> void changed(ConfigOption<T> option, T value) {
        draft.set(option, value);
        refreshAll();
    }

    private void refresh(ConfigOption<?> option, AbstractWidget widget) {
        boolean blockedRemotely = remote && option.side == ConfigOption.Side.SERVER;
        widget.active = option.modPresent() && draft.requirementsMet(option) && !blockedRemotely;
        widget.setTooltip(Tooltip.create(tooltip(option, blockedRemotely)));
    }

    private <T> Component tooltip(ConfigOption<T> option, boolean blockedRemotely) {
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
            List<String> unknown = unknownIds((List<String>) draft.get(option));
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

    private static List<String> unknownIds(List<String> ids) {
        List<String> unknown = new ArrayList<>();
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
            setMessage(Component.translatable(option.langKey()).append(": ").append(option.format(currentValue())));
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
