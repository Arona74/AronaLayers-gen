package io.arona74.aronalayersgen.client.config;

import io.arona74.aronalayersgen.AronaLayersGen;
import io.arona74.aronalayersgen.Compat;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.renderer.RenderPipelines;

/** Draws the {@link ConfigPreview} panel (Minecraft 1.21.11). */
public final class PreviewPanel implements Renderable {
    private final ConfigPreview preview;
    private final Font font;

    public PreviewPanel(ConfigPreview preview, Font font) {
        this.preview = preview;
        this.font = font;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        ConfigPreview.Frame frame = preview.frame();
        graphics.fill(frame.left(), frame.top(), frame.right(), frame.bottom(), ConfigPreview.BACKGROUND);
        graphics.enableScissor(frame.left(), frame.top(), frame.right(), frame.bottom());
        ConfigPreview.Image image = frame.image();
        if (image != null) {
            graphics.blit(RenderPipelines.GUI_TEXTURED, Compat.id(AronaLayersGen.MOD_ID, image.path()), image.x(), image.y(), 0f, 0f, image.width(), image.height(),
                    image.textureWidth(), image.textureHeight(), image.textureWidth(), image.textureHeight());
        }
        for (ConfigPreview.Line line : frame.lines()) graphics.drawString(font, line.text(), line.x(), line.y(), line.color());
        graphics.disableScissor();
        int[] bar = frame.scrollbar();
        if (bar != null) graphics.fill(bar[0], bar[1], bar[2], bar[3], bar[4]);
    }
}
