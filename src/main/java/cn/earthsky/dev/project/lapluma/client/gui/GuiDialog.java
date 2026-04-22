package cn.earthsky.dev.project.lapluma.client.gui;

import cn.earthsky.dev.project.lapluma.LaPluma;
import cn.earthsky.dev.project.lapluma.client.audio.DynamicMusicSound;
import cn.earthsky.dev.project.lapluma.client.gui.fx.FX;
import cn.earthsky.dev.project.lapluma.client.gui.fx.FXFade;
import cn.earthsky.dev.project.lapluma.client.gui.fx.FXFadeOut;
import cn.earthsky.dev.project.lapluma.client.gui.fx.FXShake;
import cn.earthsky.dev.project.lapluma.common.network.PlaceholderConnect;
import cn.earthsky.dev.project.lapluma.common.network.ProxyPacketHandler;
import cn.earthsky.dev.project.lapluma.common.text.AVGCharacter;
import cn.earthsky.dev.project.lapluma.common.text.ConversationPrompt;
import cn.earthsky.dev.project.lapluma.common.text.ConversationStructure;
import cn.earthsky.dev.project.lapluma.common.text.prompts.FunctionPrompt;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundCategory;
import net.minecraftforge.fml.client.FMLClientHandler;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

public class GuiDialog extends GuiScreen {

    private String speaker = "";
    private String text = "";
    private String fullText = "";


    public void tryRAUFullText(String original, String newT){
        if(fullText != null) {
            if (fullText.equals(original)) {
                this.fullText = newT;
            }
        }
    }

    @Getter private String playingMusic;

    public void setPlayingMusic(final String url){
        playingMusic = url;
        FMLClientHandler.instance().getClient().addScheduledTask(() -> Optional.ofNullable(Minecraft.getMinecraft().player).ifPresent(pl -> {
            Minecraft.getMinecraft().getSoundHandler().playSound(new DynamicMusicSound(pl.getPosition(),url));
            System.out.println("Play Sound In " + url);
        }));
    }

    @Getter
    private ConversationStructure structure;
    private List<GuiSelectionButton> selectionButtonList = new ArrayList<>();
    private List<AVGCharacter> avgCharacters = new ArrayList<>();
    private String bgName = "bg";

    private Queue<Runnable> fxBlockingQueue = new LinkedBlockingQueue<>();

    public void addNMSButtonList(GuiButton button){
        this.buttonList.add(button);
    }

    public void suspendForVideo(){
        this.hideHUD = true;
    }



    @Data
    private class DialogSnapshot{
        private int cursor;
        private final ConversationStructure structure;
        private final String speaker;
        private final String text;
        private final boolean centerText;
        public DialogSnapshot(){
            this.structure = GuiDialog.this.structure;
            this.cursor = GuiDialog.this.cursor;
            this.speaker = GuiDialog.this.speaker;
            this.text = GuiDialog.this.text;
            this.centerText = GuiDialog.this.centerText;
        }
    }

    boolean hasContinueStructure = false;

    private DialogSnapshot snapshot;

    public void continueStructure(ConversationStructure structure){
        snapshot = new DialogSnapshot();
        this.structure = structure;
        this.cursor = -1;
        this.hideHUD = false;
        this.showSkipMenu = false;
        this.showLog = false;
        this.centerText = false;
        this.speaker = "";
        this.text = "";
        this.fullText = "";
        this.hasContinueStructure = true;
        this.dialogAnimState = DialogAnimState.IDLE;
        this.dialogAnimTick = 0;
        this.isFirstLine = true;
        fxBlockingQueue.forEach(Runnable::run);
        nextPrompt();
    }

    public void reverseSnapshot(){
        if(snapshot != null){
            final DialogSnapshot recover = snapshot;
            snapshot = new DialogSnapshot();
            this.structure = recover.getStructure();
            this.cursor = recover.getCursor();
            this.hideHUD = false;
            this.showSkipMenu = false;
            this.showLog = false;
            this.centerText = recover.centerText;
            this.speaker = recover.getSpeaker();
            this.text = recover.getText();
            this.fullText = "";
            this.hasContinueStructure = true;
            this.dialogAnimState = DialogAnimState.IDLE;
            this.dialogAnimTick = 0;
            this.isFirstLine = true;
            fxBlockingQueue.forEach(Runnable::run);
            nextPrompt();
        }
    }

    int keepAliveTicks;

    @Getter @Setter private boolean hideHUD = false;

    @Getter private GuiSkipMenu skipMenu;
    @Getter @Setter private boolean showSkipMenu = false;

