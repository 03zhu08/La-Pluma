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
import cn.earthsky.dev.project.lapluma.common.utils.Timestamp;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundCategory;
import net.minecraftforge.fml.client.FMLClientHandler;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
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
            fxBlockingQueue.forEach(Runnable::run);
            nextPrompt();
        }
    }

    int keepAliveTicks;

    @Getter @Setter private boolean hideHUD = false;

    @Getter private GuiSkipMenu skipMenu;
    @Getter @Setter private boolean showSkipMenu = false;
    private boolean suppressClosePacket = false;

    @Setter private int splitLineColor = 0xffff5733;

    private List<GuiSmallButton> smallButtonList = new ArrayList<>();
    @Getter @Setter private boolean centerText = false;

    public void setBackground(final String bg){
        if(hasFX()) {
            fxBlockingQueue.offer(() -> bgName = bg);
        }else {
            this.bgName = bg;
        }
    }

    public void suspendForVideo() {
        this.suppressClosePacket = true;
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if(keyCode == Keyboard.KEY_ESCAPE){
            if(showLog){
                showLog = false;
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
                for (net.minecraft.entity.player.EntityPlayer p : mc.world.playerEntities) {
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

    private void drawEntityOnScreen(int posX, int posY, float scale, Entity entity) {
        GlStateManager.enableColorMaterial();
        GlStateManager.pushMatrix();
        GlStateManager.translate((float) posX, (float) posY, 50.0F);
        GlStateManager.scale(-scale, scale, scale);
        GlStateManager.rotate(180.0F, 0.0F, 0.0F, 1.0F);
        GlStateManager.rotate(135.0F, 0.0F, 1.0F, 0.0F);
        RenderHelper.enableStandardItemLighting();
        GlStateManager.rotate(-135.0F, 0.0F, 1.0F, 0.0F);
        GlStateManager.rotate(-((float) Math.atan(0)) * 20.0F, 1.0F, 0.0F, 0.0F);
        GlStateManager.translate(0.0F, 0.0F, 0.0F);
        RenderManager rendermanager = Minecraft.getMinecraft().getRenderManager();
        rendermanager.setPlayerViewY(180.0F);
        rendermanager.setRenderShadow(false);
        try {
            rendermanager.renderEntity(entity, 0.0D, 0.0D, 0.0D, 0.0F, 1.0F, false);
        } catch (Throwable ignored) {
        }
        rendermanager.setRenderShadow(true);
        GlStateManager.popMatrix();
        RenderHelper.disableStandardItemLighting();
        GlStateManager.disableRescaleNormal();
        GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit);
        GlStateManager.disableTexture2D();
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
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
        smallButtonList.add(new GuiSmallButton(2,85,5,() -> this.hideHUD = true, "hide", "隐藏页面"));
        smallButtonList.add(new GuiSmallButton(3,90,5,() -> showSkipMenu = true, "skip", "跳过此段剧情"));

    }

    public void addSelection(Consumer<GuiScreen> callback, String text){
        int size = selectionButtonList.size();
        int keyIndex = size + 1;
        int btnWidth = (int)(this.width * 0.35);
        int btnHeight = 20;
        int btnX = this.width - btnWidth - 10;
        int btnY = getRectTop() - 10 - (size + 1) * (btnHeight + 4);
        GuiSelectionButton but = new GuiSelectionButton(keyIndex, btnX, btnY, btnWidth, btnHeight, text, keyIndex);
        selectionButtonList.add(but);
        but.setCallback(callback);
        repositionSelections();
    }



    private void repositionSelections() {
        int total = selectionButtonList.size();
        int btnWidth = (int)(this.width * 0.35);
        int btnHeight = 20;
        int gap = 4;
        int btnX = this.width - btnWidth - 10;
        int baseY = getRectTop() - 10;
        for (int i = 0; i < total; i++) {
            GuiSelectionButton but = selectionButtonList.get(i);
            but.width = btnWidth;
            but.height = btnHeight;
            but.x = btnX;
            but.y = baseY - (total - i) * (btnHeight + gap);
        }
    }

    public static ConversationStructure EXAMPLE_STRUCTURE;

    public void showText(String speaker, String newText){
        this.text = "";
        if(!speaker.equalsIgnoreCase("~")) {
            this.speaker = speaker;
        }
        this.fullText = newText;

        if(fullText.contains("%")){
            PlaceholderConnect.submitRequest(fullText);
        }

        Optional.ofNullable(Minecraft.getMinecraft().world).ifPresent(w -> Optional.ofNullable(Minecraft.getMinecraft().player).ifPresent(p -> w.playSound(p.posX, p.posY, p.posZ, LaPluma.Sounds.BEEP, SoundCategory.MASTER, 0.2f,1f,false)));

        if(logSlider != null) {
            logSlider.addLog("§l" + this.speaker + "§f    " + newText);
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
        if (text.equals(fullText)) {
            return;
        }
        text = fullText.substring(0, Math.min(text.length()+1, fullText.length()+1));

    }


    private void drawCenteredLargeString(String text, float x, float y, double scale, int color) {
        if(scale < 1) scale = 1;
        GL11.glPushMatrix();
//        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glScaled(scale, scale, 1.0F);
        this.drawCenteredString(fontRenderer, text, (int) (x / scale ), (int) (y / scale - fontRenderer.FONT_HEIGHT ), color);
        GL11.glPopMatrix();
    }

    public double getHeightScale(){
        return Math.max(Math.ceil(this.height/480d),1f);
    }
    public double getWidthScale(){
        return Math.max(Math.ceil(this.width/854d),1);
    }

    private int getRectTop(){
        return (this.height/4*3) - 50;
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
        if (Keyboard.getEventKeyState()) {
            int key = Keyboard.getEventKey();
            if (key == Keyboard.KEY_SPACE) {
                touchScreen(this.width / 2, getRectTop() + 25);
            } else if (!selectionButtonList.isEmpty()) {
                int index = keyToSelectionIndex(key);
                if (index >= 0 && index < selectionButtonList.size()) {
                    selectOption(index);
                }
            }
        }
    }

    private int keyToSelectionIndex(int keyCode) {
        switch (keyCode) {
            case Keyboard.KEY_1: return 0;
            case Keyboard.KEY_2: return 1;
            case Keyboard.KEY_3: return 2;
            case Keyboard.KEY_4: return 3;
            case Keyboard.KEY_5: return 4;
            case Keyboard.KEY_6: return 5;
            case Keyboard.KEY_7: return 6;
            case Keyboard.KEY_8: return 7;
            case Keyboard.KEY_9: return 8;
            default: return -1;
        }
    }

    private void selectOption(int index) {
        if (index < 0 || index >= selectionButtonList.size()) return;
        GuiSelectionButton guibutton = selectionButtonList.get(index);
        playPressedSound();
        ProxyPacketHandler.sendPacket(3, cursor * 10 + index, structure.getName());
        guibutton.getCallback().accept(this);
        logSlider.addLog("  \u00a7e[" + guibutton.displayString + "]");
        selectionButtonList.clear();
        if (!hasContinueStructure) {
            nextPrompt();
        } else {
            hasContinueStructure = false;
        }
    }

    private void touchScreen(int mouseX, int mouseY){
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
                    // SKIP
                    if (text.equals(fullText)) {
                        nextPrompt();
                    } else {
                        text = fullText;
                    }
                }else if(centerText && Math.abs(mouseY-height/2) < 60){
                    if (text.equals(fullText)) {
                        nextPrompt();
                    } else {
                        text = fullText;
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
        if (suppressClosePacket) {
            suppressClosePacket = false;
            return;
        }
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
                if (character.isEntity()) {
                    Entity entity = resolveEntity(character);
                    if (entity != null) {
                        int entityX = (int) (this.width * (character.getPosition() / 100d));
                        int entityY = getRectTop() - 10 + character.getEntityYOffset();
                        float baseScale = 30.0f * character.getEntityScale() * (float) heightScaleFactor;
                        if (hasFX()) {
                            GlStateManager.color(gAlpha, gAlpha, gAlpha, 1.0f);
                        } else if (character.isDimmed()) {
                            GlStateManager.color(0.5f, 0.5f, 0.5f, 1.0f);
                        }
                        drawEntityOnScreen(entityX, entityY, baseScale, entity);
                        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
                    }
                } else {
                    mc.getTextureManager().bindTexture(new ResourceLocation("lapluma", "avg/" + character.getIdentity() + ".png"));
                    GL11.glPushMatrix();
                    GL11.glScaled(widthScaleFactor, heightScaleFactor, 1);
                    if (hasFX()) {
                        GL11.glColor3f(gAlpha, gAlpha, gAlpha);
                    } else {
                        if (character.isDimmed()) {
                            GL11.glColor3f(0.5f, 0.5f, 0.5f);
                        } else {
                            GL11.glColor3f(1.0f, 1.0f, 1.0f);
                        }
                    }
                    this.drawTexturedModalRect((int) ((this.width * (character.getPosition() / 100d) - 122) / (widthScaleFactor * 2)), (int) ((this.height / 4 - 60) / heightScaleFactor), 0, 0, 256, 256);
                    GL11.glPopMatrix();
                }
            }catch (Throwable throwable){
                // TODO ERROR
            }
        }

        if(!hideHUD && !showLog) {

            boolean showDialog = true;
            if(hasFX() && getFX() instanceof FXFade){
                showDialog = false;
            }

            if(showDialog) {
                if (!centerText) {
                    int top = getRectTop();
                    this.drawGradientRect(0, top, this.width, this.height, -1072689136, -804253680);
                    GL11.glColor3f(1f,1f,1f);
                    if(LaPluma.hasDialogBubbleProvided){
                        mc.getTextureManager().bindTexture(new ResourceLocation("lapluma:icon/dialog_bubble.png"));
                        Gui.drawModalRectWithCustomSizedTexture(0, top, 0,0,this.width,this.height-top,this.width,this.height-top);
                    }

                    this.drawHorizontalLine((int) (this.width / 2 - 50 * getWidthScale()), (int) (width / 2 + 50 * getWidthScale()), top + 17, splitLineColor);
                    this.drawCenteredLargeString(speaker, this.width / 2f, (float) (top + 15), getHeightScale() * 1.5d, 0xffffffff);
                    int blanketSize = (int) (20 * getWidthScale());
                    int trimWidth = (int) ((this.width - blanketSize * 4) / (getWidthScale() * 1.3));
                    double textScale = getHeightScale() * 1.5d;
                    GL11.glPushMatrix();
                    GL11.glScaled(textScale, textScale, 1);
                    this.fontRenderer.drawSplitString(text, (int) (blanketSize / textScale), (int) ((top + 22) / textScale), trimWidth, 0xffDCDCDC);
                    GL11.glPopMatrix();
                } else {
                    drawCenteredLargeString(text, this.width / 2f, this.height / 2f, getHeightScale() * 2, 0xFFFFFFFF);
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
