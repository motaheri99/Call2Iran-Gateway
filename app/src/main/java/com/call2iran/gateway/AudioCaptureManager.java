package com.call2iran.gateway;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

public class AudioCaptureManager {

    private static final String TAG = "AudioCapture";
    private static final int SAMPLE_RATE = DTMFDecoder.getSampleRate();
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    private AudioRecord audioRecord;
    private Thread captureThread;
    private volatile boolean isCapturing = false;
    private final DTMFDecoder decoder;

    public AudioCaptureManager(DTMFDecoder decoder) {
        this.decoder = decoder;
    }

    public boolean startCapture() {
        int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            bufferSize = SAMPLE_RATE * 2;
        }
        bufferSize = Math.max(bufferSize, SAMPLE_RATE);

        try {
            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
            );
        } catch (SecurityException e) {
            Log.e(TAG, "No RECORD_AUDIO permission", e);
            return false;
        }

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize");
            audioRecord.release();
            audioRecord = null;
            return false;
        }

        isCapturing = true;
        decoder.reset();

        audioRecord.startRecording();

        captureThread = new Thread(this::captureLoop, "DTMFCapture");
        captureThread.start();

        Log.d(TAG, "Audio capture started");
        return true;
    }

    public void stopCapture() {
        isCapturing = false;

        if (captureThread != null) {
            try {
                captureThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            captureThread = null;
        }

        if (audioRecord != null) {
            try {
                if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop();
                }
            } catch (IllegalStateException e) {
                Log.w(TAG, "Error stopping AudioRecord", e);
            }
            audioRecord.release();
            audioRecord = null;
        }

        Log.d(TAG, "Audio capture stopped");
    }

    private void captureLoop() {
        int blockSize = DTMFDecoder.getBlockSize();
        short[] buffer = new short[blockSize * 4];

        while (isCapturing) {
            int read = audioRecord.read(buffer, 0, buffer.length);
            if (read > 0) {
                decoder.processAudioBlock(buffer, 0, read);
            } else if (read < 0) {
                Log.e(TAG, "AudioRecord read error: " + read);
                break;
            }
        }
    }

    public boolean isCapturing() {
        return isCapturing;
    }
}