    @Setter private int splitLineColor = 0xffff5733;

    private enum DialogAnimState { IDLE, SLIDING_OUT, SLIDING_IN }
    private DialogAnimState dialogAnimState = DialogAnimState.IDLE;
    private int dialogAnimTick = 0;
    private static final int ANIM_SLIDE_OUT_TICKS = 4;
    private static final int ANIM_SLIDE_IN_TICKS = 6;
    private String pendingSpeaker = null;
    private String pendingFullText = null;
    private boolean isFirstLine = true;

    private List<GuiSmallButton> smallButtonList = new ArrayList<>();
    @Getter @Setter private boolean centerText = false;
    private float textProgress = 0f;
    private static final float TEXT_SPEED = 5.0f;
    private Framebuffer charFbo;
    private static final int FBO_SIZE = 64;
    private boolean autoPlay = true;
    private int autoPlayWaitTick = 0;
    private static final int AUTO_PLAY_DELAY = 40;

    public void setBackground(final String bg){
        if(hasFX()) {
            fxBlockingQueue.offer(() -> bgName = bg);
        }else {
            this.bgName = bg;
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if(keyCode == Keyboard.KEY_ESCAPE){
            if(showLog){
                showLog = false;
            }
        }

        if (!selectionButtonList.isEmpty() && typedChar >= '1' && typedChar <= '9') {
            int index = typedChar - '1';
            if (index < selectionButtonList.size()) {
                GuiSelectionButton btn = selectionButtonList.get(index);
                playPressedSound();
                ProxyPacketHandler.sendPacket(3, cursor * 10 + index, structure.getName());
                btn.getCallback().accept(this);
                logSlider.addLog("  §e[" + btn.displayString + "]");
                selectionButtonList.clear();
                if (!hasContinueStructure) {
                    nextPrompt();
                } else {
                    hasContinueStructure = false;
                }
            }
        }
    }

    public void clearCharacter(){
        avgCharacters.clear();
        entityCache.clear();
    }

    public void addCharacter(AVGCharacter avg){
        avgCharacters.add(avg);
    }

    private final Map<String, Entity> entityCache = new HashMap<>();

    private Entity resolveEntity(AVGCharacter character) {
        String key = character.getEntityMode().name() + ":" + character.getIdentity();
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null) return null;

        switch (character.getEntityMode()) {
            case CREATE:
                return entityCache.computeIfAbsent(key, k -> {
                    ResourceLocation rl = new ResourceLocation(character.getIdentity());
                    return EntityList.createEntityByIDFromName(rl, mc.world);
                });
            case PLAYER:
                String playerName = character.getIdentity();
                if ("$self".equalsIgnoreCase(playerName) || "$player".equalsIgnoreCase(playerName)) {
                    return mc.player;
                }
                for (EntityPlayer p : mc.world.playerEntities) {
                    if (p.getName().equalsIgnoreCase(playerName)) {
                        return p;
                    }
                }
                return null;
            case DISPLAY_NAME:
                String dn = character.getIdentity();
                for (Entity e : mc.world.loadedEntityList) {
                    if (e.getDisplayName() != null && e.getDisplayName().getUnformattedText().equals(dn)) {
                        return e;
                    }
                }
                return null;
            case UUID:
                try {
                    java.util.UUID uuid = java.util.UUID.fromString(character.getIdentity());
                    for (Entity e : mc.world.loadedEntityList) {
                        if (e.getUniqueID().equals(uuid)) {
                            return e;
                        }
                    }
                } catch (IllegalArgumentException ignored) {
                }
                return null;
            default:
                return null;
        }
    }

