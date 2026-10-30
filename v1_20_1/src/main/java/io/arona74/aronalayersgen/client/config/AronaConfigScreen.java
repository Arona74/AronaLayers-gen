package io.arona74.aronalayersgen.client.config;

import io.arona74.aronalayersgen.AronaLayersGen;
import io.arona74.aronalayersgen.LayerConfig;
import io.arona74.aronalayersgen.config.ConfigDraft;
import io.arona74.aronalayersgen.config.ConfigOption;
import io.arona74.aronalayersgen.config.ConfigTab;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 1.20.1 copy of the config screen; the same as the shared one but for drawing its background
 * itself and the mouse wheel's older signature. The in-game config screen: one tab per {@link ConfigTab} along the top, the options of the open
 * tab in the left half, and in the right half a preview of the option under the mouse (an image,
 * its name and its description: {@link ConfigPreview}). Done / Cancel sit in the footer. Edits go
 * into a {@link ConfigDraft} and reach the game only on Done.
 *
 * <p>The option list and the preview panel are drawn by each version's {@link OptionRows} and
 * {@link PreviewPanel}, as drawing differs between versions. Switching tab opens a new screen on
 * the same draft.
 */
public class AronaConfigScreen extends Screen {
    private static final int LIST_TOP = 50;
    private static final int FOOTER_HEIGHT = 32;

    /** Remembered for the session, so reopening the screen returns to where the user was. */
    private static ConfigTab lastTab = ConfigTab.WORLDGEN;
    private static boolean showAdvanced;

    private final Screen lastScreen;
    private final ConfigDraft draft;
    private final ConfigTab tab;
    private ConfigPreview preview;
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
        int titleWidth = font.width(title);
        addRenderableWidget(new StringWidget((width - titleWidth) / 2, 8, titleWidth, 9, title, font));

        List<ConfigTab> tabs = visibleTabs();
        int buttonWidth = Math.min(80, (width - 20) / tabs.size() - 4);
        int x = (width - tabs.size() * (buttonWidth + 4) + 4) / 2;
        for (ConfigTab t : tabs) {
            Button button = addRenderableWidget(Button.builder(Component.translatable(t.langKey()), b -> reopen(t))
                    .bounds(x, 22, buttonWidth, 20).build());
            button.active = t != tab;
            x += buttonWidth + 4;
        }

        int half = width / 2;
        int listBottom = height - FOOTER_HEIGHT;
        OptionRows list = new OptionRows(Minecraft.getInstance(), half, listBottom - LIST_TOP, LIST_TOP);
        ConfigWidgets widgets = new ConfigWidgets(draft, font);
        fillRows(list, widgets, tab, font);
        addRenderableWidget(list);

        preview = new ConfigPreview(widgets, tab, font);
        preview.setBounds(half + 4, LIST_TOP + 2, width - 10, listBottom - 2);
        addRenderableOnly(new PreviewPanel(preview, font));

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

    /**
     * The open tab's rows: on a world generation tab the warning's heading first, then each group
     * under its heading, one option per row. A block-list box has no label of its own, so its name
     * gets a row above it.
     */
    static void fillRows(OptionRows list, ConfigWidgets widgets, ConfigTab tab, net.minecraft.client.gui.Font font) {
        if (tab.worldgen) list.add(new StringWidget(ConfigWidgets.WIDTH, ConfigWidgets.HEIGHT, ConfigWidgets.worldgenWarning(0), font));
        String group = null;
        for (ConfigOption<?> option : LayerConfig.options()) {
            if (option.tab != tab || (option.advanced && !showAdvanced)) continue;
            if (!Objects.equals(option.group, group)) {
                group = option.group;
                if (group != null) {
                    list.add(new StringWidget(ConfigWidgets.WIDTH, ConfigWidgets.HEIGHT,
                            Component.translatable("config.aronalayersgen.group." + group), font));
                }
            }
            if (option.kind == ConfigOption.Kind.STRING_LIST) list.add(widgets.label(option));
            list.add(widgets.create(option));
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    /** The mouse wheel over the preview panel scrolls a long description. */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scroll) {
        if (preview != null && preview.contains(mouseX, mouseY)) {
            preview.scroll(scroll);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scroll);
    }

    private void reopen(ConfigTab target) {
        ScreenCompat.setScreen(new AronaConfigScreen(lastScreen, draft, target));
    }

    /** Done and Escape save, as in vanilla's options screens; Cancel discards. */
    @Override
    public void onClose() {
        if (!discard) commit(draft);
        ScreenCompat.setScreen(lastScreen);
    }

    static void commit(ConfigDraft draft) {
        List<ConfigOption<?>> changed = draft.commit();
        List<String> restart = changed.stream()
                .filter(o -> o.apply == ConfigOption.Apply.RESTART)
                .map(o -> o.key)
                .toList();
        if (!restart.isEmpty()) {
            AronaLayersGen.LOGGER.info("[Config] Restart Minecraft for these changes to take effect: {}", restart);
        }
    }

    static List<ConfigTab> visibleTabs() {
        List<ConfigTab> tabs = new ArrayList<>();
        for (ConfigTab t : ConfigTab.values()) {
            if (t.requiredMod == null || FabricLoader.getInstance().isModLoaded(t.requiredMod)) tabs.add(t);
        }
        return tabs;
    }
}
