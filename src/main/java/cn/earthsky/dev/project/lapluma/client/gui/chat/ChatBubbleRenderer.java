package cn.earthsky.dev.project.lapluma.client.gui.chat;

import cn.earthsky.dev.project.lapluma.client.gui.chat.data.ChatContact;
import cn.earthsky.dev.project.lapluma.client.gui.chat.data.ChatDataManager;
import cn.earthsky.dev.project.lapluma.client.gui.chat.data.ChatMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;

import java.util.List;

public class ChatBubbleRenderer {

    private static final int BUBBLE_PADDING_H = 8;
    private static final int BUBBLE_PADDING_V = 5;
    private static final int AVATAR_SIZE = 16;
    private static final int AVATAR_GAP = 4;
    private static final int CUT_SIZE = 6;
    private static final int LINE_HEIGHT = 10;

    private static final int NPC_BG = 0xCC2A2D35;
    private static final int PLAYER_BG = 0xCC3D5A80;
    private static final int SYSTEM_BG = 0x80404040;
    private static final int NPC_TEXT = 0xFFE0E0E0;
    private static final int PLAYER_TEXT = 0xFFFFFFFF;
    private static final int SYSTEM_TEXT = 0xFF999999;
    private static final int TIME_COLOR = 0xFF666666;
    private static final String PLAYER_ID = "$player";

    private static int getMaxTextWidth(int panelWidth) {
        int usable = panelWidth - AVATAR_SIZE - AVATAR_GAP - 6 - 6;
        int maxBubble = Math.min((int)(panelWidth * 0.65), usable - BUBBLE_PADDING_H * 2);
        return Math.max(maxBubble, 40);
    }

    public static int measureHeight(ChatMessage msg, int panelWidth) {
        FontRenderer fr = Minecraft.getMinecraft().fontRenderer;
        if (msg.getType() == ChatMessage.MessageType.SYSTEM) {
            return LINE_HEIGHT + 8;
        }
        int textWidth = getMaxTextWidth(panelWidth);
        List<String> lines = fr.listFormattedStringToWidth(msg.getContent(), textWidth);
        return BUBBLE_PADDING_V * 2 + lines.size() * LINE_HEIGHT + 2;
    }

    public static void drawBubble(ChatMessage msg, int x, int y, int panelWidth) {
        drawBubble(msg, x, y, panelWidth, 1.0f);
    }

    public static void drawBubble(ChatMessage msg, int x, int y, int panelWidth, float alpha) {
        if (msg.getType() == ChatMessage.MessageType.SYSTEM) {
            drawSystemMessage(msg, x, y, panelWidth, alpha);
            return;
        }
        boolean isPlayer = PLAYER_ID.equals(msg.getSenderId());
        FontRenderer fr = Minecraft.getMinecraft().fontRenderer;
        int textWidth = getMaxTextWidth(panelWidth);
        List<String> lines = fr.listFormattedStringToWidth(msg.getContent(), textWidth);

        int contentW = 0;
        for (String line : lines) contentW = Math.max(contentW, fr.getStringWidth(line));
        int bubbleW = contentW + BUBBLE_PADDING_H * 2;
        int bubbleH = BUBBLE_PADDING_V * 2 + lines.size() * LINE_HEIGHT;

        int margin = 6;
        int avatarX, bubbleX;
        if (isPlayer) {
            avatarX = x + panelWidth - AVATAR_SIZE - margin;
            bubbleX = avatarX - AVATAR_GAP - bubbleW;
            if (bubbleX < x + margin) bubbleX = x + margin;
        } else {
            avatarX = x + margin;
            bubbleX = avatarX + AVATAR_SIZE + AVATAR_GAP;
        }

        boolean showName = !isPlayer && msg.getSenderName() != null && !msg.getSenderName().isEmpty();
        int avatarY = showName ? y - LINE_HEIGHT : y;

        GlStateManager.color(1f, 1f, 1f, alpha);
        if (isPlayer) {
            drawPlayerHead(avatarX, avatarY);
        } else {
            ChatContact senderContact = ChatDataManager.getContacts().get(msg.getSenderId());
            if (senderContact != null && senderContact.getSkin() != null && !senderContact.getSkin().isEmpty()) {
                drawSkinHead(senderContact.getSkin(), avatarX, avatarY);
            } else {
                drawIcon(avatarX, avatarY);
            }
        }
        GlStateManager.color(1f, 1f, 1f, 1f);

        int bgColor = isPlayer ? PLAYER_BG : NPC_BG;
        boolean cutTopLeft = !isPlayer;
        boolean cutTopRight = isPlayer;
        int fadedBg = applyAlpha(bgColor, alpha);
        drawCutCornerRect(bubbleX, y, bubbleW, bubbleH, fadedBg, cutTopLeft, cutTopRight);

        int textColor = isPlayer ? PLAYER_TEXT : NPC_TEXT;
        int fadedText = applyAlpha(textColor, alpha);
        int textX = bubbleX + BUBBLE_PADDING_H;
        int textY = y + BUBBLE_PADDING_V;
        for (String line : lines) {
            fr.drawStringWithShadow(line, textX, textY, fadedText);
            textY += LINE_HEIGHT;
        }

        if (showName) {
            fr.drawStringWithShadow(msg.getSenderName(), bubbleX + BUBBLE_PADDING_H, y - LINE_HEIGHT, applyAlpha(0xFF8899AA, alpha));
        }
    }