    private void drawEntityOnScreen(int posX, int posY, float scale, float mouseX, float mouseY, Entity entity) {
        if (entity instanceof EntityLivingBase) {
            EntityLivingBase living = (EntityLivingBase) entity;
            float lookX = posX - mouseX;
            float lookY = posY - scale - mouseY;

            GlStateManager.disableBlend();
            GlStateManager.enableDepth();
            GlStateManager.depthMask(true);
            GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
            GlStateManager.enableColorMaterial();
            GlStateManager.pushMatrix();
            GlStateManager.translate((float) posX, (float) posY, 50.0F);
            GlStateManager.scale(-(float)(int) scale, (float)(int) scale, (float)(int) scale);
            GlStateManager.rotate(180.0F, 0.0F, 0.0F, 1.0F);
            float f = living.renderYawOffset;
            float f1 = living.rotationYaw;
            float f2 = living.rotationPitch;
            float f3 = living.prevRotationYawHead;
            float f4 = living.rotationYawHead;
            GlStateManager.rotate(135.0F, 0.0F, 1.0F, 0.0F);
            RenderHelper.enableStandardItemLighting();
            GlStateManager.rotate(-135.0F, 0.0F, 1.0F, 0.0F);
            GlStateManager.rotate(-((float) Math.atan((double)(lookY / 40.0F))) * 20.0F, 1.0F, 0.0F, 0.0F);
            living.renderYawOffset = (float) Math.atan((double)(lookX / 40.0F)) * 20.0F;
            living.rotationYaw = (float) Math.atan((double)(lookX / 40.0F)) * 40.0F;
            living.rotationPitch = -((float) Math.atan((double)(lookY / 40.0F))) * 20.0F;
            living.rotationYawHead = living.rotationYaw;
            living.prevRotationYawHead = living.rotationYaw;
            GlStateManager.translate(0.0F, 0.0F, 0.0F);
            RenderManager rendermanager = Minecraft.getMinecraft().getRenderManager();
            rendermanager.setPlayerViewY(180.0F);
            rendermanager.setRenderShadow(false);
            rendermanager.renderEntity(living, 0.0D, 0.0D, 0.0D, 0.0F, 1.0F, false);
            rendermanager.setRenderShadow(true);
            living.renderYawOffset = f;
            living.rotationYaw = f1;
            living.rotationPitch = f2;
            living.prevRotationYawHead = f3;
            living.rotationYawHead = f4;
            GlStateManager.popMatrix();
            RenderHelper.disableStandardItemLighting();
            GlStateManager.disableRescaleNormal();
            GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit);
            GlStateManager.disableTexture2D();
            GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        }
    }


    private boolean showLog = false;
    private final GuiLogSlider logSlider;

    public void setShowLog(boolean show){
        this.showLog = show;
    }

    // FX System
    private FX fx;
    public boolean hasFX(){
        return fx != null;
    }

    public String getFXName(){
        return (hasFX() ? fx.getName() : "none");
    }

    public void stopCurrentFX(){
        setFX(null);
    }

    public void setFX(FX fx){
        if(this.fx != null){
            this.fx.done();
            // FX Switch
            fxBlockingQueue.forEach(Runnable::run);
        }
        this.fx = fx;
    }


    public FX getFX(){
        return fx;
    }


    public GuiDialog(ConversationStructure structure){
        super();
        logSlider = new GuiLogSlider(this);
        skipMenu = new GuiSkipMenu(this);
        this.structure = structure;
        nextPrompt();

        smallButtonList.add(new GuiSmallButton(1,5,5,() -> setShowLog(true), "log", "查看剧情记录"));
        GuiSmallButton autoBtn = new GuiSmallButton(2,80,5,() -> { autoPlay = !autoPlay; autoPlayWaitTick = 0; }, "auto", "自动播放");
        autoBtn.setActiveSupplier(() -> autoPlay);
        autoBtn.setActiveText("自动播放中...");
        smallButtonList.add(autoBtn);
        smallButtonList.add(new GuiSmallButton(3,85,5,() -> this.hideHUD = true, "hide", "隐藏页面"));
        smallButtonList.add(new GuiSmallButton(4,90,5,() -> showSkipMenu = true, "skip", "跳过此段剧情"));

    }

    public void addSelection(Consumer<GuiScreen> callback, String text){
        int size = selectionButtonList.size();
        GuiSelectionButton but = new GuiSelectionButton(size+1, 0, 0, 200, 20, text, size + 1);
        selectionButtonList.add(but);
        but.setCallback(callback);
        repositionSelections();
    }

    private void repositionSelections() {
        if (selectionButtonList.isEmpty()) return;
        Minecraft mc = Minecraft.getMinecraft();
        int maxTextWidth = 0;
        for (GuiSelectionButton btn : selectionButtonList) {
            int w = mc.fontRenderer.getStringWidth(btn.displayString);
            if (w > maxTextWidth) maxTextWidth = w;
        }
        int btnWidth = maxTextWidth + 34;
        int btnHeight = 20;
        int gap = 4;
        int margin = 15;
        int total = selectionButtonList.size();
        int btnX = this.width - btnWidth - margin;
        int baseY = getRectTop() - margin;
        for (int i = 0; i < total; i++) {
            GuiSelectionButton btn = selectionButtonList.get(i);
            btn.width = btnWidth;
            btn.height = btnHeight;
            btn.x = btnX;
            btn.y = baseY - (total - i) * (btnHeight + gap);
        }
    }



    public static ConversationStructure EXAMPLE_STRUCTURE;

    public void showText(String speaker, String newText){
        Optional.ofNullable(Minecraft.getMinecraft().world).ifPresent(w -> Optional.ofNullable(Minecraft.getMinecraft().player).ifPresent(p -> w.playSound(p.posX, p.posY, p.posZ, LaPluma.Sounds.CLICK, SoundCategory.MASTER, 0.2f,1f,false)));

        if(logSlider != null) {
            String logSpeaker = speaker.equalsIgnoreCase("~") ? this.speaker : speaker;
            logSlider.addLog("§l" + logSpeaker + "§f    " + newText);
        }

        if (isFirstLine || dialogAnimState != DialogAnimState.IDLE) {
            applyNewText(speaker, newText);
            dialogAnimState = DialogAnimState.SLIDING_IN;
            dialogAnimTick = 0;
            isFirstLine = false;
        } else {
            pendingSpeaker = speaker;
            pendingFullText = newText;
            dialogAnimState = DialogAnimState.SLIDING_OUT;
            dialogAnimTick = 0;
        }
    }

    private void applyNewText(String speaker, String newText) {
        this.text = "";
        this.textProgress = 0f;
        if (!speaker.equalsIgnoreCase("~")) {
            this.speaker = speaker;
        }
        this.fullText = newText;
        if (fullText.contains("%")) {
            PlaceholderConnect.submitRequest(fullText);
        }
    }

    @Override
    public void initGui(){
        super.initGui();

        skipMenu.onResize(Minecraft.getMinecraft(), width, height);

//        Minecraft.getMinecraft().mouseHelper.ungrabMouseCursor();
    }

    @Override
    public void updateScreen(){
        keepAliveTicks++;
        if(keepAliveTicks >= 21){
            keepAliveTicks = 0;
            int data = Integer.parseInt(cursor + "021" + structure.length());
            ProxyPacketHandler.sendPacket(23, data,getStructure().getName() + "|" + System.currentTimeMillis());
        }

        if(hasFX()){
            getFX().updateFx();
        }

        logSlider.updateShowState(showLog);


        if(hasFX() && getFX() instanceof FXFade){
            return;
        }

        if (dialogAnimState == DialogAnimState.SLIDING_OUT) {
            dialogAnimTick++;
            if (dialogAnimTick >= ANIM_SLIDE_OUT_TICKS) {
                applyNewText(pendingSpeaker, pendingFullText);
                pendingSpeaker = null;
                pendingFullText = null;
                dialogAnimState = DialogAnimState.SLIDING_IN;
                dialogAnimTick = 0;
            }
            return;
        }
        if (dialogAnimState == DialogAnimState.SLIDING_IN) {
            dialogAnimTick++;
            if (dialogAnimTick >= ANIM_SLIDE_IN_TICKS) {
                dialogAnimState = DialogAnimState.IDLE;
                dialogAnimTick = 0;
            }
        }

        if (text.equals(fullText)) {
            if (autoPlay && selectionButtonList.isEmpty() && dialogAnimState == DialogAnimState.IDLE && !hasFX()) {
                autoPlayWaitTick++;
                if (autoPlayWaitTick >= AUTO_PLAY_DELAY) {
                    autoPlayWaitTick = 0;
                    nextPrompt();
                }
            }
            return;
        }
        autoPlayWaitTick = 0;
        textProgress += TEXT_SPEED;
        int totalWidth = fontRenderer.getStringWidth(fullText);
        if (textProgress >= totalWidth) {
            text = fullText;
        } else {
            int px = 0;
            int count = 0;
            for (int i = 0; i < fullText.length(); i++) {
                int cw = fontRenderer.getCharWidth(fullText.charAt(i));
                if (px + cw > textProgress) break;
                px += cw;
                count++;
            }
            text = fullText.substring(0, count);
        }

    }


    private void drawCenteredLargeString(String text, float x, float y, double scale, int color) {
        if(scale < 1) scale = 1;
        GL11.glPushMatrix();
//        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glScaled(scale, scale, 1.0F);
        this.drawCenteredString(fontRenderer, text, (int) (x / scale ), (int) (y / scale - fontRenderer.FONT_HEIGHT ), color);
        GL11.glPopMatrix();
    }

    private void drawCenteredSplitString(int trimWidth, int top, double textScale, int color, float partialTicks) {
        if(fullText == null || fullText.isEmpty()) return;

        java.util.List<String> fullLines = fontRenderer.listFormattedStringToWidth(fullText, trimWidth);
        int totalPx = 0;
        for (String line : fullLines) totalPx += fontRenderer.getStringWidth(line);
        float renderPx = Math.min(textProgress + TEXT_SPEED * partialTicks, totalPx);

        GL11.glPushMatrix();
        GL11.glScaled(textScale, textScale, 1);

        int y = (int) ((top + 22) / textScale);
        double centerX = this.width / 2.0 / textScale;

        float consumed = 0;
        for (String fullLine : fullLines) {
            int lineW = fontRenderer.getStringWidth(fullLine);
            int lineX = (int) (centerX - lineW / 2.0);
            float lineRevealed = renderPx - consumed;

            if (lineRevealed >= lineW) {
                fontRenderer.drawStringWithShadow(fullLine, lineX, y, color);
            } else if (lineRevealed > 0) {
                int px = 0;
                int solidEnd = 0;
                for (int i = 0; i < fullLine.length(); i++) {
                    int cw = fontRenderer.getCharWidth(fullLine.charAt(i));
                    if (px + cw <= lineRevealed) { px += cw; solidEnd = i + 1; }
                    else break;
                }
                if (solidEnd > 0) {
                    fontRenderer.drawStringWithShadow(fullLine.substring(0, solidEnd), lineX, y, color);
                }
                if (solidEnd < fullLine.length()) {
                    int charX = lineX + (solidEnd > 0 ? fontRenderer.getStringWidth(fullLine.substring(0, solidEnd)) : 0);
                    float alpha = (lineRevealed - px) / (float) fontRenderer.getCharWidth(fullLine.charAt(solidEnd));
                    alpha = Math.max(0f, Math.min(1f, alpha));
                    drawFadingChar(String.valueOf(fullLine.charAt(solidEnd)), charX, y, color, alpha, textScale);
                }
            } else {
                break;
            }

            consumed += lineW;
            y += fontRenderer.FONT_HEIGHT;
        }

        GL11.glPopMatrix();
    }

    private void drawFadingChar(String ch, int x, int y, int color, float alpha, double currentScale) {
        Minecraft mc = Minecraft.getMinecraft();
        int scaleFactor = mc.gameSettings.guiScale == 0
                ? Math.max(1, (int)(Math.min(mc.displayWidth, mc.displayHeight) / 320.0))
                : mc.gameSettings.guiScale;
        int fbW = (int)(FBO_SIZE * currentScale * scaleFactor);
        int fbH = (int)(FBO_SIZE * currentScale * scaleFactor);
        if (fbW <= 0 || fbH <= 0) return;

        if (charFbo == null || charFbo.framebufferWidth != fbW || charFbo.framebufferHeight != fbH) {
            if (charFbo != null) charFbo.deleteFramebuffer();
            charFbo = new Framebuffer(fbW, fbH, false);
        }

        GL11.glPopMatrix();

        charFbo.framebufferClear();
        charFbo.bindFramebuffer(true);
        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.pushMatrix();
        GlStateManager.loadIdentity();
        GlStateManager.ortho(0, FBO_SIZE * currentScale, FBO_SIZE * currentScale, 0, 1000, 3000);
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.pushMatrix();
        GlStateManager.loadIdentity();
        GlStateManager.translate(0, 0, -2000);

        GlStateManager.pushMatrix();
        GL11.glScaled(currentScale, currentScale, 1);
        fontRenderer.drawStringWithShadow(ch, 1, 1, color);
        GlStateManager.popMatrix();

        GlStateManager.popMatrix();
        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.popMatrix();
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);

        mc.getFramebuffer().bindFramebuffer(true);

        GL11.glPushMatrix();
        GL11.glScaled(currentScale, currentScale, 1);

        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        GlStateManager.color(1f, 1f, 1f, alpha);
        charFbo.bindFramebufferTexture();

        int drawW = (int)(FBO_SIZE);
        int drawH = (int)(FBO_SIZE);
        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
        buf.pos(x - 1, y - 1 + drawH, 0).tex(0, 0).endVertex();
        buf.pos(x - 1 + drawW, y - 1 + drawH, 0).tex(1, 0).endVertex();
        buf.pos(x - 1 + drawW, y - 1, 0).tex(1, 1).endVertex();
        buf.pos(x - 1, y - 1, 0).tex(0, 1).endVertex();
        tess.draw();

        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    public double getHeightScale(){
        return Math.max(Math.ceil(this.height/480d),1f);
    }
    public double getWidthScale(){
        return Math.max(Math.ceil(this.width/854d),1);
    }

    private int getRectTop(){
        return this.height - (int)((this.height / 4 + 50) * 0.7);
    }

    @Override
    public void onResize(Minecraft mcIn, int w, int h) {
        super.onResize(mcIn, w, h);
        skipMenu.onResize(mcIn, w, h);
        repositionSelections();
    }

    public void playPressedSound(){
        Optional.ofNullable(Minecraft.getMinecraft().world).ifPresent(w -> Optional.ofNullable(Minecraft.getMinecraft().player).ifPresent(p -> w.playSound(p.posX, p.posY, p.posZ, LaPluma.Sounds.CLICK, SoundCategory.MASTER, 1f,1f,false)));
    }

    @Override
    public void handleKeyboardInput() throws IOException {
        super.handleKeyboardInput();
        if(Keyboard.isKeyDown(Keyboard.KEY_SPACE)){
            touchScreen(this.width/2, getRectTop() + 25);
        }
    }

    private void touchScreen(int mouseX, int mouseY){
        if(dialogAnimState != DialogAnimState.IDLE) return;
        if(hideHUD){
            hideHUD = false;
            return;
        }

        if(showLog){
            showLog = false;
            return;
        }

        boolean hasPressed = false;
        for (int i = 0; i < this.selectionButtonList.size(); ++i)
        {
            GuiSelectionButton guibutton = this.selectionButtonList.get(i);

            if (guibutton.mousePressed(this.mc, mouseX, mouseY))
            {
                playPressedSound();
                ProxyPacketHandler.sendPacket(3,cursor * 10 + i, structure.getName());
                guibutton.getCallback().accept(this);
                logSlider.addLog("  §e[" + guibutton.displayString + "]");
                hasPressed = true;
            }
        }
        if(hasPressed){
            selectionButtonList.clear();
            if(!hasContinueStructure) {
                nextPrompt();
            }else{
                hasContinueStructure = false;
            }
        }else if(selectionButtonList.isEmpty()){
            if(showSkipMenu){
                skipMenu.mouseClicked(mouseX, mouseY, 0, this);
            }else {
                if (mouseY > getRectTop() + 17 && !centerText && !hasFX()) {
                    if (autoPlay) { autoPlay = false; autoPlayWaitTick = 0; }
                    if (text.equals(fullText)) {
                        nextPrompt();
                    } else {
                        text = fullText;
                        textProgress = Float.MAX_VALUE;
                    }
                }else if(centerText && Math.abs(mouseY-height/2) < 60){
                    if (autoPlay) { autoPlay = false; autoPlayWaitTick = 0; }
                    if (text.equals(fullText)) {
                        nextPrompt();
                    } else {
                        text = fullText;
                        textProgress = Float.MAX_VALUE;
                    }
                }else {
                    for (int i = 0; i < this.smallButtonList.size(); ++i) {
                        GuiSmallButton guibutton = this.smallButtonList.get(i);
                        if (guibutton.mousePressed(this.mc, mouseX, mouseY)) {
                            guibutton.getCallback().run();
                            playPressedSound();
                        }
                    }
                }
            }
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException{
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (mouseButton == 0)
        {
           touchScreen(mouseX, mouseY);
        }
    }

    @Override
    public void handleMouseInput() throws IOException{
        super.handleMouseInput();
        if(showLog) logSlider.handleMouseInput();
    }





    int cursor = -1;
    private void nextPrompt(){
        if(!hasFX()) {
            cursor++;
            if (cursor >= structure.length()) {
                setFX(new FXFadeOut(this));
                fxBlockingQueue.offer(() -> Minecraft.getMinecraft().player.closeScreen());
                return;
            }
            ConversationPrompt prompt = structure.at(cursor);
            prompt.sendPrompt(this);
            if (prompt instanceof FunctionPrompt) {
                nextPrompt();
            }
        }else{
            fxBlockingQueue.offer(() -> {
                if(!hasContinueStructure) {
                    cursor++;
                    if (cursor >= structure.length()) {
                        Minecraft.getMinecraft().player.closeScreen();

                        return;
                    }
                    ConversationPrompt prompt = structure.at(cursor);
                    prompt.sendPrompt(this);
                    if (prompt instanceof FunctionPrompt) {
                        nextPrompt();
                    }
                }
            });
        }
    }

    protected boolean trySkipped = false;

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        if (charFbo != null) { charFbo.deleteFramebuffer(); charFbo = null; }
        ProxyPacketHandler.sendPacket(2,trySkipped ? 1 : 0, structure.getName());
    }


    public void drawGradientRect(int left,int top,int right, int down, int c1,int c2){
        super.drawGradientRect(left, top, right, down, c1, c2);
    }


    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks){
        super.drawScreen(mouseX, mouseY, partialTicks);
        Minecraft mc = Minecraft.getMinecraft();


        double heightScaleFactor = getHeightScale();
        double widthScaleFactor = getWidthScale();

        float gAlpha = 1;
        if(hasFX() && getFX() instanceof FXFade){
            gAlpha = ((FXFade) getFX()).gAlpha();
        }

        // Background Layer
        if(!bgName.equals("empty")) {
            try {
                Gui.drawRect(0, 0, this.width, this.height, 0xFF212121);
                GL11.glPushMatrix();
                GL11.glColor3f(gAlpha, gAlpha, gAlpha);
                mc.getTextureManager().bindTexture(new ResourceLocation("lapluma", "avg/" + bgName + ".png"));
                GL11.glScaled(widthScaleFactor, heightScaleFactor, 1);
                int bgW = this.width + 20;
                int bgH = this.height + 20;
                int bgX = -10;
                int bgY = -10;
                if(hasFX() && getFX() instanceof FXShake){
                    bgX += ((FXShake) getFX()).getX();
                    bgY += ((FXShake) getFX()).getY();
                }
                Gui.drawModalRectWithCustomSizedTexture(bgX, bgY, 0, 0, (int) (bgW / widthScaleFactor), (int) (bgH / heightScaleFactor), (float) (bgW / widthScaleFactor), (float) (bgH / heightScaleFactor));
                GL11.glPopMatrix();
            } catch (Throwable throwable) {
                this.drawGradientRect(0, 0, this.width, this.height, 0x80606060, 0x80696969);
            }
        }




        for(AVGCharacter character : avgCharacters){
            try {
                int charX = (int)(this.width * (character.getPosition() / 100d));
                int charY = (int)(this.height * 0.75) + character.getEntityYOffset();

                if (character.isEntity()) {
                    Entity entity = resolveEntity(character);
                    if (entity != null) {
                        float scale = 50 * character.getEntityScale() * (float) heightScaleFactor;
                        float mx = character.isEntityFollowMouse() ? mouseX : charX;
                        float my = character.isEntityFollowMouse() ? mouseY : charY - scale;
                        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
                        drawEntityOnScreen(charX, charY, scale, mx, my, entity);
                    }
                } else {
                    mc.getTextureManager().bindTexture(new ResourceLocation("lapluma", "avg/" + character.getIdentity() + ".png"));
                    GL11.glPushMatrix();
                    GL11.glEnable(GL11.GL_BLEND);
                    GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
                    GL11.glScaled(widthScaleFactor, heightScaleFactor, 1);
                    if (hasFX()) {
                        GL11.glColor4f(gAlpha, gAlpha, gAlpha, 1.0f);
                    } else if (character.isDimmed()) {
                        GL11.glColor4f(0.5f, 0.5f, 0.5f, 1.0f);
                    } else {
                        GL11.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
                    }
                    this.drawTexturedModalRect((int) ((this.width * (character.getPosition() / 100d) - 122) / (widthScaleFactor * 2)), (int) ((this.height / 4 - 60) / heightScaleFactor), 0, 0, 256, 256);
                    GL11.glDisable(GL11.GL_BLEND);
                    GL11.glPopMatrix();
                }
            }catch (Throwable throwable){
            }
        }

        if(!hideHUD && !showLog) {

            boolean showDialog = true;
            if(hasFX() && getFX() instanceof FXFade){
                showDialog = false;
            }

            if(showDialog) {
                float animAlpha = 1.0f;
                int animYOffset = 0;
                int slideDistance = 50;

                if (dialogAnimState == DialogAnimState.SLIDING_OUT) {
                    float progress = Math.min((dialogAnimTick + partialTicks) / ANIM_SLIDE_OUT_TICKS, 1.0f);
                    float eased = progress * progress;
                    animAlpha = 1.0f - eased;
                    animYOffset = (int)(eased * slideDistance);
                } else if (dialogAnimState == DialogAnimState.SLIDING_IN) {
                    float progress = Math.min((dialogAnimTick + partialTicks) / ANIM_SLIDE_IN_TICKS, 1.0f);
                    float eased = 1.0f - (1.0f - progress) * (1.0f - progress) * (1.0f - progress);
                    animAlpha = eased;
                    animYOffset = (int)((1.0f - eased) * slideDistance);
                }

                int alphaInt = (int)(animAlpha * 255) & 0xFF;

                if (!centerText) {
                    int top = getRectTop() + animYOffset;
                    int bottom = this.height + animYOffset;
                    int totalHeight = bottom - top;
                    int strips = Math.min(128, totalHeight);
                    float minAlpha = 0x0;
                    float maxAlpha = 0x90;
                    for (int s = 0; s < strips; s++) {
                        int sTop = top + (int)((float) s / strips * totalHeight);
                        int sBot = top + (int)((float)(s + 1) / strips * totalHeight);
                        float t1 = (float) s / strips;
                        float t2 = (float) (s + 1) / strips;
                        int a1 = (int)((minAlpha + (maxAlpha - minAlpha) * t1) * animAlpha) & 0xFF;
                        int a2 = (int)((minAlpha + (maxAlpha - minAlpha) * t2) * animAlpha) & 0xFF;
                        this.drawGradientRect(0, sTop, this.width, sBot, (a1 << 24) | 0x101010, (a2 << 24) | 0x101010);
                    }
                    GL11.glColor4f(1f, 1f, 1f, animAlpha);
                    if(LaPluma.hasDialogBubbleProvided){
                        GL11.glEnable(GL11.GL_BLEND);
                        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
                        mc.getTextureManager().bindTexture(new ResourceLocation("lapluma:icon/dialog_bubble.png"));
                        Gui.drawModalRectWithCustomSizedTexture(0, top, 0,0,this.width,this.height-top,this.width,this.height-top);
                        GL11.glDisable(GL11.GL_BLEND);
                    }

                    int lineColor = (alphaInt << 24) | (splitLineColor & 0x00FFFFFF);
                    this.drawHorizontalLine((int) (this.width / 2 - 50 * getWidthScale()), (int) (width / 2 + 50 * getWidthScale()), top + 17, lineColor);
                    int speakerColor = (alphaInt << 24) | 0xFFFFFF;
                    this.drawCenteredLargeString(speaker, this.width / 2f, (float) (top + 15), getHeightScale() * 1.5d, speakerColor);
                    int padding = 20;
                    int trimWidth = this.width - padding * 2;
                    int textColor = (alphaInt << 24) | 0xDCDCDC;
                    drawCenteredSplitString(trimWidth, top, 1.0d, textColor, partialTicks);
                    GL11.glColor4f(1f, 1f, 1f, 1f);
                } else {
                    int centerColor = (alphaInt << 24) | 0xFFFFFF;
                    double cScale = Math.max(getHeightScale() * 2, 1);
                    int totalW = fontRenderer.getStringWidth(fullText);
                    float renderPx = Math.min(textProgress + TEXT_SPEED * partialTicks, totalW);
                    if (renderPx < totalW) {
                        GL11.glPushMatrix();
                        GL11.glScaled(cScale, cScale, 1.0F);
                        int sx = (int)(this.width / 2f / cScale);
                        int sy = (int)((this.height / 2f + animYOffset) / cScale - fontRenderer.FONT_HEIGHT);
                        int startX = sx - totalW / 2;

                        int px = 0;
                        int solidEnd = 0;
                        for (int i = 0; i < fullText.length(); i++) {
                            int cw = fontRenderer.getCharWidth(fullText.charAt(i));
                            if (px + cw <= renderPx) { px += cw; solidEnd = i + 1; }
                            else break;
                        }
                        if (solidEnd > 0) {
                            fontRenderer.drawStringWithShadow(fullText.substring(0, solidEnd), startX, sy, centerColor);
                        }
                        if (solidEnd < fullText.length()) {
                            int charX = startX + (solidEnd > 0 ? fontRenderer.getStringWidth(fullText.substring(0, solidEnd)) : 0);
                            float alpha = (renderPx - px) / (float) fontRenderer.getCharWidth(fullText.charAt(solidEnd));
                            alpha = Math.max(0f, Math.min(1f, alpha));
                            drawFadingChar(String.valueOf(fullText.charAt(solidEnd)), charX, sy, centerColor, alpha, cScale);
                        }
                        GL11.glPopMatrix();
                    } else {
                        drawCenteredLargeString(text, this.width / 2f, this.height / 2f + animYOffset, cScale, centerColor);
                    }
                }
            }


            if(showSkipMenu){
                skipMenu.drawSkipMenu(Minecraft.getMinecraft(), this, mouseX, mouseY, partialTicks);
            }else {
                for (GuiSelectionButton button : selectionButtonList) {
                    button.drawButton(Minecraft.getMinecraft(), mouseX, mouseY, partialTicks);
                }

                for (GuiSmallButton button : smallButtonList) {
                    button.updatePosition(this);
                    button.drawButton(Minecraft.getMinecraft(), mouseX, mouseY, partialTicks);
                }
            }
        }else if(showLog){
            logSlider.onDrawLog(mouseX, mouseY, partialTicks);
        }

        if(hasFX()){
            getFX().render();
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
