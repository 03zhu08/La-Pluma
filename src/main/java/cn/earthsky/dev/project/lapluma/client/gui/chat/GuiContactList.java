package cn.earthsky.dev.project.lapluma.client.gui.chat;

import cn.earthsky.dev.project.lapluma.client.gui.chat.data.ChatContact;
import cn.earthsky.dev.project.lapluma.client.gui.chat.data.ChatDataManager;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import java.util.List;
import java.util.function.Consumer;

public class GuiContactList {

    private static final int ENTRY_HEIGHT = 32;
    private static final int AVATAR_SIZE = 20;
    private static final int PADDING = 4;
    private static final int BG_COLOR = 0xB0181C24;
    private static final int SELECTED_COLOR = 0xC0283040;
    private static final int HOVER_COLOR = 0x80283040;
    private static final int SEPARATOR_COLOR = 0x40FFFFFF;
    private static final int CONV_HEIGHT = 18;
    private static final int CONV_INDENT = 14;

    @Getter @Setter private String selectedContactId;
    @Setter private Consumer<String> onContactSelected;
    private int scrollOffset = 0;
    private String expandedContactId = null;
    private String animatingContactId = null;
    private long expandAnimStart = 0;
    private boolean expandOpening = false;
    private static final long EXPAND_DURATION = 200;