    private static int applyAlpha(int color, float alpha) {
        int a = (int)((color >> 24 & 0xFF) * alpha) & 0xFF;
        return (a << 24) | (color & 0x00FFFFFF);
    }

    private static void drawSystemMessage(ChatMessage msg, int x, int y, int panelWidth, float alpha) {
        FontRenderer fr = Minecraft.getMinecraft().fontRenderer;
        int textW = fr.getStringWidth(msg.getContent());
        int cx = x + panelWidth / 2 - textW / 2;
        fr.drawStringWithShadow(msg.getContent(), cx, y + 2, applyAlpha(SYSTEM_TEXT, alpha));
    }

    private static void drawIcon(int x, int y) {
        Minecraft mc = Minecraft.getMinecraft();
        try {
            mc.getTextureManager().bindTexture(new ResourceLocation("lapluma", "chat/skin/contact_default.png"));
            Gui.drawModalRectWithCustomSizedTexture(x, y, 0, 0, AVATAR_SIZE, AVATAR_SIZE, AVATAR_SIZE, AVATAR_SIZE);
        } catch (Throwable ignored) {
            Gui.drawRect(x, y, x + AVATAR_SIZE, y + AVATAR_SIZE, 0xFF555555);
        }
    }

    public static void drawSkinHead(String skinPath, int x, int y) {
        Minecraft mc = Minecraft.getMinecraft();
        try {
            ResourceLocation skinLoc = new ResourceLocation("lapluma", skinPath + ".png");
            mc.getTextureManager().bindTexture(skinLoc);
            Gui.drawScaledCustomSizeModalRect(x, y, 8, 8, 8, 8, AVATAR_SIZE, AVATAR_SIZE, 64, 64);
            Gui.drawScaledCustomSizeModalRect(x, y, 40, 8, 8, 8, AVATAR_SIZE, AVATAR_SIZE, 64, 64);
        } catch (Throwable ignored) {
            Gui.drawRect(x, y, x + AVATAR_SIZE, y + AVATAR_SIZE, 0xFF555555);
        }
    }

    private static void drawPlayerHead(int x, int y) {
        Minecraft mc = Minecraft.getMinecraft();
        try {
            if (mc.player instanceof AbstractClientPlayer) {
                ResourceLocation skin = ((AbstractClientPlayer) mc.player).getLocationSkin();
                mc.getTextureManager().bindTexture(skin);
                Gui.drawScaledCustomSizeModalRect(x, y, 8, 8, 8, 8, AVATAR_SIZE, AVATAR_SIZE, 64, 64);
                Gui.drawScaledCustomSizeModalRect(x, y, 40, 8, 8, 8, AVATAR_SIZE, AVATAR_SIZE, 64, 64);
            }
        } catch (Throwable ignored) {
            Gui.drawRect(x, y, x + AVATAR_SIZE, y + AVATAR_SIZE, 0xFF555555);
        }
    }

