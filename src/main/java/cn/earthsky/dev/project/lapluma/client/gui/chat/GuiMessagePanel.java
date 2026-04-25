package cn.earthsky.dev.project.lapluma.client.gui.chat;

import cn.earthsky.dev.project.lapluma.client.gui.chat.data.ChatContact;
import cn.earthsky.dev.project.lapluma.client.gui.chat.data.ChatDataManager;
import cn.earthsky.dev.project.lapluma.client.gui.chat.data.ChatMessage;
import cn.earthsky.dev.project.lapluma.common.network.ProxyPacketHandler;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import java.util.List;

public class GuiMessagePanel {

    private static final int HEADER_HEIGHT = 18;
    private static final int MSG_GAP = 6;
    private static final int REPLY_AREA_GAP = 8;
    private static final int BG_COLOR = 0x90101418;
    private static final int HEADER_BG = 0xC0181C24;
    private static final int TYPING_COLOR = 0xFF5588AA;
    private static final long FADE_DURATION = 400;
    private static final long SWITCH_DURATION = 300;

    @Getter @Setter private String contactId;
    private int scrollOffset = 0;
    private int typingAnimTick = 0;

    public void updateTick() {
        typingAnimTick++;
    }

    public void setContactAndReset(String id) {
        if (!id.equals(this.contactId)) {
            this.switchTime = System.currentTimeMillis();
            this.switchFading = true;
        }
        this.contactId = id;
        this.scrollOffset = 0;
        this.previousOptions = null;
        this.lastOptsAppearTime = 0;
    }

