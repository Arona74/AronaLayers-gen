package io.arona74.aronalayersgen.client.config;

import io.arona74.aronalayersgen.AronaLayersGen;
import io.arona74.aronalayersgen.Compat;
import io.arona74.aronalayersgen.config.ConfigOption;
import io.arona74.aronalayersgen.config.ConfigTab;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The right half of the config screen: a preview image of the option last hovered, and below it
 * that option's name and description. Before any option is hovered it describes the tab instead
 * (on a world generation tab, the warning that changes only reach new chunks).
 *
 * <p>This class decides what is shown and where; each version's {@code PreviewPanel} only draws the
 * {@link Frame} it returns, since drawing is what differs between versions.
 *
 * <p>Images are optional resources, {@code assets/aronalayersgen/textures/gui/config/<option key>.png}
 * (the key lowercased, as resource paths must be) or {@code tab_<tab>.png} for a tab. An option
 * without one shows its text alone. Any size works: the image is scaled to the panel's width,
 * keeping its proportions, and to at most {@link #MAX_IMAGE_SHARE} of its height.
 */
public final class ConfigPreview {
    public static final int BACKGROUND = 0x66000000;
    private static final int SCROLLBAR = 0xFF808080;
    private static final int TEXT_COLOR = 0xFFE0E0E0;
    private static final int TITLE_COLOR = 0xFFFFFFFF;
    private static final int PADDING = 6;
    private static final int GAP = 6;
    private static final double MAX_IMAGE_SHARE = 0.6;
    private static final int SCROLL_STEP = 12;
    private static final int[] NO_IMAGE = new int[0];

    /** Pixel size of each image looked up, NO_IMAGE when there is none; filled as options are hovered. */
    private final Map<String, int[]> imageSizes = new HashMap<>();
    private final ConfigWidgets widgets;
    private final ConfigTab tab;
    private final Font font;

    private int left, top, right, bottom;
    private ConfigOption<?> shown;
    private ConfigOption<?> lastFocused;
    private int scroll;
    private int maxScroll;

    public record Image(String path, int x, int y, int width, int height, int textureWidth, int textureHeight) {}

    public record Line(FormattedCharSequence text, int x, int y, int color) {}

    /** Everything to draw this frame. Image and lines are clipped to the panel; scrollbar may be null. */
    public record Frame(int left, int top, int right, int bottom, Image image, List<Line> lines, int[] scrollbar) {}

    public ConfigPreview(ConfigWidgets widgets, ConfigTab tab, Font font) {
        this.widgets = widgets;
        this.tab = tab;
        this.font = font;
    }

    public void setBounds(int left, int top, int right, int bottom) {
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
    }

    public boolean contains(double x, double y) {
        return x >= left && x < right && y >= top && y < bottom;
    }

    /** Scrolls a description too long for the panel; {@code amount} as the mouse wheel reports it. */
    public void scroll(double amount) {
        scroll = Math.max(0, Math.min(maxScroll, scroll - (int) Math.round(amount * SCROLL_STEP)));
    }

    /**
     * Follows the mouse, then lays the panel out. An option stays shown after the mouse leaves it,
     * so it can be moved onto the panel to scroll a long description; a control reached with the
     * keyboard is shown when focus moves to it.
     */
    public Frame frame() {
        ConfigOption<?> hovered = widgets.hovered();
        ConfigOption<?> focused = widgets.focused();
        ConfigOption<?> next = hovered != null ? hovered : focused != lastFocused && focused != null ? focused : shown;
        lastFocused = focused;
        if (next != shown) {
            shown = next;
            scroll = 0;
        }

        int innerLeft = left + PADDING, innerWidth = right - left - 2 * PADDING;
        int innerHeight = bottom - top - 2 * PADDING;
        int y = top + PADDING - scroll;

        Image image = null;
        String path = shown != null ? "textures/gui/config/" + shown.key.toLowerCase(Locale.ROOT) + ".png"
                : "textures/gui/config/tab_" + tab.id() + ".png";
        int[] size = imageSize(path);
        if (size.length == 2 && innerWidth > 0) {
            int width = innerWidth;
            int height = width * size[1] / size[0];
            int maxHeight = (int) (innerHeight * MAX_IMAGE_SHARE);
            if (height > maxHeight) {
                height = maxHeight;
                width = height * size[0] / size[1];
            }
            image = new Image(path, innerLeft + (innerWidth - width) / 2, y, width, height, size[0], size[1]);
            y += height + GAP;
        }

        List<Line> lines = new ArrayList<>();
        y = addLines(lines, title(), TITLE_COLOR, innerLeft, y, innerWidth);
        y += GAP / 2;
        y = addLines(lines, body(), TEXT_COLOR, innerLeft, y, innerWidth);

        int contentHeight = y + scroll - (top + PADDING);
        maxScroll = Math.max(0, contentHeight - innerHeight);
        scroll = Math.min(scroll, maxScroll);

        int[] scrollbar = null;
        if (maxScroll > 0) {
            int track = bottom - top;
            int thumb = Math.max(10, track * track / (contentHeight + 2 * PADDING));
            int thumbTop = top + (track - thumb) * scroll / maxScroll;
            scrollbar = new int[] {right - 2, thumbTop, right, thumbTop + thumb, SCROLLBAR};
        }
        return new Frame(left, top, right, bottom, image, lines, scrollbar);
    }

    private Component title() {
        if (shown != null) return Component.translatable(shown.langKey()).withStyle(ChatFormatting.BOLD);
        return Component.translatable(tab.langKey()).withStyle(ChatFormatting.BOLD);
    }

    private Component body() {
        if (shown != null) return widgets.description(shown);
        MutableComponent text = Component.empty();
        if (tab.worldgen) {
            for (int line = 1; line < ConfigWidgets.WORLDGEN_WARNING_LINES; line++) {
                if (line > 1) text.append(" ");
                text.append(ConfigWidgets.worldgenWarning(line));
            }
            text.append("\n\n");
        } else {
            text.append(Component.translatable("config.aronalayersgen.section." + tab.id())).append("\n\n");
        }
        return text.append(Component.translatable("config.aronalayersgen.preview.hint").withStyle(ChatFormatting.GRAY));
    }

    private int addLines(List<Line> lines, Component text, int color, int x, int y, int width) {
        if (width <= 0) return y;
        for (FormattedCharSequence line : font.split(text, width)) {
            lines.add(new Line(line, x, y, color));
            y += font.lineHeight + 1;
        }
        return y;
    }

    /** The pixel size from the PNG header, without loading the texture; NO_IMAGE if there is none. */
    private int[] imageSize(String path) {
        return imageSizes.computeIfAbsent(path, p -> {
            var resource = Minecraft.getInstance().getResourceManager().getResource(Compat.id(AronaLayersGen.MOD_ID, p));
            if (resource.isEmpty()) return NO_IMAGE;
            try (InputStream in = resource.get().open()) {
                byte[] header = in.readNBytes(24);
                if (header.length < 24 || header[1] != 'P' || header[2] != 'N' || header[3] != 'G') return NO_IMAGE;
                int width = readInt(header, 16), height = readInt(header, 20);
                return width > 0 && height > 0 ? new int[] {width, height} : NO_IMAGE;
            } catch (IOException e) {
                return NO_IMAGE;
            }
        });
    }

    private static int readInt(byte[] bytes, int at) {
        return (bytes[at] & 0xFF) << 24 | (bytes[at + 1] & 0xFF) << 16 | (bytes[at + 2] & 0xFF) << 8 | (bytes[at + 3] & 0xFF);
    }
}