    private static void drawCutCornerRect(int x, int y, int w, int h, int color, boolean cutTL, boolean cutTR) {
        float a = (color >> 24 & 0xFF) / 255f;
        float r = (color >> 16 & 0xFF) / 255f;
        float g = (color >> 8 & 0xFF) / 255f;
        float b = (color & 0xFF) / 255f;

        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        GlStateManager.color(r, g, b, a);

        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        buf.begin(GL11.GL_POLYGON, DefaultVertexFormats.POSITION);

        if (cutTL) {
            buf.pos(x + CUT_SIZE, y, 0).endVertex();
        } else {
            buf.pos(x, y, 0).endVertex();
        }
        if (cutTR) {
            buf.pos(x + w - CUT_SIZE, y, 0).endVertex();
            buf.pos(x + w, y + CUT_SIZE, 0).endVertex();
        } else {
            buf.pos(x + w, y, 0).endVertex();
        }
        buf.pos(x + w, y + h, 0).endVertex();
        buf.pos(x, y + h, 0).endVertex();
        if (cutTL) {
            buf.pos(x, y + CUT_SIZE, 0).endVertex();
        }

        tess.draw();

        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    public static void drawReplyOptions(List<String> options, int x, int y, int panelWidth, int mouseX, int mouseY) {
        drawReplyOptions(options, x, y, panelWidth, mouseX, mouseY, 1.0f);
    }

    public static void drawReplyOptions(List<String> options, int x, int y, int panelWidth, int mouseX, int mouseY, float alpha) {
        if (options == null || options.isEmpty()) return;
        FontRenderer fr = Minecraft.getMinecraft().fontRenderer;
        int margin = 10;
        int optW = panelWidth - margin * 2;
        int optH = 16;
        int gap = 2;
        int optX = x + margin;
        int optY = y;

        Gui.drawRect(optX, optY - 4, optX + optW, optY - 3, applyAlpha(0x40FFFFFF, alpha));

        for (int i = 0; i < options.size(); i++) {
            String opt = options.get(i);
            String label = "§e[" + (i + 1) + "]§r " + opt;
            boolean hovered = mouseX >= optX && mouseX <= optX + optW && mouseY >= optY && mouseY < optY + optH;
            int bg = hovered ? applyAlpha(0xCC4A7AAF, alpha) : applyAlpha(0xCC2A3040, alpha);
            drawCutCornerRect(optX, optY, optW, optH, bg, false, true);
            Gui.drawRect(optX, optY + optH, optX + optW, optY + optH + 1, applyAlpha(0x30FFFFFF, alpha));
            fr.drawStringWithShadow(label, optX + 6, optY + 4, hovered ? applyAlpha(0xFFFFFFFF, alpha) : applyAlpha(0xFFCCCCCC, alpha));
            optY += optH + gap;
        }
    }

    public static int measureReplyOptionsHeight(List<String> options) {
        if (options == null || options.isEmpty()) return 0;
        return options.size() * 18 + 4;
    }

    public static int getClickedOption(List<String> options, int x, int y, int panelWidth, int mouseX, int mouseY) {
        if (options == null || options.isEmpty()) return -1;
        int margin = 10;
        int optW = panelWidth - margin * 2;
        int optH = 16;
        int gap = 2;
        int optX = x + margin;
        int optY = y;
        for (int i = 0; i < options.size(); i++) {
            if (mouseX >= optX && mouseX <= optX + optW && mouseY >= optY && mouseY < optY + optH) {
                return i;
            }
            optY += optH + gap;
        }
        return -1;
    }
}
