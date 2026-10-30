package io.arona74.aronalayersgen.client.config;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;

import java.util.List;

/**
 * The config screen's option list: one widget per row, stretched to the row's width, in a list as
 * wide as it is given (the left half of the screen). Vanilla's OptionsList lays its rows out around
 * the middle of the screen, so it cannot be moved aside for the preview panel.
 */
public final class OptionRows extends ContainerObjectSelectionList<OptionRows.Row> {
    private static final int MAX_ROW_WIDTH = 260;

    public OptionRows(Minecraft minecraft, int width, int height, int y) {
        super(minecraft, width, height, y, 24);
    }

    public void add(AbstractWidget widget) {
        addEntry(new Row(widget));
    }

    @Override
    public int getRowWidth() {
        return Math.min(MAX_ROW_WIDTH, width - 24);
    }

    @Override
    protected int getScrollbarPosition() {
        return getRowLeft() + getRowWidth() + 6;
    }

    static final class Row extends ContainerObjectSelectionList.Entry<Row> {
        private final AbstractWidget widget;

        Row(AbstractWidget widget) {
            this.widget = widget;
        }

        @Override
        public void render(GuiGraphics graphics, int index, int top, int left, int width, int height,
                           int mouseX, int mouseY, boolean hovering, float partialTick) {
            widget.setX(left);
            widget.setY(top);
            widget.setWidth(width);
            widget.render(graphics, mouseX, mouseY, partialTick);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of(widget);
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of(widget);
        }
    }
}
