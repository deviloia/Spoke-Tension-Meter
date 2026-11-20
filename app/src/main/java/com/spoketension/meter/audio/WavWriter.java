package com.spoketension.meter.audio;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Writes the raw audio to a .wav file (16-bit PCM, mono).
 *
 * Why: the raw audio is data that CANNOT BE RECORDED AGAIN.
 *  - When the algorithm picks the wrong tone, listen to the file to hear what the app "heard".
 *  - Later it can serve as a dataset for the deep-learning part, and new algorithms can be
 *    re-run on old data for a fair comparison.
 *
 * No Android dependencies.
 */
public final class WavWriter {

    private WavWriter() { }

    public static void write(File file, short[] pcm, int sampleRate) throws IOException {
        int dataBytes = pcm.length * 2;
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(file))) {
            // Standard 44-byte WAV header
            out.write(new byte[]{'R', 'I', 'F', 'F'});
            writeInt(out, 36 + dataBytes);
            out.write(new byte[]{'W', 'A', 'V', 'E', 'f', 'm', 't', ' '});
            writeInt(out, 16);                 // size of the fmt chunk
            writeShort(out, 1);                // 1 = PCM
            writeShort(out, 1);                // 1 channel (mono)
            writeInt(out, sampleRate);
            writeInt(out, sampleRate * 2);     // bytes per second
            writeShort(out, 2);                // bytes per sample
            writeShort(out, 16);               // bits per sample
            out.write(new byte[]{'d', 'a', 't', 'a'});
            writeInt(out, dataBytes);
            // Audio data, little-endian
            for (short s : pcm) writeShort(out, s);
        }
    }

    private static void writeInt(OutputStream out, int v) throws IOException {
        out.write(v & 0xff);
        out.write((v >> 8) & 0xff);
        out.write((v >> 16) & 0xff);
        out.write((v >> 24) & 0xff);
    }

    private static void writeShort(OutputStream out, int v) throws IOException {
        out.write(v & 0xff);
        out.write((v >> 8) & 0xff);
    }
}
