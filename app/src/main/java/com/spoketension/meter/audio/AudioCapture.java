package com.spoketension.meter.audio;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;

/**
 * Records audio from the microphone. This is the ONLY class in the audio package that uses Android.
 *
 * DIFFERENCE FROM THE OLD VERSION: the audio source is chosen in order of preference
 *   1. UNPROCESSED       : raw audio, not processed by the phone (if the device supports it)
 *   2. VOICE_RECOGNITION : little processing, no automatic gain control
 *   3. MIC               : the default; on many phones it is processed to emphasise the voice
 * The source actually used is returned so it can be displayed and logged with the experiment data,
 * because results may differ between phone models.
 */
public final class AudioCapture {

    private AudioCapture() { }

    public static final class Recording {
        public final short[] pcm;          // 16-bit audio samples
        public final String sourceName;    // audio source that was used

        Recording(short[] pcm, String sourceName) {
            this.pcm = pcm;
            this.sourceName = sourceName;
        }
    }

    /**
     * A running microphone stream. Read and close it on the capture thread. stop() may be called
     * from the UI thread to interrupt a blocking read; read() then returns -1. The read itself never
     * holds the state lock, so Stop does not have to wait for another audio block to arrive.
     */
    public static final class Stream implements AutoCloseable {
        public final String sourceName;
        private final AudioRecord recorder;
        private final Object stateLock = new Object();
        private volatile boolean stopped;
        private boolean released;

        private Stream(AudioRecord recorder, String sourceName) {
            this.recorder = recorder;
            this.sourceName = sourceName;
        }

        public int read(short[] buffer) {
            return read(buffer, 0, buffer.length);
        }

        public int read(short[] buffer, int offset, int count) {
            if (stopped) return -1;
            try {
                int read = recorder.read(buffer, offset, count, AudioRecord.READ_BLOCKING);
                if (stopped) return -1;
                if (read <= 0) throw new IllegalStateException("Audio read error: " + read);
                return read;
            } catch (IllegalStateException e) {
                if (stopped) return -1;
                throw e;
            }
        }

        public void stop() {
            synchronized (stateLock) {
                if (stopped) return;
                stopped = true;
                if (!released) {
                    try {
                        recorder.stop();
                    } catch (IllegalStateException ignored) {
                        // Already stopped by the platform (e.g. microphone access revoked).
                    }
                }
            }
        }

        @Override public void close() {
            stop();
            synchronized (stateLock) {
                if (!released) {
                    released = true;
                    recorder.release();
                }
            }
        }
    }

    /** Opens and starts recording. Caller must check RECORD_AUDIO permission before calling. */
    @SuppressLint("MissingPermission")
    public static Stream openStream(Context context, int sampleRate) {
        int minBytes = AudioRecord.getMinBufferSize(sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBytes <= 0) throw new IllegalStateException(sampleRate + " Hz recording unsupported");
        int bufferBytes = Math.max(minBytes, 8192 * 2);

        int[] sources = {
                MediaRecorder.AudioSource.UNPROCESSED,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC
        };
        String[] names = {"UNPROCESSED", "VOICE_RECOGNITION", "MIC"};
        boolean unprocessedSupported = "true".equals(((AudioManager)
                context.getSystemService(Context.AUDIO_SERVICE))
                .getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED));

        RuntimeException lastFailure = null;
        for (int i = 0; i < sources.length; i++) {
            if (i == 0 && !unprocessedSupported) continue;
            AudioRecord candidate = null;
            try {
                candidate = new AudioRecord(sources[i], sampleRate,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferBytes);
                if (candidate.getState() != AudioRecord.STATE_INITIALIZED)
                    throw new IllegalStateException("Cannot initialize microphone source " + names[i]);
                candidate.startRecording();
                if (candidate.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
                    throw new IllegalStateException("Cannot start microphone source " + names[i]);
                return new Stream(candidate, names[i]);
            } catch (RuntimeException e) {
                lastFailure = e;
                if (candidate != null) candidate.release();
            }
        }
        throw new IllegalStateException("Cannot initialize microphone", lastFailure);
    }

    /** Records synchronously (blocks the thread). Call from a background thread, NOT the UI thread. */
    public static Recording record(Context context, int sampleRate, double seconds) {
        short[] pcm = new short[(int) (sampleRate * seconds)];
        try (Stream stream = openStream(context, sampleRate)) {
            int offset = 0;
            while (offset < pcm.length) {
                int count = stream.read(pcm, offset, Math.min(2048, pcm.length - offset));
                if (count <= 0) throw new IllegalStateException("Audio read error: " + count);
                offset += count;
            }
            return new Recording(pcm, stream.sourceName);
        }
    }

    /** Converts 16-bit samples (-32768..32767) to floating point (-1..1) for computation. */
    public static double[] toDouble(short[] pcm) {
        double[] x = new double[pcm.length];
        for (int i = 0; i < pcm.length; i++) x[i] = pcm[i] / 32768.0;
        return x;
    }
}
