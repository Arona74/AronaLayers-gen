package io.arona74.aronalayersgen.client.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Mod Menu's "Configure" button. Declared as the "modmenu" entrypoint, which only Mod Menu loads. */
public class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return AronaConfigScreen::new;
    }
}
