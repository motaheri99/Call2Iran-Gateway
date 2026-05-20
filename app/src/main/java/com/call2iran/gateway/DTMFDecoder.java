package com.call2iran.gateway;

public class DTMFDecoder {

    private static final int SAMPLE_RATE = 8000;
    private static final int BLOCK_SIZE = 205;

    private static final float[] LOW_FREQS = {697f, 770f, 852f, 941f};
    private static final float[] HIGH_FREQS = {1209f, 1336f, 1477f, 1633f};

    private static final char[][] DTMF_TABLE = {
            {'1', '2', '3', 'A'},
            {'4', '5', '6', 'B'},
            {'7', '8', '9', 'C'},
            {'*', '0', '#', 'D'}
    };

    private static final float MAGNITUDE_THRESHOLD = 50.0f;
    private static final int MIN_DETECTION_FRAMES = 2;  // ~50ms at 25ms per frame
    private static final int MIN_SILENCE_FRAMES = 2;    // ~50ms gap between digits

    private char lastDetected = 0;
    private int detectionCount = 0;
    private int silenceCount = 0;
    private boolean digitAccepted = false;

    private final StringBuilder buffer = new StringBuilder();

    public interface MessageListener {
        void onMessageReceived(String message);
    }

    private MessageListener listener;

    public void setMessageListener(MessageListener listener) {
        this.listener = listener;
    }

    public void reset() {
        buffer.setLength(0);
        lastDetected = 0;
        detectionCount = 0;
        silenceCount = 0;
        digitAccepted = false;
    }

    public String getBuffer() {
        return buffer.toString();
    }

    public void processAudioBlock(short[] samples, int offset, int length) {
        if (length < BLOCK_SIZE) {
            return;
        }

        int blocksToProcess = length / BLOCK_SIZE;
        for (int b = 0; b < blocksToProcess; b++) {
            int blockOffset = offset + b * BLOCK_SIZE;
            char digit = detectDTMF(samples, blockOffset);
            processDetection(digit);
        }
    }

    private char detectDTMF(short[] samples, int offset) {
        float[] lowMagnitudes = new float[LOW_FREQS.length];
        float[] highMagnitudes = new float[HIGH_FREQS.length];

        for (int i = 0; i < LOW_FREQS.length; i++) {
            lowMagnitudes[i] = goertzel(samples, offset, BLOCK_SIZE, LOW_FREQS[i]);
        }
        for (int i = 0; i < HIGH_FREQS.length; i++) {
            highMagnitudes[i] = goertzel(samples, offset, BLOCK_SIZE, HIGH_FREQS[i]);
        }

        int bestLow = -1;
        float bestLowMag = 0;
        for (int i = 0; i < lowMagnitudes.length; i++) {
            if (lowMagnitudes[i] > bestLowMag) {
                bestLowMag = lowMagnitudes[i];
                bestLow = i;
            }
        }

        int bestHigh = -1;
        float bestHighMag = 0;
        for (int i = 0; i < highMagnitudes.length; i++) {
            if (highMagnitudes[i] > bestHighMag) {
                bestHighMag = highMagnitudes[i];
                bestHigh = i;
            }
        }

        if (bestLow < 0 || bestHigh < 0) {
            return 0;
        }
        if (bestLowMag < MAGNITUDE_THRESHOLD || bestHighMag < MAGNITUDE_THRESHOLD) {
            return 0;
        }

        float lowSecondBest = 0;
        for (int i = 0; i < lowMagnitudes.length; i++) {
            if (i != bestLow && lowMagnitudes[i] > lowSecondBest) {
                lowSecondBest = lowMagnitudes[i];
            }
        }
        float highSecondBest = 0;
        for (int i = 0; i < highMagnitudes.length; i++) {
            if (i != bestHigh && highMagnitudes[i] > highSecondBest) {
                highSecondBest = highMagnitudes[i];
            }
        }

        if (lowSecondBest > 0 && bestLowMag / lowSecondBest < 2.0f) {
            return 0;
        }
        if (highSecondBest > 0 && bestHighMag / highSecondBest < 2.0f) {
            return 0;
        }

        return DTMF_TABLE[bestLow][bestHigh];
    }

    private float goertzel(short[] samples, int offset, int length, float targetFreq) {
        float k = 0.5f + ((length * targetFreq) / SAMPLE_RATE);
        float w = (float) (2.0 * Math.PI * k / length);
        float coeff = 2.0f * (float) Math.cos(w);

        float s0 = 0;
        float s1 = 0;
        float s2 = 0;

        for (int i = 0; i < length; i++) {
            float sample = samples[offset + i] / 32768.0f;
            s0 = sample + coeff * s1 - s2;
            s2 = s1;
            s1 = s0;
        }

        float power = s1 * s1 + s2 * s2 - coeff * s1 * s2;
        return (float) Math.sqrt(Math.abs(power));
    }

    private void processDetection(char digit) {
        if (digit == 0) {
            silenceCount++;
            if (silenceCount >= MIN_SILENCE_FRAMES) {
                if (digitAccepted) {
                    digitAccepted = false;
                }
                lastDetected = 0;
                detectionCount = 0;
            }
            return;
        }

        silenceCount = 0;

        if (digit == lastDetected) {
            detectionCount++;
            if (detectionCount >= MIN_DETECTION_FRAMES && !digitAccepted) {
                digitAccepted = true;
                onDigitDetected(digit);
            }
        } else {
            lastDetected = digit;
            detectionCount = 1;
            digitAccepted = false;
        }
    }

    private void onDigitDetected(char digit) {
        if (digit == '#') {
            String message = buffer.toString();
            buffer.setLength(0);
            if (listener != null) {
                listener.onMessageReceived(message);
            }
        } else {
            buffer.append(digit);
        }
    }

    public static int getSampleRate() {
        return SAMPLE_RATE;
    }

    public static int getBlockSize() {
        return BLOCK_SIZE;
    }
}
