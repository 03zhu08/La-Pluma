package cn.earthsky.dev.project.lapluma.client.gui;

import cn.earthsky.dev.project.lapluma.LaPluma;
import cn.earthsky.dev.project.lapluma.client.audio.VideoAudioPlayer;
import cn.earthsky.dev.project.lapluma.client.video.NativeExtractor;
import cn.earthsky.dev.project.lapluma.common.network.ProxyPacketHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.util.ResourceLocation;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.awt.image.BufferedImage;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

public class GuiVideoPlayer extends GuiScreen {

    private static volatile GuiVideoPlayer activePlayer;

    public static void openVideo(String source) {
        openVideo(source, true, null);
    }

    public static void openVideo(String source, boolean allowSkip, GuiScreen returnScreen) {
        Minecraft.getMinecraft().addScheduledTask(() -> {
            stopCurrentPlayback();
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.getSoundHandler() != null) {
                mc.getSoundHandler().stopSounds();
            }
            GuiVideoPlayer gui = new GuiVideoPlayer(source, allowSkip, returnScreen);
            activePlayer = gui;
            mc.displayGuiScreen(gui);
        });
    }

    public static void stopCurrentPlayback() {
        GuiVideoPlayer current = activePlayer;
        if (current != null) {
            current.stopForReplacement();
        }
    }

    private final String source;
    private final boolean allowSkip;
    private final GuiScreen returnScreen;

    private DynamicTexture videoTexture;
    private ResourceLocation videoTextureLocation;
    private int texWidth;
    private int texHeight;

    private final AtomicBoolean playing = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final AtomicBoolean finished = new AtomicBoolean(false);
    private final AtomicBoolean failed = new AtomicBoolean(false);
    private final AtomicReference<String> statusText = new AtomicReference<>("Loading...");
    private final AtomicReference<BufferedImage> pendingFrame = new AtomicReference<>(null);

    private volatile long durationMicros = 0;
    private volatile long currentTimeMicros = 0;
    private volatile boolean restoreScreenOnClose = true;
    private volatile boolean notifyServerOnClose = true;

    private Thread decoderThread;
    private VideoAudioPlayer audioPlayer;
    private Java2DFrameConverter converter;

    private GuiSmallButton skipButton;

    public GuiVideoPlayer(String source) {
        this(source, true, null);
    }

    public GuiVideoPlayer(String source, boolean allowSkip, GuiScreen returnScreen) {
        this.source = source;
        this.allowSkip = allowSkip;
        this.returnScreen = returnScreen;
    }

    @Override
    public void initGui() {
        super.initGui();
        skipButton = new GuiSmallButton(1, 90, 5, this::requestClose, "skip", "跳过此段视频");
        skipButton.updatePosition(this);

        LaPluma.getLogger().log(Level.INFO, "[Video] initGui() called, allowSkip=" + allowSkip + ", source=" + source);
        if (decoderThread == null || !decoderThread.isAlive()) {
            startDecoding();
        }
    }

    private void startDecoding() {
        finished.set(false);
        playing.set(false);
        failed.set(false);
        statusText.set("Loading...");
        audioPlayer = new VideoAudioPlayer();

        decoderThread = new Thread(() -> {
            FFmpegFrameGrabber grabber = null;
            try {
                NativeExtractor.ensureExtracted();
                converter = new Java2DFrameConverter();
                grabber = new FFmpegFrameGrabber(source);
                grabber.setImageMode(FFmpegFrameGrabber.ImageMode.COLOR);
                grabber.start();

                durationMicros = Math.max(0, grabber.getLengthInTime());
                playing.set(true);
                statusText.set("Playing");

                LaPluma.getLogger().log(Level.INFO, "[Video] Started: " + source
                        + " (" + grabber.getImageWidth() + "x" + grabber.getImageHeight()
                        + ", " + String.format("%.1f", grabber.getFrameRate()) + "fps)");

                final int audioChannels = Math.max(1, grabber.getAudioChannels());
                boolean audioOpened = false;
                if (grabber.getAudioChannels() > 0 && grabber.getSampleRate() > 0) {
                    audioOpened = audioPlayer.open(grabber.getSampleRate(), audioChannels, 16);
                    LaPluma.getLogger().log(Level.INFO, "[Video] Audio opened: " + audioOpened
                            + ", channels=" + audioChannels
                            + ", sampleRate=" + grabber.getSampleRate());
                }

                double frameRate = grabber.getFrameRate();
                if (frameRate <= 0) {
                    frameRate = 30.0;
                }
                long frameDurationNanos = (long) (1_000_000_000.0 / frameRate);
                long lastFrameTime = System.nanoTime();

                int videoFrames = 0;
                int audioFrames = 0;
                while (!finished.get()) {
                    if (paused.get()) {
                        Thread.sleep(50L);
                        lastFrameTime = System.nanoTime();
                        continue;
                    }

                    Frame frame = grabber.grab();
                    if (frame == null) {
                        break;
                    }

                    if (frame.image != null) {
                        videoFrames++;
                        currentTimeMicros = Math.max(currentTimeMicros, grabber.getTimestamp());
                        BufferedImage img = converter.convert(frame);
                        if (img != null) {
                            BufferedImage argb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
                            argb.getGraphics().drawImage(img, 0, 0, null);
                            pendingFrame.set(argb);
                        }

                        long elapsed = System.nanoTime() - lastFrameTime;
                        long sleepNanos = frameDurationNanos - elapsed;
                        if (sleepNanos > 1_000_000L) {
                            Thread.sleep(sleepNanos / 1_000_000L, (int) (sleepNanos % 1_000_000L));
                        }
                        lastFrameTime = System.nanoTime();
                    }

                    if (frame.samples != null && audioOpened) {
                        audioFrames++;
                        writeAudioFrame(frame, audioChannels);
                    }

                    if ((videoFrames + audioFrames) % 120 == 1) {
                        LaPluma.getLogger().log(Level.INFO, "[Video] Frames decoded: video=" + videoFrames
                                + ", audio=" + audioFrames + ", ts=" + currentTimeMicros);
                    }
                }

                currentTimeMicros = Math.max(currentTimeMicros, durationMicros);
                statusText.set("Finished");
                LaPluma.getLogger().log(Level.INFO, "[Video] Decode loop ended, videoFrames=" + videoFrames + " audioFrames=" + audioFrames);
            } catch (Throwable e) {
                failed.set(true);
                statusText.set("Error: " + e.getMessage());
                LaPluma.getLogger().log(Level.WARNING, "[Video] Playback error", e);
            } finally {
                playing.set(false);
                finished.set(true);
                if (grabber != null) {
                    try { grabber.stop(); } catch (Exception ignored) {}
                    try { grabber.release(); } catch (Exception ignored) {}
                }
                if (audioPlayer != null) {
                    audioPlayer.close();
                }
                if (activePlayer == this) {
                    activePlayer = null;
                }
                if (notifyServerOnClose) {
                    ProxyPacketHandler.sendPacket(5, 0, source);
                }
                restoreScreenIfNeeded();
            }
        }, "LaPluma-VideoDecoder");
        decoderThread.setDaemon(true);
        decoderThread.start();
    }

    private void writeAudioFrame(Frame frame, int audioChannels) {
        if (frame.samples == null || frame.samples.length == 0 || audioPlayer == null || !audioPlayer.isOpen()) {
            return;
        }

        Object first = frame.samples[0];
        if (first instanceof ShortBuffer) {
            writeShortBuffers(frame, audioChannels);
        } else if (first instanceof FloatBuffer) {
            writeFloatBuffers(frame, audioChannels);
        }
    }

    private void writeShortBuffers(Frame frame, int audioChannels) {
        ShortBuffer[] buffers = new ShortBuffer[frame.samples.length];
        int sampleCount = Integer.MAX_VALUE;
        for (int i = 0; i < frame.samples.length; i++) {
            if (!(frame.samples[i] instanceof ShortBuffer)) {
                return;
            }
            buffers[i] = ((ShortBuffer) frame.samples[i]).duplicate();
            sampleCount = Math.min(sampleCount, buffers[i].remaining());
        }
        if (sampleCount <= 0) {
            return;
        }

        if (buffers.length == 1) {
            byte[] bytes = new byte[sampleCount * 2];
            for (int i = 0; i < sampleCount; i++) {
                short val = buffers[0].get();
                bytes[i * 2] = (byte) (val & 0xFF);
                bytes[i * 2 + 1] = (byte) ((val >>> 8) & 0xFF);
            }
            audioPlayer.write(bytes, 0, bytes.length);
            return;
        }

        int channels = Math.min(Math.max(audioChannels, 1), buffers.length);
        byte[] bytes = new byte[sampleCount * channels * 2];
        int index = 0;
        for (int sample = 0; sample < sampleCount; sample++) {
            for (int channel = 0; channel < channels; channel++) {
                short val = buffers[channel].get();
                bytes[index++] = (byte) (val & 0xFF);
                bytes[index++] = (byte) ((val >>> 8) & 0xFF);
            }
        }
        audioPlayer.write(bytes, 0, index);
    }

    private void writeFloatBuffers(Frame frame, int audioChannels) {
        FloatBuffer[] buffers = new FloatBuffer[frame.samples.length];
        int sampleCount = Integer.MAX_VALUE;
        for (int i = 0; i < frame.samples.length; i++) {
            if (!(frame.samples[i] instanceof FloatBuffer)) {
                return;
            }
            buffers[i] = ((FloatBuffer) frame.samples[i]).duplicate();
            sampleCount = Math.min(sampleCount, buffers[i].remaining());
        }
        if (sampleCount <= 0) {
            return;
        }

        if (buffers.length == 1) {
            byte[] bytes = new byte[sampleCount * 2];
            for (int i = 0; i < sampleCount; i++) {
                short val = floatToPcm16(buffers[0].get());
                bytes[i * 2] = (byte) (val & 0xFF);
                bytes[i * 2 + 1] = (byte) ((val >>> 8) & 0xFF);
            }
            audioPlayer.write(bytes, 0, bytes.length);
            return;
        }

        int channels = Math.min(Math.max(audioChannels, 1), buffers.length);
        byte[] bytes = new byte[sampleCount * channels * 2];
        int index = 0;
        for (int sample = 0; sample < sampleCount; sample++) {
            for (int channel = 0; channel < channels; channel++) {
                short val = floatToPcm16(buffers[channel].get());
                bytes[index++] = (byte) (val & 0xFF);
                bytes[index++] = (byte) ((val >>> 8) & 0xFF);
            }
        }
        audioPlayer.write(bytes, 0, index);
    }

    private short floatToPcm16(float value) {
        float clamped = Math.max(-1.0f, Math.min(1.0f, value));
        return (short) Math.round(clamped * Short.MAX_VALUE);
    }

    private void stopForReplacement() {
        restoreScreenOnClose = false;
        notifyServerOnClose = false;
        finished.set(true);
        if (audioPlayer != null) {
            audioPlayer.close();
        }
    }

    private void requestClose() {
        finished.set(true);
        if (audioPlayer != null) {
            audioPlayer.close();
        }
        Minecraft.getMinecraft().addScheduledTask(this::showReturnScreen);
    }

    private void restoreScreenIfNeeded() {
        Minecraft.getMinecraft().addScheduledTask(() -> {
            if (!restoreScreenOnClose) {
                return;
            }
            if (Minecraft.getMinecraft().currentScreen == this) {
                showReturnScreen();
            }
        });
    }

    private void showReturnScreen() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen == this) {
            mc.displayGuiScreen(returnScreen);
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawRect(0, 0, this.width, this.height, 0xFF000000);

        BufferedImage frame = pendingFrame.getAndSet(null);
        if (frame != null) {
            uploadFrame(frame);
        }

        if (videoTexture != null && videoTextureLocation != null) {
            Minecraft mc = Minecraft.getMinecraft();
            float videoAspect = (float) texWidth / (float) texHeight;
            float screenAspect = (float) this.width / (float) this.height;
            int renderW;
            int renderH;
            int renderX;
            int renderY;

            if (videoAspect > screenAspect) {
                renderW = this.width;
                renderH = Math.round(this.width / videoAspect);
                renderX = 0;
                renderY = (this.height - renderH) / 2;
            } else {
                renderH = this.height;
                renderW = Math.round(this.height * videoAspect);
                renderX = (this.width - renderW) / 2;
                renderY = 0;
            }

            GlStateManager.pushMatrix();
            GlStateManager.enableTexture2D();
            GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
            mc.getTextureManager().bindTexture(videoTextureLocation);
            Gui.drawScaledCustomSizeModalRect(renderX, renderY, 0, 0, texWidth, texHeight, renderW, renderH, texWidth, texHeight);
            GlStateManager.popMatrix();
        } else if (!playing.get() && !finished.get()) {
            this.drawCenteredString(this.fontRenderer, statusText.get(), this.width / 2, this.height / 2, 0xFFFFAA00);
        }

        if (failed.get()) {
            this.drawCenteredString(this.fontRenderer, "\u00A7c" + statusText.get(), this.width / 2, this.height / 2, 0xFFFF5555);
        }
        if (paused.get() && playing.get()) {
            this.drawCenteredString(this.fontRenderer, "\u00A7e|| \u5df2\u6682\u505c", this.width / 2, this.height / 2 - 20, 0xFFFFFF);
        }

        drawTimeText();
        if (allowSkip) {
            drawSkipButton(mouseX, mouseY);
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void uploadFrame(BufferedImage frame) {
        TextureManager texManager = Minecraft.getMinecraft().getTextureManager();
        if (texWidth != frame.getWidth() || texHeight != frame.getHeight() || videoTexture == null) {
            if (videoTextureLocation != null) {
                texManager.deleteTexture(videoTextureLocation);
            }
            texWidth = frame.getWidth();
            texHeight = frame.getHeight();
            videoTexture = new DynamicTexture(texWidth, texHeight);
            videoTextureLocation = texManager.getDynamicTextureLocation("lapluma_video", videoTexture);
        }

        int[] texData = videoTexture.getTextureData();
        frame.getRGB(0, 0, texWidth, texHeight, texData, 0, texWidth);
        videoTexture.updateDynamicTexture();
    }

    private void drawTimeText() {
        if (durationMicros <= 0) {
            return;
        }
        long currentSec = Math.max(0, currentTimeMicros / 1_000_000L);
        long totalSec = Math.max(currentSec, durationMicros / 1_000_000L);
        String timeText = String.format("%d:%02d / %d:%02d", currentSec / 60, currentSec % 60, totalSec / 60, totalSec % 60);
        this.drawString(this.fontRenderer, timeText, 8, this.height - 16, 0xFFAAAAAA);
    }

    private void drawSkipButton(int mouseX, int mouseY) {
        if (skipButton != null) {
            skipButton.updatePosition(this);
            skipButton.drawButton(this.mc, mouseX, mouseY, 0.0f);
        }
    }

    @Override
    public void handleMouseInput() throws java.io.IOException {
        super.handleMouseInput();
        int scroll = Mouse.getEventDWheel();
        if (scroll != 0 && audioPlayer != null) {
            float delta = scroll > 0 ? 0.05f : -0.05f;
            audioPlayer.setVolume(audioPlayer.getVolume() + delta);
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            if (allowSkip) {
                requestClose();
            }
        } else if (keyCode == Keyboard.KEY_SPACE) {
            paused.set(!paused.get());
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws java.io.IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (allowSkip && mouseButton == 0 && skipButton != null && skipButton.mousePressed(this.mc, mouseX, mouseY)) {
            requestClose();
        }
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        finished.set(true);
        if (videoTextureLocation != null) {
            Minecraft.getMinecraft().getTextureManager().deleteTexture(videoTextureLocation);
            videoTextureLocation = null;
        }
        videoTexture = null;
        if (activePlayer == this) {
            activePlayer = null;
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
