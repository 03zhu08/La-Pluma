package cn.earthsky.dev.project.lapluma.client.audio;

import cn.earthsky.dev.project.lapluma.LaPluma;
import lombok.Getter;

import javax.sound.sampled.*;
import java.util.logging.Level;

public class VideoAudioPlayer {

    private SourceDataLine line;
    @Getter
    private float volume = 1.0f;
    private boolean closed = false;
    private int bytesPerSecond = 0;

    public boolean open(int sampleRate, int channels, int sampleSizeInBits) {
        try {
            AudioFormat format = new AudioFormat(sampleRate, sampleSizeInBits, channels, true, false);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
            line = (SourceDataLine) AudioSystem.getLine(info);
            int frameSize = Math.max(1, channels * (sampleSizeInBits / 8));
            bytesPerSecond = sampleRate * frameSize;
            int bufferSize = sampleRate * channels * (sampleSizeInBits / 8) * 2;
            line.open(format, bufferSize);
            line.start();
            return true;
        } catch (LineUnavailableException e) {
            LaPluma.getLogger().log(Level.WARNING, "[VideoAudio] Failed to open audio line", e);
            return false;
        }
    }

    public void start() {
    }

    public void write(byte[] data, int offset, int length) {
        if (line != null && !closed) {
            line.write(data, offset, length);
        }
    }

    public void setVolume(float vol) {
        this.volume = Math.max(0f, Math.min(1f, vol));
        if (line != null && line.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
            FloatControl control = (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN);
            float dB = (float) (20.0 * Math.log10(Math.max(0.0001, this.volume)));
            dB = Math.max(control.getMinimum(), Math.min(control.getMaximum(), dB));
            control.setValue(dB);
        }
    }

    public long getPlaybackPositionMicros() {
        if (line == null || closed) return 0L;
        return Math.max(0L, line.getMicrosecondPosition());
    }

    public long getBufferedMicros() {
        if (line == null || closed || bytesPerSecond <= 0) return 0L;
        long queuedBytes = Math.max(0, line.getBufferSize() - line.available());
        return (queuedBytes * 1_000_000L) / bytesPerSecond;
    }

    public void close() {
        closed = true;
        if (line != null) {
            line.stop();
            line.flush();
            line.close();
            line = null;
        }
        bytesPerSecond = 0;
    }

    public boolean isOpen() {
        return line != null && !closed;
    }

}
