package io.arona74.aronalayersgen.client.config;

import io.arona74.aronalayersgen.AronaLayersGen;
import io.arona74.aronalayersgen.LayerConfig;
import io.arona74.aronalayersgen.config.ConfigDraft;
import io.arona74.aronalayersgen.config.ConfigOption;
import io.arona74.aronalayersgen.config.ConfigTab;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The in-game config screen: one tab per {@link ConfigTab}, the options of the open tab in vanilla's
 * two-column option list, and Done / Cancel in the footer. Edits go into a {@link ConfigDraft} and
 * reach the game only on Done.
 *
 * <p>Built on OptionsSubScreen so the list, scrolling and background are vanilla's own on every
 * version from 1.21.1 on (1.20.1 has its own copy of this class). Its header layout is final and
 * cannot be rebuilt in place, so switching tab opens a new screen on the same draft.
 */
public class AronaConfigScreen extends OptionsSubScreen {
    private static final int HEADER_HEIGHT = 58;

    /** Remembered for the session, so reopening the screen returns to where the user was. */
    private static ConfigTab lastTab = ConfigTab.WORLDGEN;
    private static boolean showAdvanced;

    private final ConfigDraft draft;
    private final ConfigTab tab;
    private ConfigWidgets widgets;
    private boolean discard;

    public AronaConfigScreen(Screen parent) {
        this(parent, new ConfigDraft(), lastTab);
    }

    private AronaConfigScreen(Screen parent, ConfigDraft draft, ConfigTab tab) {
        super(parent, Minecraft.getInstance().options, Component.translatable("config.aronalayersgen.title"));
        this.draft = draft;
        this.tab = tab;
        lastTab = tab;
        layout.setHeaderHeight(HEADER_HEIGHT);
    }

    @Override
    protected void addTitle() {
        LinearLayout header = layout.addToHeader(LinearLayout.vertical().spacing(6));
        header.defaultCellSetting().alignHorizontallyCenter();
        header.addChild(new StringWidget(title, font));

        List<ConfigTab> tabs = visibleTabs();
        LinearLayout row = header.addChild(LinearLayout.horizontal().spacing(4));
        int buttonWidth = Math.min(80, (width - 20) / tabs.size() - 4);
        for (ConfigTab t : tabs) {
            Button button = Button.builder(Component.translatable(t.langKey()), b -> reopen(t)).width(buttonWidth).build();
            button.active = t != tab;
            row.addChild(button);
        }
    }

    @Override
    protected void addOptions() {
        widgets = new ConfigWidgets(draft, font);
        List<AbstractWidget> pending = new ArrayList<>();
        if (tab.worldgen) {
            for (int line = 0; line < ConfigWidgets.WORLDGEN_WARNING_LINES; line++) {
                ScreenCompat.addHeader(list, ConfigWidgets.worldgenWarning(line), font);
            }
        }
        String group = null;
        for (ConfigOption<?> option : LayerConfig.options()) {
            if (option.tab != tab || (option.advanced && !showAdvanced)) continue;
            if (!Objects.equals(option.group, group)) {
                flush(pending);
                group = option.group;
                if (group != null) ScreenCompat.addHeader(list, Component.translatable("config.aronalayersgen.group." + group), font);
            }
            if (option.kind == ConfigOption.Kind.STRING_LIST) {
                // A text box has no label of its own: give it a row with the name beside it.
                flush(pending);
                list.addSmall(widgets.label(option), widgets.create(option));
            } else {
                pending.add(widgets.create(option));
            }
        }
        flush(pending);
    }

    private void flush(List<AbstractWidget> pending) {
        if (pending.isEmpty()) return;
        list.addSmall(new ArrayList<>(pending));
        pending.clear();
    }

    @Override
    protected void addFooter() {
        LinearLayout footer = layout.addToFooter(LinearLayout.horizontal().spacing(6));
        footer.addChild(ScreenCompat.onOff(showAdvanced)
                .create(0, 0, 100, 20, Component.translatable("config.aronalayersgen.advanced"), (b, value) -> {
                    showAdvanced = value;
                    reopen(tab);
                })).setTooltip(Tooltip.create(Component.translatable("config.aronalayersgen.advanced.tooltip")));
        footer.addChild(Button.builder(Component.translatable("config.aronalayersgen.reset_tab"), b -> {
                    draft.resetToDefaults(tab);
                    reopen(tab);
                }).width(70).tooltip(Tooltip.create(Component.translatable("config.aronalayersgen.reset_tab.tooltip"))).build());
        footer.addChild(Button.builder(Component.translatable("config.aronalayersgen.open_folder"),
                        b -> ScreenCompat.openPath(LayerConfig.configDir()))
                .width(50).tooltip(Tooltip.create(Component.translatable("config.aronalayersgen.open_folder.tooltip"))).build());
        footer.addChild(Button.builder(CommonComponents.GUI_CANCEL, b -> {
                    discard = true;
                    onClose();
                }).width(60).build());
        footer.addChild(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).width(60).build());
    }

    private void reopen(ConfigTab target) {
        ScreenCompat.setScreen(new AronaConfigScreen(lastScreen, draft, target));
    }

    /** Done and Escape save, as in vanilla's options screens; Cancel discards. */
    @Override
    public void onClose() {
        if (!discard) commit(draft);
        super.onClose();
    }

    /** Vanilla's version saves options.txt, which this screen never touches. */
    @Override
    public void removed() {
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
