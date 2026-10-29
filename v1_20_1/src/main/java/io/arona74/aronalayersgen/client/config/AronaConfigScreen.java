package io.arona74.aronalayersgen.client.config;

import io.arona74.aronalayersgen.AronaLayersGen;
import io.arona74.aronalayersgen.LayerConfig;
import io.arona74.aronalayersgen.config.ConfigDraft;
import io.arona74.aronalayersgen.config.ConfigOption;
import io.arona74.aronalayersgen.config.ConfigTab;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 1.20.1 copy of the config screen. Same tabs, options and footer as the shared one, but 1.20.1's
 * OptionsList only takes OptionInstances, so the rows are a small list of its own here.
 */
public class AronaConfigScreen extends Screen {
    private static final int LIST_TOP = 50;
    private static final int FOOTER_HEIGHT = 32;

    private static ConfigTab lastTab = ConfigTab.WORLDGEN;
    private static boolean showAdvanced;

    private final Screen lastScreen;
    private final ConfigDraft draft;
    private final ConfigTab tab;
    private Rows list;
    private boolean discard;

    public AronaConfigScreen(Screen parent) {
        this(parent, new ConfigDraft(), lastTab);
    }

    private AronaConfigScreen(Screen parent, ConfigDraft draft, ConfigTab tab) {
        super(Component.translatable("config.aronalayersgen.title"));
        this.lastScreen = parent;
        this.draft = draft;
        this.tab = tab;
        lastTab = tab;
    }

    @Override
    protected void init() {
        List<ConfigTab> tabs = visibleTabs();
        int buttonWidth = Math.min(80, (width - 20) / tabs.size() - 4);
        int x = (width - tabs.size() * (buttonWidth + 4) + 4) / 2;
        for (ConfigTab t : tabs) {
            Button button = addRenderableWidget(Button.builder(Component.translatable(t.langKey()), b -> reopen(t))
                    .bounds(x, 22, buttonWidth, 20).build());
            button.active = t != tab;
            x += buttonWidth + 4;
        }

        list = addRenderableWidget(new Rows(minecraft, width, height, LIST_TOP, height - FOOTER_HEIGHT));
        ConfigWidgets widgets = new ConfigWidgets(draft, font);
        List<AbstractWidget> pending = new ArrayList<>();
        if (tab.worldgen) {
            for (int line = 0; line < ConfigWidgets.WORLDGEN_WARNING_LINES; line++) {
                list.add(List.of(new StringWidget(310, 20, ConfigWidgets.worldgenWarning(line), font)));
            }
        }
        String group = null;
        for (ConfigOption<?> option : LayerConfig.options()) {
            if (option.tab != tab || (option.advanced && !showAdvanced)) continue;
            if (!Objects.equals(option.group, group)) {
                flush(pending);
                group = option.group;
                if (group != null) {
                    list.add(List.of(new StringWidget(310, 20, Component.translatable("config.aronalayersgen.group." + group), font)));
                }
            }
            if (option.kind == ConfigOption.Kind.STRING_LIST) {
                flush(pending);
                list.add(List.of(widgets.label(option), widgets.create(option)));
            } else {
                pending.add(widgets.create(option));
            }
        }
        flush(pending);

        int y = height - 26;
        int fx = width / 2 - 179;
        addRenderableWidget(ScreenCompat.onOff(showAdvanced)
                .create(fx, y, 100, 20, Component.translatable("config.aronalayersgen.advanced"), (b, value) -> {
                    showAdvanced = value;
                    reopen(tab);
                })).setTooltip(Tooltip.create(Component.translatable("config.aronalayersgen.advanced.tooltip")));
        addRenderableWidget(Button.builder(Component.translatable("config.aronalayersgen.reset_tab"), b -> {
                    draft.resetToDefaults(tab);
                    reopen(tab);
                }).bounds(fx + 106, y, 70, 20)
                .tooltip(Tooltip.create(Component.translatable("config.aronalayersgen.reset_tab.tooltip"))).build());
        addRenderableWidget(Button.builder(Component.translatable("config.aronalayersgen.open_folder"),
                        b -> ScreenCompat.openPath(LayerConfig.configDir()))
                .bounds(fx + 182, y, 50, 20)
                .tooltip(Tooltip.create(Component.translatable("config.aronalayersgen.open_folder.tooltip"))).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> {
                    discard = true;
                    onClose();
                }).bounds(fx + 238, y, 60, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).bounds(fx + 304, y, 60, 20).build());
    }

    private void flush(List<AbstractWidget> pending) {
        for (int i = 0; i < pending.size(); i += 2) {
            list.add(pending.subList(i, Math.min(i + 2, pending.size())));
        }
        pending.clear();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 8, 0xFFFFFF);
    }

    private void reopen(ConfigTab target) {
        ScreenCompat.setScreen(new AronaConfigScreen(lastScreen, draft, target));
    }

    @Override
    public void onClose() {
        if (!discard) {
            List<String> restart = draft.commit().stream()
                    .filter(o -> o.apply == ConfigOption.Apply.RESTART)
                    .map(o -> o.key)
                    .toList();
            if (!restart.isEmpty()) {
                AronaLayersGen.LOGGER.info("[Config] Restart Minecraft for these changes to take effect: {}", restart);
            }
        }
        Minecraft.getInstance().setScreen(lastScreen);
    }

    private static List<ConfigTab> visibleTabs() {
        List<ConfigTab> tabs = new ArrayList<>();
        for (ConfigTab t : ConfigTab.values()) {
            if (t.requiredMod == null || FabricLoader.getInstance().isModLoaded(t.requiredMod)) tabs.add(t);
        }
        return tabs;
    }

    /** Rows of up to two widgets, laid out like vanilla's option list. */
    private static final class Rows extends ContainerObjectSelectionList<Rows.Row> {
        Rows(Minecraft minecraft, int width, int height, int top, int bottom) {
            super(minecraft, width, height, top, bottom, 25);
        }

        void add(List<AbstractWidget> widgets) {
            addEntry(new Row(List.copyOf(widgets)));
        }

        @Override
        public int getRowWidth() {
            return 310;
        }

        @Override
        protected int getScrollbarPosition() {
            return width / 2 + 160;
        }

        static final class Row extends ContainerObjectSelectionList.Entry<Row> {
            private final List<AbstractWidget> widgets;

            Row(List<AbstractWidget> widgets) {
                this.widgets = widgets;
            }

            @Override
            public void render(GuiGraphics graphics, int index, int top, int left, int width, int height,
                               int mouseX, int mouseY, boolean hovering, float partialTick) {
                int x = left;
                for (AbstractWidget widget : widgets) {
                    widget.setX(x);
                    widget.setY(top);
                    widget.render(graphics, mouseX, mouseY, partialTick);
                    x += 160;
                }
            }

            @Override
            public List<? extends GuiEventListener> children() {
                return widgets;
            }

            @Override
            public List<? extends NarratableEntry> narratables() {
                return widgets;
            }
        }
    }
}