    public void draw(int x, int y, int w, int h, int mouseX, int mouseY) {
        Gui.drawRect(x, y, x + w, y + h, BG_COLOR);

        drawDecoLines(x, y, w, h);

        FontRenderer fr = Minecraft.getMinecraft().fontRenderer;

        Gui.drawRect(x, y, x + w, y + HEADER_HEIGHT, HEADER_BG);
        String title = "";
        if (contactId != null) {
            ChatContact contact = ChatDataManager.getContacts().get(contactId);
            if (contact != null) title = contact.getName();
        }
        fr.drawStringWithShadow("§l" + title, x + 8, y + 5, 0xFFDDDDDD);
        Gui.drawRect(x, y + HEADER_HEIGHT - 1, x + w, y + HEADER_HEIGHT, 0x40FF5733);

        if (contactId == null) {
            fr.drawStringWithShadow("选择一个联系人开始对话", x + w / 2 - fr.getStringWidth("选择一个联系人开始对话") / 2, y + h / 2, 0xFF555555);
            return;
        }

        List<ChatMessage> msgs = ChatDataManager.getMessages(contactId);
        int contentY = y + HEADER_HEIGHT + 4;
        int contentH = h - HEADER_HEIGHT - 4;

        int totalHeight = 0;
        for (ChatMessage msg : msgs) {
            boolean showName = msg.getType() != ChatMessage.MessageType.SYSTEM && !"$player".equals(msg.getSenderId());
            totalHeight += ChatBubbleRenderer.measureHeight(msg, w) + MSG_GAP;
            if (showName) totalHeight += 10;
        }

        ChatMessage lastMsg = msgs.isEmpty() ? null : msgs.get(msgs.size() - 1);
        int replyAreaH = 0;

        List<String> replyOpts = null;
        if (lastMsg != null && lastMsg.getReplyOptions() != null && !lastMsg.getReplyOptions().isEmpty()) {
            replyOpts = lastMsg.getReplyOptions();
        } else {
            ChatDataManager.Conversation conv = ChatDataManager.getConversation(contactId);
            if (conv != null && !conv.finished && !conv.isTyping && conv.hasNextMessage()) {
                ChatMessage next = conv.peekNext();
                if (next.getReplyOptions() != null && !next.getReplyOptions().isEmpty()) {
                    replyOpts = next.getReplyOptions();
                }
            }
        }
        if (replyOpts != null) {
            replyAreaH = ChatBubbleRenderer.measureReplyOptionsHeight(replyOpts) + REPLY_AREA_GAP;
        }
        totalHeight += replyAreaH;

        int maxScroll = Math.max(0, totalHeight - contentH);
        scrollOffset = Math.min(scrollOffset, maxScroll);

        Minecraft mc = Minecraft.getMinecraft();
        ScaledResolution sr = new ScaledResolution(mc);
        int sf = sr.getScaleFactor();
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(x * sf, mc.displayHeight - (y + h) * sf, w * sf, contentH * sf);

        int drawY = contentY - scrollOffset;
        for (int i = 0; i < msgs.size(); i++) {
            ChatMessage msg = msgs.get(i);
            boolean showName = msg.getType() != ChatMessage.MessageType.SYSTEM && !"$player".equals(msg.getSenderId());
            if (showName) drawY += 10;
            float alpha = computeFadeAlpha(msg, i == msgs.size() - 1);
            ChatBubbleRenderer.drawBubble(msg, x, drawY, w, alpha);
            drawY += ChatBubbleRenderer.measureHeight(msg, w) + MSG_GAP;
        }

        List<String> activeReplyOptions = null;
        if (lastMsg != null && lastMsg.getReplyOptions() != null && !lastMsg.getReplyOptions().isEmpty()) {
            activeReplyOptions = lastMsg.getReplyOptions();
        } else {
            ChatDataManager.Conversation conv = ChatDataManager.getConversation(contactId);
            if (conv != null && !conv.finished && !conv.isTyping && conv.hasNextMessage()) {
                ChatMessage next = conv.peekNext();
                if (next.getReplyOptions() != null && !next.getReplyOptions().isEmpty()) {
                    activeReplyOptions = next.getReplyOptions();
                }
            }
        }

        if (activeReplyOptions != previousOptions) {
            if (activeReplyOptions != null) markOptionsAppeared();
            previousOptions = activeReplyOptions;
        }

        if (activeReplyOptions != null) {
            drawY += REPLY_AREA_GAP;
            float optAlpha = computeOptionsFadeAlpha();
            ChatBubbleRenderer.drawReplyOptions(activeReplyOptions, x, drawY, w, mouseX, mouseY, optAlpha);
        }

        if (ChatDataManager.isTyping(contactId)) {
            int dots = (typingAnimTick / 10) % 4;
            String typing = "对方正在输入" + new String(new char[dots]).replace('\0', '.');
            fr.drawStringWithShadow(typing, x + 30, y + h - 14, TYPING_COLOR);
        } else {
            ChatDataManager.Conversation conv = ChatDataManager.getConversation(contactId);
            if (conv != null && !conv.finished && conv.hasNextMessage()) {
                ChatMessage next = conv.peekNext();
                boolean waitingReply = next.getReplyOptions() != null && !next.getReplyOptions().isEmpty();
                if (!waitingReply) {
                    float alpha = (float)(Math.sin(typingAnimTick * 0.12) * 0.3 + 0.7);
                    int alphaInt = (int)(alpha * 255) & 0xFF;
                    int hintColor = (alphaInt << 24) | 0xDDAA44;

                    String arrow = "▼";
                    String hint = " 点击界面继续...";
                    String full = arrow + hint;
                    int fullW = fr.getStringWidth(full);
                    int hintX = x + w / 2 - fullW / 2;
                    int hintY = y + h - 14;

                    fr.drawStringWithShadow(full, hintX, hintY, hintColor);
                }
            }
        }

        GL11.glDisable(GL11.GL_SCISSOR_TEST);

        drawScrollEdgeFade(x, y + HEADER_HEIGHT, w, contentH, scrollOffset, maxScroll);

        if (switchFading) {
            long elapsed = System.currentTimeMillis() - switchTime;
            if (elapsed >= SWITCH_DURATION) {
                switchFading = false;
            } else {
                float progress;
                long half = SWITCH_DURATION / 2;
                if (elapsed < half) {
                    progress = (float) elapsed / half;
                } else {
                    progress = 1.0f - (float)(elapsed - half) / half;
                }
                progress = (float)(Math.sin((progress - 0.5) * Math.PI) * 0.5 + 0.5);
                int alpha = (int)(progress * 180) & 0xFF;
                Gui.drawRect(x, y, x + w, y + h, (alpha << 24) | 0x000000);
            }
        }
    }

