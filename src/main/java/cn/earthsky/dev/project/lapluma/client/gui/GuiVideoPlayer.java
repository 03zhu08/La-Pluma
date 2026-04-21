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
import org.bytedeco.javacv.FFmpegFrameFilter;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GuiVideoPlayer extends GuiScreen {

    private static volatile GuiVideoPlayer activePlayer;
    private static final String DEFAULT_HTTP_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36";
    private static final int HTTP_CONNECT_TIMEOUT_MS = 10_000;
    private static final int HTTP_READ_TIMEOUT_MS = 15_000;
    private static final int MAX_HTTP_REDIRECTS = 8;
    private static final int MAX_HTML_BYTES = 32_768;
    private static final int MAX_PENDING_VIDEO_FRAMES = 3;
    private static final int STARTUP_PREBUFFER_VIDEO_FRAMES = 3;
    private static final long STARTUP_PREBUFFER_AUDIO_MICROS = 100_000L;
    private static final long VIDEO_AUDIO_LEAD_MICROS = 20_000L;
    private static final Pattern TARGET_INPUT_PATTERN = Pattern.compile(
            "<input[^>]*id=[\"']target[\"'][^>]*value=[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern META_REFRESH_PATTERN = Pattern.compile(
            "<meta[^>]*http-equiv=[\"']refresh[\"'][^>]*content=[\"'][^\"']*url=([^\"'>]+)[\"']",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern JS_REDIRECT_PATTERN = Pattern.compile(
            "(?:location(?:\\.href)?|window\\.location)\\s*=\\s*[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE);

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
    private final Deque<TimedVideoFrame> pendingFrames = new ArrayDeque<>();

    private volatile long durationMicros = 0;
    private volatile long currentTimeMicros = 0;
    private volatile boolean restoreScreenOnClose = true;
    private volatile boolean notifyServerOnClose = true;
    private volatile boolean audioClockActive = false;

    private Thread decoderThread;
    private VideoAudioPlayer audioPlayer;
    private Java2DFrameConverter converter;

    private GuiSmallButton skipButton;

    public GuiVideoPlayer(String source) {
        this(source, true, null);
    }

    public GuiVideoPlayer(String source, boolean allowSkip, GuiScreen returnScreen) {
        this.source = normalizeSource(source);
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
        synchronized (pendingFrames) {
            pendingFrames.clear();
        }
        audioPlayer = new VideoAudioPlayer();

        decoderThread = new Thread(() -> {
            FFmpegFrameGrabber grabber = null;
            FFmpegFrameFilter audioFilter = null;
            try {
                NativeExtractor.ensureExtracted();
                converter = new Java2DFrameConverter();
                ResolvedVideoSource resolvedSource = resolveVideoSource(source);
                grabber = new FFmpegFrameGrabber(resolvedSource.playbackUrl);
                grabber.setImageMode(FFmpegFrameGrabber.ImageMode.COLOR);
                configureGrabber(grabber, resolvedSource);
                grabber.start();

                durationMicros = Math.max(0, grabber.getLengthInTime());
                statusText.set("Buffering...");

                LaPluma.getLogger().log(Level.INFO, "[Video] Started: " + resolvedSource.playbackUrl
                        + " (" + grabber.getImageWidth() + "x" + grabber.getImageHeight()
                        + ", " + String.format("%.1f", grabber.getFrameRate()) + "fps)");

                final int inputAudioChannels = Math.max(1, grabber.getAudioChannels());
                int outputAudioChannels = inputAudioChannels;
                int outputSampleRate = Math.max(1, grabber.getSampleRate());
                boolean audioOpened = false;
                if (grabber.getAudioChannels() > 0 && grabber.getSampleRate() > 0) {
                    try {
                        outputAudioChannels = normalizeOutputAudioChannels(inputAudioChannels);
                        outputSampleRate = normalizeOutputSampleRate(grabber.getSampleRate());
                        audioFilter = createAudioFilter(inputAudioChannels, outputAudioChannels, outputSampleRate);
                    } catch (Throwable filterError) {
                        audioFilter = null;
                        outputAudioChannels = inputAudioChannels;
                        outputSampleRate = Math.max(1, grabber.getSampleRate());
                        LaPluma.getLogger().log(Level.WARNING, "[Video] Audio filter unavailable, using raw decoded samples", filterError);
                    }

                    audioOpened = audioPlayer.open(outputSampleRate, outputAudioChannels, 16);
                    LaPluma.getLogger().log(Level.INFO, "[Video] Audio opened: " + audioOpened
                            + ", inputChannels=" + inputAudioChannels
                            + ", outputChannels=" + outputAudioChannels
                            + ", inputSampleRate=" + grabber.getSampleRate()
                            + ", outputSampleRate=" + outputSampleRate
                            + ", filtered=" + (audioFilter != null));
                }
                audioClockActive = false;
                boolean playbackStarted = false;
                if (!audioOpened) {
                    playbackStarted = true;
                    playing.set(true);
                    statusText.set("Playing");
                }

                long playbackStartMicros = -1L;
                long playbackStartNanos = -1L;

                int videoFrames = 0;
                int audioFrames = 0;
                while (!finished.get()) {
                    if (paused.get()) {
                        Thread.sleep(50L);
                        playbackStartMicros = -1L;
                        playbackStartNanos = -1L;
                        continue;
                    }

                    Frame frame = grabber.grab();
                    if (frame == null) {
                        break;
                    }

                    long frameTimestampMicros = Math.max(0L, grabber.getTimestamp());
                    if (playbackStartMicros < 0L) {
                        playbackStartMicros = frameTimestampMicros;
                        playbackStartNanos = System.nanoTime();
                    }
                    currentTimeMicros = Math.max(currentTimeMicros, frameTimestampMicros);

                    if (frame.image != null) {
                        videoFrames++;
                        if (!audioOpened) {
                            syncVideoFrame(frameTimestampMicros, playbackStartMicros, playbackStartNanos);
                        }
                        BufferedImage img = converter.convert(frame);
                        if (img != null) {
                            BufferedImage argb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
                            argb.getGraphics().drawImage(img, 0, 0, null);
                            enqueueVideoFrame(frameTimestampMicros, argb);
                        }
                    }

                    if (frame.samples != null && audioOpened) {
                        audioFrames++;
                        if (audioFilter != null) {
                            writeFilteredAudioFrame(audioFilter, frame, outputAudioChannels);
                        } else {
                            writeAudioFrame(frame, outputAudioChannels);
                        }
                    }

                    if (!playbackStarted && shouldStartPlayback(audioOpened)) {
                        audioPlayer.start();
                        audioClockActive = audioOpened;
                        playbackStarted = true;
                        playing.set(true);
                        statusText.set("Playing");
                        LaPluma.getLogger().log(Level.INFO, "[Video] Playback started after prebuffer: videoFrames="
                                + getPendingFrameCount() + ", audioBufferedMicros=" + audioPlayer.getBufferedMicros());
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
                audioClockActive = false;
                if (grabber != null) {
                    try { grabber.stop(); } catch (Exception ignored) {}
                    try { grabber.release(); } catch (Exception ignored) {}
                }
                if (audioFilter != null) {
                    try { audioFilter.stop(); } catch (Exception ignored) {}
                    try { audioFilter.release(); } catch (Exception ignored) {}
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

    private boolean shouldStartPlayback(boolean audioOpened) {
        if (!audioOpened) {
            return getPendingFrameCount() >= 1;
        }
        return getPendingFrameCount() >= STARTUP_PREBUFFER_VIDEO_FRAMES
                && audioPlayer != null
                && audioPlayer.getBufferedMicros() >= STARTUP_PREBUFFER_AUDIO_MICROS;
    }

    private void enqueueVideoFrame(long timestampMicros, BufferedImage image) {
        synchronized (pendingFrames) {
            pendingFrames.addLast(new TimedVideoFrame(timestampMicros, image));
            while (pendingFrames.size() > MAX_PENDING_VIDEO_FRAMES) {
                pendingFrames.removeFirst();
            }
        }
    }

    private int getPendingFrameCount() {
        synchronized (pendingFrames) {
            return pendingFrames.size();
        }
    }

    private FFmpegFrameFilter createAudioFilter(int inputAudioChannels, int outputAudioChannels, int outputSampleRate)
            throws FFmpegFrameFilter.Exception {
        String channelLayout = outputAudioChannels == 1 ? "mono" : "stereo";
        String filters = "aresample=" + outputSampleRate + ",aformat=sample_fmts=s16:channel_layouts=" + channelLayout;
        FFmpegFrameFilter filter = new FFmpegFrameFilter(filters, inputAudioChannels);
        filter.setAudioInputs(1);
        filter.setAudioChannels(outputAudioChannels);
        filter.setSampleRate(outputSampleRate);
        filter.start();
        return filter;
    }

    private int normalizeOutputAudioChannels(int inputAudioChannels) {
        return inputAudioChannels <= 1 ? 1 : 2;
    }

    private int normalizeOutputSampleRate(int inputSampleRate) {
        if (inputSampleRate == 44100 || inputSampleRate == 48000) {
            return inputSampleRate;
        }
        return 48000;
    }

    private void syncVideoFrame(long frameTimestampMicros, long playbackStartMicros, long playbackStartNanos) throws InterruptedException {
        long targetNanos = playbackStartNanos + Math.max(0L, frameTimestampMicros - playbackStartMicros) * 1000L;
        long sleepNanos = targetNanos - System.nanoTime();
        if (sleepNanos <= 0L) {
            return;
        }

        long sleepMillis = sleepNanos / 1_000_000L;
        int extraNanos = (int) (sleepNanos % 1_000_000L);
        Thread.sleep(sleepMillis, extraNanos);
    }

    private void writeFilteredAudioFrame(FFmpegFrameFilter audioFilter, Frame frame, int audioChannels)
            throws FFmpegFrameFilter.Exception {
        audioFilter.push(frame);
        Frame filteredFrame;
        while ((filteredFrame = audioFilter.pullSamples()) != null) {
            writeAudioFrame(filteredFrame, audioChannels);
        }
    }

    private ResolvedVideoSource resolveVideoSource(String rawSource) {
        rawSource = normalizeSource(rawSource);
        if (!isHttpSource(rawSource)) {
            return new ResolvedVideoSource(rawSource, null);
        }

        String currentUrl = rawSource;
        String referer = null;
        for (int redirectCount = 0; redirectCount < MAX_HTTP_REDIRECTS; redirectCount++) {
            HttpURLConnection connection = null;
            try {
                connection = openHttpConnection(currentUrl, referer);
                int status = connection.getResponseCode();
                if (isRedirectStatus(status)) {
                    String location = connection.getHeaderField("Location");
                    if (location == null || location.trim().isEmpty()) {
                        break;
                    }

                    referer = currentUrl;
                    currentUrl = new URL(new URL(currentUrl), location).toExternalForm();
                    continue;
                }

                String embeddedTarget = extractEmbeddedTarget(connection, currentUrl);
                if (embeddedTarget != null && !embeddedTarget.equals(currentUrl)) {
                    referer = currentUrl;
                    currentUrl = embeddedTarget;
                    continue;
                }

                if (!isRedirectStatus(status)) {
                    LaPluma.getLogger().log(Level.INFO, "[Video] Resolved source: " + rawSource + " -> " + currentUrl
                            + ", status=" + status);
                    return new ResolvedVideoSource(currentUrl, referer);
                }
            } catch (IOException e) {
                LaPluma.getLogger().log(Level.INFO, "[Video] Failed to pre-resolve source, using original URL: " + rawSource, e);
                break;
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }

        return new ResolvedVideoSource(currentUrl, referer);
    }

    private String extractEmbeddedTarget(HttpURLConnection connection, String baseUrl) throws IOException {
        String contentType = connection.getContentType();
        if (contentType == null || !contentType.toLowerCase().contains("text/html")) {
            return null;
        }

        String html = readResponseBody(connection);
        String extracted = findEmbeddedUrl(html, TARGET_INPUT_PATTERN);
        if (extracted == null) {
            extracted = findEmbeddedUrl(html, META_REFRESH_PATTERN);
        }
        if (extracted == null) {
            extracted = findEmbeddedUrl(html, JS_REDIRECT_PATTERN);
        }
        if (extracted == null || extracted.isEmpty()) {
            return null;
        }

        String decoded = decodeHtmlEntities(extracted.trim());
        return new URL(new URL(baseUrl), decoded).toExternalForm();
    }

    private String findEmbeddedUrl(String html, Pattern pattern) {
        Matcher matcher = pattern.matcher(html);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(1);
    }

    private String readResponseBody(HttpURLConnection connection) throws IOException {
        StringBuilder body = new StringBuilder();
        char[] buffer = new char[2048];
        int remaining = MAX_HTML_BYTES;
        try (BufferedReader reader = new BufferedReader(
                new java.io.InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            while (remaining > 0) {
                int read = reader.read(buffer, 0, Math.min(buffer.length, remaining));
                if (read < 0) {
                    break;
                }
                body.append(buffer, 0, read);
                remaining -= read;
            }
        }
        return body.toString();
    }

    private String decodeHtmlEntities(String value) {
        return value
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">");
    }

    private HttpURLConnection openHttpConnection(String url, String referer) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(HTTP_CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(HTTP_READ_TIMEOUT_MS);
        connection.setRequestProperty("User-Agent", DEFAULT_HTTP_USER_AGENT);
        connection.setRequestProperty("Accept", "*/*");
        connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        connection.setRequestProperty("Connection", "keep-alive");
        if (referer != null && !referer.isEmpty()) {
            connection.setRequestProperty("Referer", referer);
        }
        return connection;
    }

    private void configureGrabber(FFmpegFrameGrabber grabber, ResolvedVideoSource resolvedSource) {
        if (!isHttpSource(resolvedSource.playbackUrl)) {
            return;
        }

        StringBuilder headers = new StringBuilder();
        headers.append("User-Agent: ").append(DEFAULT_HTTP_USER_AGENT).append("\r\n");
        headers.append("Accept: */*\r\n");
        headers.append("Accept-Language: zh-CN,zh;q=0.9,en;q=0.8\r\n");
        headers.append("Connection: keep-alive\r\n");
        if (resolvedSource.referer != null && !resolvedSource.referer.isEmpty()) {
            headers.append("Referer: ").append(resolvedSource.referer).append("\r\n");
        }

        grabber.setOption("user_agent", DEFAULT_HTTP_USER_AGENT);
        grabber.setOption("headers", headers.toString());
        grabber.setOption("reconnect", "1");
        grabber.setOption("reconnect_streamed", "1");
        grabber.setOption("reconnect_on_network_error", "1");
        grabber.setOption("rw_timeout", String.valueOf(HTTP_READ_TIMEOUT_MS * 1000L));
        grabber.setOption("timeout", String.valueOf(HTTP_CONNECT_TIMEOUT_MS * 1000L));
    }

    private boolean isHttpSource(String url) {
        return url != null && (url.startsWith("http://") || url.startsWith("https://"));
    }

    private boolean isRedirectStatus(int status) {
        return status == HttpURLConnection.HTTP_MOVED_PERM
                || status == HttpURLConnection.HTTP_MOVED_TEMP
                || status == HttpURLConnection.HTTP_SEE_OTHER
                || status == 307
                || status == 308;
    }

    private static class ResolvedVideoSource {
        private final String playbackUrl;
        private final String referer;

        private ResolvedVideoSource(String playbackUrl, String referer) {
            this.playbackUrl = playbackUrl;
            this.referer = referer;
        }
    }

    private String normalizeSource(String rawSource) {
        return rawSource == null ? "" : rawSource.trim();
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
            writePackedShortBuffer(buffers[0]);
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
            writePackedFloatBuffer(buffers[0]);
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

    private void writePackedShortBuffer(ShortBuffer buffer) {
        int totalSamples = buffer.remaining();
        if (totalSamples <= 0) {
            return;
        }

        byte[] bytes = new byte[totalSamples * 2];
        for (int i = 0; i < totalSamples; i++) {
            short val = buffer.get();
            bytes[i * 2] = (byte) (val & 0xFF);
            bytes[i * 2 + 1] = (byte) ((val >>> 8) & 0xFF);
        }
        audioPlayer.write(bytes, 0, bytes.length);
    }

    private void writePackedFloatBuffer(FloatBuffer buffer) {
        int totalSamples = buffer.remaining();
        if (totalSamples <= 0) {
            return;
        }

        byte[] bytes = new byte[totalSamples * 2];
        for (int i = 0; i < totalSamples; i++) {
            short val = floatToPcm16(buffer.get());
            bytes[i * 2] = (byte) (val & 0xFF);
            bytes[i * 2 + 1] = (byte) ((val >>> 8) & 0xFF);
        }
        audioPlayer.write(bytes, 0, bytes.length);
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

        uploadReadyFrames();

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

    private void uploadReadyFrames() {
        long playbackMicros = getPlaybackClockMicros();
        BufferedImage frameToUpload = null;
        synchronized (pendingFrames) {
            while (!pendingFrames.isEmpty()) {
                TimedVideoFrame nextFrame = pendingFrames.peekFirst();
                if (audioClockActive && nextFrame.timestampMicros > playbackMicros + VIDEO_AUDIO_LEAD_MICROS) {
                    break;
                }
                frameToUpload = pendingFrames.removeFirst().image;
            }
        }

        if (frameToUpload != null) {
            uploadFrame(frameToUpload);
        }
    }

    private long getPlaybackClockMicros() {
        if (audioClockActive && audioPlayer != null && audioPlayer.isOpen()) {
            long audioMicros = audioPlayer.getPlaybackPositionMicros();
            currentTimeMicros = Math.max(currentTimeMicros, audioMicros);
            return audioMicros;
        }
        return currentTimeMicros;
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
        synchronized (pendingFrames) {
            pendingFrames.clear();
        }
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

    private static class TimedVideoFrame {
        private final long timestampMicros;
        private final BufferedImage image;

        private TimedVideoFrame(long timestampMicros, BufferedImage image) {
            this.timestampMicros = timestampMicros;
            this.image = image;
        }
    }
}