    public void draw(int x, int y, int w, int h, int mouseX, int mouseY) {
        Gui.drawRect(x, y, x + w, y + h, BG_COLOR);

        FontRenderer fr = Minecraft.getMinecraft().fontRenderer;
        fr.drawStringWithShadow("§l联系人", x + PADDING + 2, y + 4, 0xFFCCCCCC);
        Gui.drawRect(x, y + 14, x + w, y + 15, SEPARATOR_COLOR);

        List<ChatContact> contacts = ChatDataManager.getContactsSorted();
        int listY = y + 16;
        int contentH = h - 16;

        int totalHeight = computeTotalHeight(contacts);
        int maxScroll = Math.max(0, totalHeight - contentH);
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));

        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        Minecraft mc = Minecraft.getMinecraft();
        ScaledResolution sr = new ScaledResolution(mc);
        int sf = sr.getScaleFactor();
        GL11.glScissor(x * sf, (mc.displayHeight - (y + h) * sf), w * sf, contentH * sf);

        int drawY = listY - scrollOffset;
        for (int ci = 0; ci < contacts.size(); ci++) {
            ChatContact contact = contacts.get(ci);
            if (drawY + ENTRY_HEIGHT >= listY && drawY <= y + h) {
                drawContactEntry(contact, x, drawY, w, mouseX, mouseY, fr);
            }
            drawY += ENTRY_HEIGHT;

            float expandMult = getExpandMultiplier(contact.getId());
            if (expandMult > 0.0f) {
                List<ChatDataManager.Conversation> convs = ChatDataManager.getConversationsForContact(contact.getId());
                ChatDataManager.Conversation activeConv = ChatDataManager.getActiveConversation(contact.getId());
                int animConvH = Math.round(CONV_HEIGHT * expandMult);
                for (ChatDataManager.Conversation conv : convs) {
                    if (drawY + animConvH >= listY && drawY <= y + h) {
                        drawConvEntry(conv, activeConv, x, drawY, w, mouseX, mouseY, fr, expandMult);
                    }
                    drawY += animConvH;
                }
            }
        }

        GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }

    private float getExpandMultiplier(String contactId) {
        if (contactId.equals(expandedContactId) && !contactId.equals(animatingContactId)) {
            return 1.0f;
        }
        if (contactId.equals(animatingContactId)) {
            long elapsed = System.currentTimeMillis() - expandAnimStart;
            float progress = Math.min(1.0f, (float) elapsed / EXPAND_DURATION);
            if (!expandOpening) {
                progress = 1.0f - progress;
                if (progress <= 0.0f) {
                    animatingContactId = null;
                    return 0.0f;
                }
            } else if (progress >= 1.0f) {
                animatingContactId = null;
                return 1.0f;
            }
            return (float)(Math.sin((progress - 0.5) * Math.PI) * 0.5 + 0.5);
        }
        return 0.0f;
    }

    private int computeTotalHeight(List<ChatContact> contacts) {
        int h = 0;
        for (ChatContact c : contacts) {
            h += ENTRY_HEIGHT;
            float mult = getExpandMultiplier(c.getId());
            if (mult > 0.0f) {
                int convCount = ChatDataManager.getConversationsForContact(c.getId()).size();
                h += Math.round(convCount * CONV_HEIGHT * mult);
            }
        }
        return h;
    }

    private void drawContactEntry(ChatContact contact, int x, int y, int w, int mouseX, int mouseY, FontRenderer fr) {
        boolean selected = contact.getId().equals(selectedContactId);
        boolean hovered = mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY < y + ENTRY_HEIGHT;

        int textX = x + PADDING + AVATAR_SIZE + PADDING;

        if (selected) {
            Gui.drawRect(textX, y, x + w - 1, y + ENTRY_HEIGHT, SELECTED_COLOR);
            Gui.drawRect(x, y, x + 2, y + ENTRY_HEIGHT, 0xFFFF5733);
        } else if (hovered) {
            Gui.drawRect(textX, y, x + w - 1, y + ENTRY_HEIGHT, HOVER_COLOR);
        }

        drawContactAvatar(contact, x + PADDING, y + (ENTRY_HEIGHT - AVATAR_SIZE) / 2);
        int nameColor = selected ? 0xFFFFFFFF : 0xFFCCCCCC;
        fr.drawStringWithShadow(contact.getName(), textX, y + 4, nameColor);

        if (contact.getFaction() != null && !contact.getFaction().isEmpty()) {
            try {
                Minecraft.getMinecraft().getTextureManager().bindTexture(
                        new ResourceLocation("lapluma", "chat/skin/faction_" + contact.getFaction() + ".png"));
                GlStateManager.color(1f, 1f, 1f, 0.7f);
                Gui.drawModalRectWithCustomSizedTexture(textX + fr.getStringWidth(contact.getName()) + 2, y + 4, 0, 0, 8, 8, 8, 8);
                GlStateManager.color(1f, 1f, 1f, 1f);
            } catch (Throwable ignored) {}
        }

        List<ChatDataManager.Conversation> convs = ChatDataManager.getConversationsForContact(contact.getId());
        boolean hasConvs = convs.size() > 1;

        if (contact.getLastMessage() != null) {
            String preview = contact.getLastMessage();
            int maxW = w - PADDING * 3 - AVATAR_SIZE - 4;
            if (hasConvs) maxW -= 16;
            if (fr.getStringWidth(preview) > maxW) {
                preview = fr.trimStringToWidth(preview, maxW - fr.getStringWidth("...")) + "...";
            }
            fr.drawStringWithShadow(preview, textX, y + 16, 0xFF777777);
        }

        // Completed checkmark — only when single conversation (no dropdown)
        ChatDataManager.Conversation activeConv = ChatDataManager.getActiveConversation(contact.getId());
        boolean isCompleted = activeConv != null && "completed".equals(activeConv.status);
        if (isCompleted && !hasConvs) {
            String check = "§a✓";
            int checkW = fr.getStringWidth(check.replace("§a", ""));
            int checkX = x + w - checkW - 4;
            fr.drawString(check, checkX, y + 4, 0xFF66BB66);
        }

        // Unread badge
        if (contact.getUnreadCount() > 0 && (!isCompleted || hasConvs)) {
            String badge = String.valueOf(contact.getUnreadCount());
            int badgeW = Math.max(fr.getStringWidth(badge) + 4, 10);
            int badgeX = x + w - badgeW - 4;
            if (hasConvs) badgeX -= 16;
            int badgeY = y + 4;
            Gui.drawRect(badgeX, badgeY, badgeX + badgeW, badgeY + 10, 0xFFFF4444);
            fr.drawString(badge, badgeX + (badgeW - fr.getStringWidth(badge)) / 2, badgeY + 1, 0xFFFFFFFF);
        }

        // Expand button
        if (hasConvs) {
            int btnX = x + w - 20;
            int btnY = y + (ENTRY_HEIGHT - 12) / 2;
            int btnW = 14;
            int btnH = 12;
            boolean btnHovered = mouseX >= btnX && mouseX <= btnX + btnW && mouseY >= btnY && mouseY < btnY + btnH;
            int btnBg = btnHovered ? 0x80666666 : 0x40444444;
            Gui.drawRect(btnX, btnY, btnX + btnW, btnY + btnH, btnBg);
            String arrow = contact.getId().equals(expandedContactId) ? "▲" : "▼";
            int arrowW = fr.getStringWidth(arrow);
            fr.drawString(arrow, btnX + (btnW - arrowW) / 2, btnY + 2, 0xFFAAAAAA);
        }

        Gui.drawRect(x + PADDING, y + ENTRY_HEIGHT - 1, x + w - PADDING, y + ENTRY_HEIGHT, 0x20FFFFFF);
    }

    private void drawConvEntry(ChatDataManager.Conversation conv, ChatDataManager.Conversation activeConv,
                               int x, int y, int w, int mouseX, int mouseY, FontRenderer fr, float alpha) {
        int h = Math.round(CONV_HEIGHT * alpha);
        if (h <= 0) return;
        boolean isActive = activeConv != null && conv.conversationId.equals(activeConv.conversationId);
        boolean hovered = mouseX >= x + CONV_INDENT && mouseX <= x + w - PADDING
                && mouseY >= y && mouseY < y + h;

        if (hovered) {
            Gui.drawRect(x + CONV_INDENT, y, x + w - PADDING, y + h, applyAlpha(0x804A7AAF, alpha));
        } else if (isActive) {
            Gui.drawRect(x + CONV_INDENT, y, x + w - PADDING, y + h, applyAlpha(0x303D5A80, alpha));
        }

        int leftBar = isActive ? 0xFFFFAA33 : 0x40666666;
        Gui.drawRect(x + CONV_INDENT, y, x + CONV_INDENT + 2, y + h, applyAlpha(leftBar, alpha));

        int textColor = isActive ? 0xFFFFFFFF : (hovered ? 0xFFDDDDDD : 0xFF999999);
        String label = isActive ? "§l" + conv.title : conv.title;
        fr.drawStringWithShadow(label, x + CONV_INDENT + 8, y + 3, applyAlpha(textColor, alpha));

        if ("completed".equals(conv.status)) {
            String check = "§a✓";
            int checkW = fr.getStringWidth(check.replace("§a", ""));
            fr.drawString(check, x + w - PADDING - checkW, y + 3, applyAlpha(0xFF66BB66, alpha));
        }
    }

    private static int applyAlpha(int color, float alpha) {
        int a = (int)((color >> 24 & 0xFF) * alpha) & 0xFF;
        return (a << 24) | (color & 0x00FFFFFF);
    }

    private void drawContactAvatar(ChatContact contact, int x, int y) {
        Minecraft mc = Minecraft.getMinecraft();
        try {
            String skin = contact.getSkin();
            if (skin != null && !skin.isEmpty()) {
                mc.getTextureManager().bindTexture(new ResourceLocation("lapluma", skin + ".png"));
                // Crop head from 64x64 skin: front at UV(8,8), hat at UV(40,8)
                Gui.drawScaledCustomSizeModalRect(x, y, 8, 8, 8, 8, AVATAR_SIZE, AVATAR_SIZE, 64, 64);
                Gui.drawScaledCustomSizeModalRect(x, y, 40, 8, 8, 8, AVATAR_SIZE, AVATAR_SIZE, 64, 64);
            } else {
                ResourceLocation def = contact.isGroup()
                        ? new ResourceLocation("lapluma", "chat/skin/group_default.png")
                        : new ResourceLocation("lapluma", "chat/skin/contact_default.png");
                mc.getTextureManager().bindTexture(def);
                Gui.drawModalRectWithCustomSizedTexture(x, y, 0, 0, AVATAR_SIZE, AVATAR_SIZE, AVATAR_SIZE, AVATAR_SIZE);
            }
            GlStateManager.color(1f, 1f, 1f, 1f);
        } catch (Throwable ignored) {
            Gui.drawRect(x, y, x + AVATAR_SIZE, y + AVATAR_SIZE, 0xFF444444);
        }
    }

    public void handleMouseInput(int x, int y, int w, int h) {
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            scrollOffset -= Integer.signum(wheel) * 24;
            int totalHeight = computeTotalHeight(ChatDataManager.getContactsSorted());
            int maxScroll = Math.max(0, totalHeight - (h - 16));
            scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));
        }
    }

    public void mouseClicked(int mouseX, int mouseY, int x, int y, int w, int h) {
        if (mouseX < x || mouseX > x + w || mouseY < y + 16 || mouseY > y + h) return;

        List<ChatContact> contacts = ChatDataManager.getContactsSorted();
        int listY = y + 16;

        int drawY = listY - scrollOffset;
        for (ChatContact contact : contacts) {
            // Check expand button first
            if (drawY + ENTRY_HEIGHT >= listY && drawY <= y + h) {
                List<ChatDataManager.Conversation> convs = ChatDataManager.getConversationsForContact(contact.getId());
                if (convs.size() > 1) {
                    int btnX = x + w - 20;
                    int btnY = drawY + (ENTRY_HEIGHT - 12) / 2;
                    int btnW = 14;
                    int btnH = 12;
                    if (mouseX >= btnX && mouseX <= btnX + btnW && mouseY >= btnY && mouseY < btnY + btnH) {
                        if (contact.getId().equals(expandedContactId)) {
                            animatingContactId = expandedContactId;
                            expandAnimStart = System.currentTimeMillis();
                            expandOpening = false;
                            expandedContactId = null;
                        } else {
                            expandedContactId = contact.getId();
                            animatingContactId = contact.getId();
                            expandAnimStart = System.currentTimeMillis();
                            expandOpening = true;
                        }
                        return;
                    }
                }

                // Check contact click (not on button)
                if (mouseY >= drawY && mouseY < drawY + ENTRY_HEIGHT) {
                    selectedContactId = contact.getId();
                    ChatDataManager.clearUnread(contact.getId());
                    if (onContactSelected != null) onContactSelected.accept(contact.getId());
                    return;
                }
            }
            drawY += ENTRY_HEIGHT;

            // Check conversation sub-items
            float expandMult = getExpandMultiplier(contact.getId());
            if (expandMult > 0.0f) {
                List<ChatDataManager.Conversation> convs = ChatDataManager.getConversationsForContact(contact.getId());
                int animConvH = Math.round(CONV_HEIGHT * expandMult);
                for (ChatDataManager.Conversation conv : convs) {
                    if (drawY + animConvH >= listY && drawY <= y + h) {
                        if (mouseX >= x + CONV_INDENT && mouseX <= x + w - PADDING
                                && mouseY >= drawY && mouseY < drawY + animConvH) {
                            ChatDataManager.switchConversation(contact.getId(), conv.conversationId);
                            selectedContactId = contact.getId();
                            ChatDataManager.clearUnread(contact.getId());
                            if (onContactSelected != null) onContactSelected.accept(contact.getId());
                            return;
                        }
                    }
                    drawY += animConvH;
                }
            }
        }
    }
}