    private void drawScrollEdgeFade(int x, int y, int w, int contentH, int scroll, int maxScroll) {
        if (maxScroll <= 0) return;
        int fadeHeight = 8;
        if (scroll > 0) {
            for (int i = 0; i < fadeHeight; i++) {
                int alpha = (int)(60 * (1.0f - (float)i / fadeHeight));
                Gui.drawRect(x, y + i, x + w, y + i + 1, (alpha << 24) | 0x000000);
            }
        }
        if (scroll < maxScroll) {
            int baseY = y + contentH - fadeHeight;
            for (int i = 0; i < fadeHeight; i++) {
                int alpha = (int)(60 * ((float)i / fadeHeight));
                Gui.drawRect(x, baseY + i, x + w, baseY + i + 1, (alpha << 24) | 0x000000);
            }
        }
    }

    private float computeFadeAlpha(ChatMessage msg, boolean isLast) {
        long revealTime = msg.getRevealTime();
        if (revealTime <= 0) return 1.0f;
        long elapsed = System.currentTimeMillis() - revealTime;
        if (elapsed >= FADE_DURATION) return 1.0f;
        return Math.max(0.05f, (float) elapsed / FADE_DURATION);
    }

    private long lastOptsAppearTime = 0;
    private List<String> previousOptions = null;
    private long switchTime = 0;
    private boolean switchFading = false;

    private float computeOptionsFadeAlpha() {
        if (lastOptsAppearTime == 0) return 1.0f;
        long elapsed = System.currentTimeMillis() - lastOptsAppearTime;
        if (elapsed >= FADE_DURATION) return 1.0f;
        return Math.max(0.05f, (float) elapsed / FADE_DURATION);
    }

    private void markOptionsAppeared() {
        lastOptsAppearTime = System.currentTimeMillis();
    }

    private void drawDecoLines(int x, int y, int w, int h) {
        Gui.drawRect(x + w - 1, y, x + w, y + h, 0x20FF5733);
        Gui.drawRect(x, y + h - 1, x + w, y + h, 0x20FF5733);
    }

