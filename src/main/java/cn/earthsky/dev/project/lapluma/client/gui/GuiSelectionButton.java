package cn.earthsky.dev.project.lapluma.client.gui;

import lombok.Getter;
import lombok.Setter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;

import java.util.function.Consumer;

public class GuiSelectionButton extends GuiButton {

    @Getter @Setter
    private Consumer<GuiScreen> callback;

    @Getter @Setter
    private int keyIndex;

    public GuiSelectionButton(int buttonId, int x, int y, int width, int height, String buttonText, int keyIndex) {
        super(buttonId, x, y, width, height, buttonText);
        this.keyIndex = keyIndex;
    }

    @Override
    public void drawButton(Minecraft mc, int mouseX, int mouseY, float partialTicks) {
        if (this.visible) {
            FontRenderer fontrenderer = mc.fontRenderer;
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            this.hovered = mouseX >= this.x && mouseY >= this.y && mouseX < this.x + this.width && mouseY < this.y + this.height;
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA, GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
            GlStateManager.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);

            int bgColor = this.hovered ? 0xC0333333 : 0xA0222222;
            GuiScreen.drawRect(this.x, this.y, this.x + this.width, this.y + this.height, bgColor);

            int borderColor = this.hovered ? 0xFFFFAA00 : 0x80AAAAAA;
            GuiScreen.drawRect(this.x, this.y, this.x + this.width, this.y + 1, borderColor);
            GuiScreen.drawRect(this.x, this.y + this.height - 1, this.x + this.width, this.y + this.height, borderColor);
            GuiScreen.drawRect(this.x, this.y, this.x + 1, this.y + this.height, borderColor);
            GuiScreen.drawRect(this.x + this.width - 1, this.y, this.x + this.width, this.y + this.height, borderColor);

            if (keyIndex > 0 && keyIndex <= 9) {
                String keyHint = "\u00A76[" + keyIndex + "]";
                fontrenderer.drawStringWithShadow(keyHint, this.x + 4, this.y + (this.height - 8) / 2.0f, 0xFFFFAA00);
            }

            int textColor = 14737632;
            if (packedFGColour != 0) {
                textColor = packedFGColour;
            } else if (!this.enabled) {
                textColor = 10526880;
            } else if (this.hovered) {
                textColor = 0xFFFFFF;
            }

            int textX = this.x + 24;
            fontrenderer.drawStringWithShadow(this.displayString, textX, this.y + (this.height - 8) / 2.0f, textColor);
        }
    }
}
