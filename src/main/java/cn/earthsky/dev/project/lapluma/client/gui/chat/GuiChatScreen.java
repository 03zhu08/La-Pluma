package cn.earthsky.dev.project.lapluma.client.gui.chat;

import cn.earthsky.dev.project.lapluma.client.gui.chat.data.ChatContact;
import cn.earthsky.dev.project.lapluma.client.gui.chat.data.ChatDataManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import java.io.IOException;
import java.util.List;

public class GuiChatScreen extends GuiScreen {

    private final GuiContactList contactList;
    private final GuiMessagePanel messagePanel;
    private final ShaderBlur shaderBlur;

    private static final float CONTACT_RATIO = 0.28f;
    private static final int MARGIN = 8;
    private static final int BORDER_COLOR = 0x40FF5733;

    private String initialContactId;
    private long openTime = 0;
    private static final long OPEN_FADE_DURATION = 350;

    public GuiChatScreen() {
        this(null);
    }

    public GuiChatScreen(String initialContactId) {
        super();
        this.initialContactId = initialContactId;
        this.contactList = new GuiContactList();
        this.messagePanel = new GuiMessagePanel();
        this.shaderBlur = new ShaderBlur();

        contactList.setOnContactSelected(id -> {
            messagePanel.setContactAndReset(id);
            ChatDataManager.clearUnread(id);
        });

        if (initialContactId != null) {
            contactList.setSelectedContactId(initialContactId);
            messagePanel.setContactAndReset(initialContactId);
        } else {
            List<ChatContact> sorted = ChatDataManager.getContactsSorted();
            if (!sorted.isEmpty()) {
                String firstId = sorted.get(0).getId();
                contactList.setSelectedContactId(firstId);
                messagePanel.setContactAndReset(firstId);
            }
        }
    }

    @Override
    public void initGui() {
        super.initGui();
        if (openTime == 0) openTime = System.currentTimeMillis();
    }

    @Override
    public void updateScreen() {
        messagePanel.updateTick();
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE || keyCode == Keyboard.KEY_U) {
            mc.displayGuiScreen(null);
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        shaderBlur.renderBlurredBackground(width, height);

        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        Gui.drawRect(0, 0, width, height, 0x60000000);
        GlStateManager.disableBlend();

        int innerX = MARGIN;
        int innerY = MARGIN;
        int innerW = width - MARGIN * 2;
        int innerH = height - MARGIN * 2;

        drawOuterFrame(innerX, innerY, innerW, innerH);

        int contactW = (int)(innerW * CONTACT_RATIO);
        int msgX = innerX + contactW + 1;
        int msgW = innerW - contactW - 1;

        contactList.draw(innerX, innerY, contactW, innerH, mouseX, mouseY);

        Gui.drawRect(innerX + contactW, innerY, innerX + contactW + 1, innerY + innerH, BORDER_COLOR);

        messagePanel.draw(msgX, innerY, msgW, innerH, mouseX, mouseY);

        drawOpeningFade(width, height);
    }

    private void drawOpeningFade(int w, int h) {
        long elapsed = System.currentTimeMillis() - openTime;
        if (elapsed >= OPEN_FADE_DURATION) return;
        float progress = 1.0f - (float) elapsed / OPEN_FADE_DURATION;
        progress = (float)(Math.sin((progress - 0.5) * Math.PI) * 0.5 + 0.5);
        int alpha = (int)(progress * 220) & 0xFF;
        Gui.drawRect(0, 0, w, h, (alpha << 24) | 0x000000);
    }

    private void drawOuterFrame(int x, int y, int w, int h) {
        int cornerLen = 12;
        Gui.drawRect(x, y, x + cornerLen, y + 1, BORDER_COLOR);
        Gui.drawRect(x, y, x + 1, y + cornerLen, BORDER_COLOR);
        Gui.drawRect(x + w - cornerLen, y, x + w, y + 1, BORDER_COLOR);
        Gui.drawRect(x + w - 1, y, x + w, y + cornerLen, BORDER_COLOR);
        Gui.drawRect(x, y + h - 1, x + cornerLen, y + h, BORDER_COLOR);
        Gui.drawRect(x, y + h - cornerLen, x + 1, y + h, BORDER_COLOR);
        Gui.drawRect(x + w - cornerLen, y + h - 1, x + w, y + h, BORDER_COLOR);
        Gui.drawRect(x + w - 1, y + h - cornerLen, x + w, y + h, BORDER_COLOR);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (mouseButton != 0) return;

        int innerX = MARGIN;
        int innerY = MARGIN;
        int innerW = width - MARGIN * 2;
        int innerH = height - MARGIN * 2;
        int contactW = (int)(innerW * CONTACT_RATIO);
        int msgX = innerX + contactW + 1;
        int msgW = innerW - contactW - 1;

        if (mouseX >= innerX && mouseX <= innerX + contactW) {
            contactList.mouseClicked(mouseX, mouseY, innerX, innerY, contactW, innerH);
        } else if (mouseX >= msgX) {
            messagePanel.mouseClicked(mouseX, mouseY, msgX, innerY, msgW, innerH);
        }
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int innerX = MARGIN;
        int innerY = MARGIN;
        int innerW = width - MARGIN * 2;
        int innerH = height - MARGIN * 2;
        int contactW = (int)(innerW * CONTACT_RATIO);
        int msgX = innerX + contactW + 1;
        int msgW = innerW - contactW - 1;

        int mx = org.lwjgl.input.Mouse.getEventX() * width / mc.displayWidth;
        int my = height - org.lwjgl.input.Mouse.getEventY() * height / mc.displayHeight - 1;

        if (mx >= innerX && mx <= innerX + contactW) {
            contactList.handleMouseInput(innerX, innerY, contactW, innerH);
        } else if (mx >= msgX) {
            messagePanel.handleMouseInput(msgX, innerY, msgW, innerH);
        }
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        shaderBlur.cleanup();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
