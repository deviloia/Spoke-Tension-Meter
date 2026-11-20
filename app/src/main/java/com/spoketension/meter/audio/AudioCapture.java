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

    /** Records synchronously (blocks the thread). Call from a background thread, NOT the UI thread. */
    @SuppressLint("MissingPermission")   // LabActivity checks the permission before calling
    public static Recording record(Context context, int sampleRate, double seconds) {
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

        AudioRecord recorder = null;
        String used = null;
        for (int i = 0; i < sources.length; i++) {
            if (i == 0 && !unprocessedSupported) continue;
            AudioRecord candidate = new AudioRecord(sources[i], sampleRate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferBytes);
            if (candidate.getState() == AudioRecord.STATE_INITIALIZED) {
                recorder = candidate;
                used = names[i];
                break;
            }
            candidate.release();
        }
        if (recorder == null) throw new IllegalStateException("Cannot initialize microphone");

        short[] pcm = new short[(int) (sampleRate * seconds)];
        try {
            recorder.startRecording();
            int offset = 0;
            while (offset < pcm.length) {
                int count = recorder.read(pcm, offset,
                        Math.min(2048, pcm.length - offset), AudioRecord.READ_BLOCKING);
                if (count <= 0) throw new IllegalStateException("Audio read error: " + count);
                offset += count;
            }
            return new Recording(pcm, used);
        } finally {
            if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) recorder.stop();
            recorder.release();
        }
    }

    /** Converts 16-bit samples (-32768..32767) to floating point (-1..1) for computation. */
    public static double[] toDouble(short[] pcm) {
        double[] x = new double[pcm.length];
        for (int i = 0; i < pcm.length; i++) x[i] = pcm[i] / 32768.0;
        return x;
    }
}