    public void handleMouseInput(int x, int y, int w, int h) {
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            scrollOffset -= Integer.signum(wheel) * 12;
            scrollOffset = Math.max(0, scrollOffset);
        }
    }

    public boolean mouseClicked(int mouseX, int mouseY, int x, int y, int w, int h) {
        if (contactId == null) return false;

        List<ChatMessage> msgs = ChatDataManager.getMessages(contactId);

        List<String> activeReplyOptions = null;
        boolean fromUnrevealedNext = false;
        ChatMessage replySourceMsg = null;

        if (!msgs.isEmpty()) {
            ChatMessage lastMsg = msgs.get(msgs.size() - 1);
            if (lastMsg.getReplyOptions() != null && !lastMsg.getReplyOptions().isEmpty()) {
                activeReplyOptions = lastMsg.getReplyOptions();
                replySourceMsg = lastMsg;
            }
        }
        if (activeReplyOptions == null) {
            ChatDataManager.Conversation conv = ChatDataManager.getConversation(contactId);
            if (conv != null && !conv.finished && !conv.isTyping && conv.hasNextMessage()) {
                ChatMessage next = conv.peekNext();
                if (next.getReplyOptions() != null && !next.getReplyOptions().isEmpty()) {
                    activeReplyOptions = next.getReplyOptions();
                    replySourceMsg = next;
                    fromUnrevealedNext = true;
                }
            }
        }

        if (activeReplyOptions != null) {
            ChatDataManager.Conversation convOpts = ChatDataManager.getConversation(contactId);
            if (convOpts != null && convOpts.finished) {
                activeReplyOptions = null;
            }
        }
        if (activeReplyOptions != null) {
            int contentY = y + HEADER_HEIGHT + 4;
            int totalH = 0;
            for (ChatMessage msg : msgs) {
                boolean showName = msg.getType() != ChatMessage.MessageType.SYSTEM && !"$player".equals(msg.getSenderId());
                totalH += ChatBubbleRenderer.measureHeight(msg, w) + MSG_GAP;
                if (showName) totalH += 10;
            }
            int replyY = contentY - scrollOffset + totalH + REPLY_AREA_GAP;
            int clicked = ChatBubbleRenderer.getClickedOption(activeReplyOptions, x, replyY, w, mouseX, mouseY);
            if (clicked >= 0) {
                String option = activeReplyOptions.get(clicked);
                String json = ChatDataManager.buildReplyJson(contactId, replySourceMsg.getId(), option);
                ProxyPacketHandler.sendPacket(25, clicked, json);

                ChatMessage playerReply = new ChatMessage();
                playerReply.setId("reply_" + System.currentTimeMillis());
                playerReply.setSenderId("$player");
                playerReply.setContent(option);
                playerReply.setTimestamp(System.currentTimeMillis());
                playerReply.setType(ChatMessage.MessageType.TEXT);
                playerReply.setRevealTime(System.currentTimeMillis());
                ChatDataManager.addMessage(contactId, playerReply);

                if (fromUnrevealedNext) {
                    replySourceMsg.setReplyOptions(null);
                } else {
                    replySourceMsg.setReplyOptions(null);
                }
                String branchId = null;
                boolean returnsToMain = false;
                if (replySourceMsg.getReplyBranches() != null && clicked < replySourceMsg.getReplyBranches().size()) {
                    branchId = replySourceMsg.getReplyBranches().get(clicked);
                }
                if (replySourceMsg.getReplyReturnsToMain() != null && clicked < replySourceMsg.getReplyReturnsToMain().size()) {
                    returnsToMain = replySourceMsg.getReplyReturnsToMain().get(clicked);
                }
                ChatDataManager.onReplySelected(contactId, branchId, returnsToMain);
                autoScrollToBottom(w, h);
                return true;
            }
        }

        if (mouseY > y + HEADER_HEIGHT && mouseX >= x && mouseX <= x + w) {
            ChatDataManager.Conversation conv = ChatDataManager.getConversation(contactId);
            if (conv != null && !conv.finished) {
                if (conv.isTyping) {
                    ChatDataManager.skipTyping(contactId);
                    autoScrollToBottom(w, h);
                    return true;
                }
                if (conv.hasNextMessage()) {
                    ChatMessage next = conv.peekNext();
                    if (next.getReplyOptions() != null && !next.getReplyOptions().isEmpty()) {
                        return false;
                    }
                    ChatDataManager.advanceConversation(contactId);
                    autoScrollToBottom(w, h);
                    return true;
                }
            }
        }

        return false;
    }

    private void autoScrollToBottom(int w, int h) {
        List<ChatMessage> msgs = ChatDataManager.getMessages(contactId);
        int contentH = h - HEADER_HEIGHT - 4;
        int totalHeight = 0;
        for (ChatMessage msg : msgs) {
            boolean showName = msg.getType() != ChatMessage.MessageType.SYSTEM && !"$player".equals(msg.getSenderId());
            totalHeight += ChatBubbleRenderer.measureHeight(msg, w) + MSG_GAP;
            if (showName) totalHeight += 10;
        }
        ChatMessage lastMsg = msgs.isEmpty() ? null : msgs.get(msgs.size() - 1);
        if (lastMsg != null && lastMsg.getReplyOptions() != null && !lastMsg.getReplyOptions().isEmpty()) {
            totalHeight += ChatBubbleRenderer.measureReplyOptionsHeight(lastMsg.getReplyOptions()) + REPLY_AREA_GAP;
        }
        int maxScroll = Math.max(0, totalHeight - contentH);
        scrollOffset = maxScroll;
    }
}
