/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.ui;

import audiomanager.core.DependencyManager;

import javafx.application.Platform;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A simple amplitude-only waveform preview for the currently selected file,
 * decoded via FFmpeg (already a hard dependency of this app) on a background
 * thread so file selection never blocks the UI.
 *
 * <p>Scope is deliberately minimal — this renders a peak-amplitude envelope,
 * not a spectrogram or anything requiring real audio analysis. Files over
 * {@link #MAX_FILE_SIZE_BYTES} are skipped with a message rather than
 * attempting a decode that could tie up the (single-threaded) decode queue
 * for a very long time.</p>
 *
 * <p>Deliberately does NOT use {@code ProcessRunner} for the actual decode:
 * every helper on that class is built around line-based text output
 * (splitting on newlines, decoding as a String), which would corrupt raw
 * 16-bit PCM audio bytes — a real risk here since this is the first place
 * in the app that needs binary subprocess output rather than text/log
 * lines. Uses {@code ProcessBuilder} directly instead, reading FFmpeg's
 * stdout as a raw byte stream.</p>
 */
public class WaveformView extends Canvas {

    private static final Logger LOGGER = LoggerFactory.getLogger(WaveformView.class);

    private static final long MAX_FILE_SIZE_BYTES = 200L * 1024 * 1024; // 200MB
    // 8kHz mono is plenty of resolution for a peak-amplitude preview and
    // keeps decoded PCM small (~57MB/hour) even though it's buffered fully
    // in memory before bucketing — decoding at the source sample rate would
    // cost many times that for no visible benefit at this canvas size.
    private static final int DECODE_SAMPLE_RATE = 8000;

    private final DependencyManager dependencyManager;
    private final ExecutorService decodeExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "WaveformView-Decoder");
        t.setDaemon(true);
        return t;
    });

    // Guards against a stale decode (from a previously-selected file)
    // finishing AFTER the user has already selected a different file and
    // overwriting the correct, newer waveform with an outdated one.
    private final AtomicLong requestId = new AtomicLong(0);

    public WaveformView(DependencyManager dependencyManager, double width, double height) {
        super(width, height);
        this.dependencyManager = dependencyManager;
        clear();
    }

    /** Clears the canvas and invalidates any in-flight decode for a previous file. */
    public void clear() {
        requestId.incrementAndGet();
        GraphicsContext gc = getGraphicsContext2D();
        gc.setFill(Color.web("#ecf0f1"));
        gc.fillRect(0, 0, getWidth(), getHeight());
    }

    /**
     * Decode and render {@code file}'s amplitude envelope on a background
     * thread. Safe to call repeatedly (e.g. once per file selection) — only
     * the most recently requested file's result is ever drawn, regardless
     * of decode completion order.
     */
    public void loadAndRender(File file) {
        long myRequestId = requestId.incrementAndGet();

        if (file == null || !file.isFile()) {
            clear();
            return;
        }
        if (file.length() > MAX_FILE_SIZE_BYTES) {
            drawMessage("Preview skipped (file over 200MB)");
            return;
        }

        drawMessage("Loading waveform...");

        int pixelWidth = Math.max(1, (int) getWidth());
        decodeExecutor.submit(() -> {
            try {
                float[] amplitudes = decodeAmplitudes(file, pixelWidth);
                if (myRequestId != requestId.get()) return; // superseded by a newer selection
                Platform.runLater(() -> {
                    if (myRequestId == requestId.get()) {
                        drawWaveform(amplitudes);
                    }
                });
            } catch (Exception e) {
                LOGGER.warn("Waveform decode failed for {}: {}", file.getName(), e.getMessage());
                if (myRequestId == requestId.get()) {
                    Platform.runLater(() -> drawMessage("Preview unavailable"));
                }
            }
        });
    }

    /**
     * Decodes {@code file} to raw mono 16-bit PCM via FFmpeg and reduces it
     * to {@code buckets} peak-amplitude samples, normalized to [0, 1].
     */
    private float[] decodeAmplitudes(File file, int buckets) throws IOException, InterruptedException {
        String ffmpegPath = dependencyManager.getFFmpegPath();
        if (ffmpegPath == null) {
            throw new IOException("FFmpeg path not resolved");
        }

        ProcessBuilder builder = new ProcessBuilder(
                ffmpegPath, "-v", "quiet", "-i", file.getAbsolutePath(),
                "-f", "s16le", "-ac", "1", "-ar", String.valueOf(DECODE_SAMPLE_RATE), "-"
        );
        Process process = builder.start();

        byte[] pcm;
        try (InputStream in = process.getInputStream()) {
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) != -1) {
                raw.write(buf, 0, read);
            }
            pcm = raw.toByteArray();
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            process.waitFor(5, TimeUnit.SECONDS);
        }

        int sampleCount = pcm.length / 2; // 16-bit samples
        float[] result = new float[buckets];
        if (sampleCount == 0) {
            return result; // silent/undecodable — flat line rather than an error
        }

        int samplesPerBucket = Math.max(1, sampleCount / buckets);
        for (int b = 0; b < buckets; b++) {
            int start = b * samplesPerBucket;
            int end = Math.min(sampleCount, start + samplesPerBucket);
            short peak = 0;
            for (int i = start; i < end; i++) {
                int byteIndex = i * 2;
                short sample = (short) ((pcm[byteIndex] & 0xFF) | (pcm[byteIndex + 1] << 8));
                short abs = (short) Math.abs(sample);
                if (abs > peak) peak = abs;
            }
            result[b] = peak / 32768.0f;
        }
        return result;
    }

    private void drawWaveform(float[] amplitudes) {
        GraphicsContext gc = getGraphicsContext2D();
        double w = getWidth(), h = getHeight();
        gc.setFill(Color.web("#ecf0f1"));
        gc.fillRect(0, 0, w, h);

        gc.setStroke(Color.web("#3498db"));
        gc.setLineWidth(1.0);
        double mid = h / 2.0;
        double barWidth = w / amplitudes.length;
        for (int i = 0; i < amplitudes.length; i++) {
            double barHeight = amplitudes[i] * (h / 2.0);
            double x = i * barWidth;
            gc.strokeLine(x, mid - barHeight, x, mid + barHeight);
        }
    }

    private void drawMessage(String message) {
        GraphicsContext gc = getGraphicsContext2D();
        gc.setFill(Color.web("#ecf0f1"));
        gc.fillRect(0, 0, getWidth(), getHeight());
        gc.setFill(Color.web("#7f8c8d"));
        gc.setFont(Font.font(11));
        gc.fillText(message, 8, getHeight() / 2 + 4);
    }

    @Override
    public boolean isResizable() { return true; }
    @Override
    public double prefWidth(double height) { return getWidth(); }
    @Override
    public double prefHeight(double width) { return getHeight(); }
}