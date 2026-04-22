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
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
    private static final int MAX_PENDING_VIDEO_FRAMES = 900;
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
    private final AtomicBoolean finished = new AtomicBoolean(false);
    private final AtomicBoolean failed = new AtomicBoolean(false);
    private final AtomicReference<String> statusText = new AtomicReference<>("视频加载中...");
    private final Deque<TimedVideoFrame> pendingFrames = new ArrayDeque<>();

    private volatile long durationMicros = 0;
    private volatile long currentTimeMicros = 0;
    private volatile long playbackStartNanos = -1L;
    private volatile int prebufferFrameTarget = 5;
    private volatile boolean restoreScreenOnClose = true;
    private volatile boolean notifyServerOnClose = true;

    private Thread decoderThread;
    private VideoAudioPlayer audioPlayer;
    private ByteArrayOutputStream audioPreBuffer;

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
        statusText.set("视频加载中...");
        synchronized (pendingFrames) {
            pendingFrames.clear();
        }
        audioPlayer = new VideoAudioPlayer();

        decoderThread = new Thread(() -> {
            FFmpegFrameGrabber grabber = null;
            try {
                NativeExtractor.ensureExtracted();
                ResolvedVideoSource resolvedSource = resolveVideoSource(source);
                String grabberSource = resolvedSource.playbackUrl;
                boolean isLocalPlayback = !isHttpSource(grabberSource);
                if (!isLocalPlayback) {
                    String cached = getCachedVideoPath(grabberSource);
                    if (cached != null) {
                        grabberSource = cached;
                        isLocalPlayback = true;
                    } else {
                        String downloaded = downloadToCache(grabberSource, resolvedSource.referer);
                        if (downloaded != null) {
                            grabberSource = downloaded;
                            isLocalPlayback = true;
                        }
                    }
                }
                grabber = new FFmpegFrameGrabber(grabberSource);
                grabber.setImageMode(FFmpegFrameGrabber.ImageMode.COLOR);
                grabber.setPixelFormat(avutil.AV_PIX_FMT_BGRA);
                if (isHttpSource(grabberSource)) {
                    configureGrabber(grabber, resolvedSource);
                }
                grabber.start();

                durationMicros = Math.max(0, grabber.getLengthInTime());
                double frameRate = grabber.getFrameRate() > 0 ? grabber.getFrameRate() : 30.0;
                int totalFrames = (int)(durationMicros / 1_000_000.0 * frameRate);
                prebufferFrameTarget = isLocalPlayback ? 5 : Math.max(5, (int)(totalFrames * 0.2));
                statusText.set("视频缓冲中...");

                LaPluma.getLogger().log(Level.INFO, "[Video] Started: " + resolvedSource.playbackUrl
                        + " (" + grabber.getImageWidth() + "x" + grabber.getImageHeight()
                        + ", " + String.format("%.1f", grabber.getFrameRate()) + "fps"
                        + ", prebuffer=" + prebufferFrameTarget + " frames)");

                final int audioChannels = Math.max(1, grabber.getAudioChannels());
                final boolean hasAudio = grabber.getAudioChannels() > 0 && grabber.getSampleRate() > 0;
                final int sampleRate = grabber.getSampleRate();
                boolean audioOpened = false;
                boolean playbackStarted = false;
                audioPreBuffer = hasAudio ? new ByteArrayOutputStream() : null;

                int videoFrames = 0;
                int audioFrames = 0;
                while (!finished.get()) {
                    Frame frame = grabber.grab();
                    if (frame == null) {
                        break;
                    }

                    long frameTimestampMicros = Math.max(0L, grabber.getTimestamp());
                    currentTimeMicros = Math.max(currentTimeMicros, frameTimestampMicros);

                    if (frame.image != null && frame.image.length > 0) {
                        videoFrames++;
                        ByteBuffer buf = (ByteBuffer) frame.image[0];
                        int w = frame.imageWidth;
                        int h = frame.imageHeight;
                        int stride = frame.imageStride;
                        int[] pixels = new int[w * h];
                        buf.order(ByteOrder.LITTLE_ENDIAN);
                        buf.position(0);
                        if (stride == w * 4) {
                            buf.asIntBuffer().get(pixels);
                        } else {
                            for (int row = 0; row < h; row++) {
                                buf.position(row * stride);
                                buf.asIntBuffer().get(pixels, row * w, w);
                            }
                        }
                        enqueueVideoFrame(frameTimestampMicros, w, h, pixels);
                    }

                    if (frame.samples != null && hasAudio) {
                        audioFrames++;
                        writeAudioFrame(frame, audioChannels);
                    }

                    if (!playbackStarted && videoFrames >= prebufferFrameTarget) {
                        int audioBufferedBytes = 0;
                        if (hasAudio) {
                            audioOpened = audioPlayer.open(sampleRate, audioChannels, 16);
                            byte[] buffered = audioPreBuffer.toByteArray();
                            audioBufferedBytes = buffered.length;
                            audioPreBuffer = null;
                            if (buffered.length > 0) {
                                audioPlayer.write(buffered, 0, buffered.length);
                            }
                        }
                        playbackStartNanos = System.nanoTime();
                        playbackStarted = true;
                        playing.set(true);
                        statusText.set("视频播放中");
                        LaPluma.getLogger().log(Level.INFO, "[Video] Playback started after prebuffer: "
                                + videoFrames + " frames, audio buffered=" + audioBufferedBytes + " bytes");
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

    private void enqueueVideoFrame(long timestampMicros, int width, int height, int[] pixels) {
        synchronized (pendingFrames) {
            pendingFrames.addLast(new TimedVideoFrame(timestampMicros, width, height, pixels));
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

    private static File getVideoCacheDir() {
        File dir = new File(Minecraft.getMinecraft().gameDir, "lapluma" + File.separator + "cache" + File.separator + "videos");
        dir.mkdirs();
        return dir;
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    private static String getUrlExtension(String url) {
        try {
            String path = new URL(url).getPath();
            int lastDot = path.lastIndexOf('.');
            int lastSlash = path.lastIndexOf('/');
            if (lastDot > lastSlash && lastDot < path.length() - 1) {
                String ext = path.substring(lastDot);
                if (ext.length() <= 6) return ext;
            }
        } catch (Exception ignored) {}
        return ".mp4";
    }

    private String getCacheFileName(String url) {
        return sha256(url) + getUrlExtension(url);
    }

    private String getCachedVideoPath(String url) {
        File cached = new File(getVideoCacheDir(), getCacheFileName(url));
        if (cached.isFile() && cached.length() > 0) {
            LaPluma.getLogger().log(Level.INFO, "[Video] Cache hit: " + cached.getAbsolutePath());
            return cached.getAbsolutePath();
        }
        return null;
    }

    private String downloadToCache(String url, String referer) {
        File cacheDir = getVideoCacheDir();
        File targetFile = new File(cacheDir, getCacheFileName(url));
        File tmpFile = new File(cacheDir, sha256(url) + ".tmp" + getUrlExtension(url));

        HttpURLConnection conn = null;
        try {
            conn = openHttpConnection(url, referer);
            int status = conn.getResponseCode();
            if (status != 200) {
                LaPluma.getLogger().log(Level.WARNING, "[Video] Download failed, status=" + status);
                return null;
            }

            long contentLength = conn.getContentLengthLong();
            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(tmpFile)) {
                byte[] buf = new byte[65536];
                int read;
                long downloaded = 0;
                while ((read = in.read(buf)) != -1 && !finished.get()) {
                    out.write(buf, 0, read);
                    downloaded += read;
                    if (contentLength > 0) {
                        int percent = (int)(downloaded * 100 / contentLength);
                        statusText.set("视频下载中... " + percent + "%");
                    } else {
                        statusText.set("视频下载中... " + (downloaded / 1024) + "KB");
                    }
                }
            }

            if (finished.get()) {
                tmpFile.delete();
                return null;
            }

            if (tmpFile.length() > 0 && tmpFile.renameTo(targetFile)) {
                LaPluma.getLogger().log(Level.INFO, "[Video] Cached: " + targetFile.getAbsolutePath());
                return targetFile.getAbsolutePath();
            }
            tmpFile.delete();
        } catch (IOException e) {
            LaPluma.getLogger().log(Level.WARNING, "[Video] Download error", e);
            tmpFile.delete();
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    private void writeAudioBytes(byte[] data, int offset, int length) {
        if (audioPreBuffer != null) {
            audioPreBuffer.write(data, offset, length);
        } else if (audioPlayer != null && audioPlayer.isOpen()) {
            audioPlayer.write(data, offset, length);
        }
    }

    private void writeAudioFrame(Frame frame, int audioChannels) {
        if (frame.samples == null || frame.samples.length == 0) {
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
        writeAudioBytes(bytes, 0, index);
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
        writeAudioBytes(bytes, 0, index);
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
        writeAudioBytes(bytes, 0, bytes.length);
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
        writeAudioBytes(bytes, 0, bytes.length);
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
            this.drawCenteredString(this.fontRenderer, "§c" + statusText.get(), this.width / 2, this.height / 2, 0xFFFF5555);
        }
        drawTimeText();
        if (allowSkip) {
            drawSkipButton(mouseX, mouseY);
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void uploadReadyFrames() {
        if (playbackStartNanos < 0) return;
        long elapsedMicros = (System.nanoTime() - playbackStartNanos) / 1000L;
        TimedVideoFrame frameToUpload = null;
        synchronized (pendingFrames) {
            while (!pendingFrames.isEmpty()) {
                TimedVideoFrame next = pendingFrames.peekFirst();
                if (next.timestampMicros > elapsedMicros) {
                    break;
                }
                frameToUpload = pendingFrames.removeFirst();
            }
        }

        if (frameToUpload != null) {
            uploadFrame(frameToUpload);
        }
    }

    private void uploadFrame(TimedVideoFrame frame) {
        TextureManager texManager = Minecraft.getMinecraft().getTextureManager();
        if (texWidth != frame.width || texHeight != frame.height || videoTexture == null) {
            if (videoTextureLocation != null) {
                texManager.deleteTexture(videoTextureLocation);
            }
            texWidth = frame.width;
            texHeight = frame.height;
            videoTexture = new DynamicTexture(texWidth, texHeight);
            videoTextureLocation = texManager.getDynamicTextureLocation("lapluma_video", videoTexture);
        }

        int[] texData = videoTexture.getTextureData();
        System.arraycopy(frame.pixels, 0, texData, 0, Math.min(frame.pixels.length, texData.length));
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
        private final int width;
        private final int height;
        private final int[] pixels;

        private TimedVideoFrame(long timestampMicros, int width, int height, int[] pixels) {
            this.timestampMicros = timestampMicros;
            this.width = width;
            this.height = height;
            this.pixels = pixels;
        }
    }
}
