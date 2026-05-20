package com.call2iran.gateway;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.telecom.Call;
import android.telecom.TelecomManager;
import android.util.Log;

import java.io.File;

public class CallBridgeManager {

    private static final String TAG = "CallBridge";
    private static final long IRAN_ANSWER_TIMEOUT_MS = 60000;
    private static final long INTL_ANSWER_TIMEOUT_MS = 60000;
    private static final int WARNING_BEEP_BEFORE_END_SEC = 30;

    public interface BridgeCallback {
        void onBridgeStateChanged(GatewayState state);
        void onBridgeComplete(int durationSeconds, String error);
    }

    private enum BridgePhase {
        IDLE, WAITING_SCHEDULE, CALLING_IRAN, PLAYING_MESSAGE, HOLDING_IRAN,
        CALLING_INTL, MERGING, BRIDGED, ENDING
    }

    private final Context context;
    private final AppSettings settings;
    private final Handler handler;
    private BridgeCallback callback;

    private JobData currentJob;
    private BridgePhase phase = BridgePhase.IDLE;
    private Call iranCall;
    private Call intlCall;
    private long bridgeStartTime;
    private int finalDuration;
    private MediaPlayer mediaPlayer;
    private Runnable warningBeepRunnable;
    private Runnable maxTimeRunnable;
    private Runnable iranTimeoutRunnable;
    private Runnable intlTimeoutRunnable;
    private Runnable scheduleRunnable;

    public CallBridgeManager(Context context, AppSettings settings) {
        this.context = context;
        this.settings = settings;
        this.handler = new Handler(Looper.getMainLooper());
    }

    public void startBridge(JobData job, BridgeCallback callback) {
        this.currentJob = job;
        this.callback = callback;
        this.phase = BridgePhase.IDLE;
        this.iranCall = null;
        this.intlCall = null;
        this.bridgeStartTime = 0;
        this.finalDuration = 0;

        if (job.getDelayMinutes() > 0) {
            long delayMs = job.getDelayMinutes() * 60L * 1000L;
            Log.d(TAG, "Scheduling call in " + job.getDelayMinutes() + " minutes");
            phase = BridgePhase.WAITING_SCHEDULE;
            notifyState(GatewayState.WAITING_SCHEDULE);

            scheduleRunnable = () -> {
                scheduleRunnable = null;
                startCallingIran();
            };
            handler.postDelayed(scheduleRunnable, delayMs);
        } else {
            startCallingIran();
        }
    }

    private void startCallingIran() {
        phase = BridgePhase.CALLING_IRAN;
        notifyState(GatewayState.CALLING_IRAN);

        GatewayInCallService inCallService = GatewayInCallService.getInstance();
        if (inCallService == null) {
            finishBridge(0, "InCallService not available");
            return;
        }

        inCallService.setCallEventListener(bridgeCallListener);

        Log.d(TAG, "Calling Iran number: " + currentJob.getIranNumber());

        if (settings.isTestMode()) {
            Log.d(TAG, "Test mode: simulating Iran call connect");
            handler.postDelayed(() -> {
                phase = BridgePhase.PLAYING_MESSAGE;
                handler.postDelayed(this::onIranMessagePlayed, 2000);
            }, 2000);
            return;
        }

        placeCall(currentJob.getIranNumber());

        iranTimeoutRunnable = () -> {
            Log.w(TAG, "Iran call answer timeout");
            cleanup();
            finishBridge(0, "Iran number did not answer");
        };
        handler.postDelayed(iranTimeoutRunnable, IRAN_ANSWER_TIMEOUT_MS);
    }

    private void onIranCallConnected(Call call) {
        iranCall = call;
        cancelTimeout(iranTimeoutRunnable);
        iranTimeoutRunnable = null;

        Log.d(TAG, "Iran call connected, playing message");
        phase = BridgePhase.PLAYING_MESSAGE;

        playHoldMessage(() -> {
            onIranMessagePlayed();
        });
    }

    private void onIranMessagePlayed() {
        Log.d(TAG, "Message played, putting Iran on hold and calling intl");

        if (!settings.isTestMode() && iranCall != null) {
            phase = BridgePhase.HOLDING_IRAN;
            GatewayInCallService inCallService = GatewayInCallService.getInstance();
            if (inCallService != null) {
                inCallService.holdCall(iranCall);
            }
        }

        handler.postDelayed(this::startCallingIntl, 500);
    }

    private void startCallingIntl() {
        phase = BridgePhase.CALLING_INTL;
        notifyState(GatewayState.CALLING_INTL);

        String intlDialNumber = currentJob.getIntlDialNumber();
        Log.d(TAG, "Calling international number: " + intlDialNumber);

        if (settings.isTestMode()) {
            Log.d(TAG, "Test mode: simulating international call connect");
            handler.postDelayed(() -> {
                onBothCallsReady();
            }, 2000);
            return;
        }

        placeCall(intlDialNumber);

        intlTimeoutRunnable = () -> {
            Log.w(TAG, "International call answer timeout");
            cleanup();
            finishBridge(0, "International number did not answer");
        };
        handler.postDelayed(intlTimeoutRunnable, INTL_ANSWER_TIMEOUT_MS);
    }

    private void onIntlCallConnected(Call call) {
        intlCall = call;
        cancelTimeout(intlTimeoutRunnable);
        intlTimeoutRunnable = null;

        Log.d(TAG, "International call connected, merging conference");
        onBothCallsReady();
    }

    private void onBothCallsReady() {
        phase = BridgePhase.MERGING;

        if (!settings.isTestMode()) {
            GatewayInCallService inCallService = GatewayInCallService.getInstance();
            if (inCallService != null) {
                if (iranCall != null && iranCall.getState() == Call.STATE_HOLDING) {
                    inCallService.unholdCall(iranCall);
                }

                handler.postDelayed(() -> {
                    if (iranCall != null && intlCall != null) {
                        Log.d(TAG, "Merging calls into conference");
                        inCallService.mergeConferenceDirect(iranCall, intlCall);
                    }
                    startBridgeTimer();
                }, 1000);
            }
        } else {
            startBridgeTimer();
        }
    }

    private void startBridgeTimer() {
        phase = BridgePhase.BRIDGED;
        notifyState(GatewayState.BRIDGED);

        bridgeStartTime = System.currentTimeMillis();
        int maxSeconds = currentJob.getMaxMinutes() * 60;

        int warningTime = maxSeconds - WARNING_BEEP_BEFORE_END_SEC;
        if (warningTime > 0) {
            warningBeepRunnable = () -> {
                Log.d(TAG, "Playing warning beep");
                playWarningBeep();
            };
            handler.postDelayed(warningBeepRunnable, warningTime * 1000L);
        }

        maxTimeRunnable = () -> {
            Log.d(TAG, "Max time reached, disconnecting");
            endBridge();
        };
        handler.postDelayed(maxTimeRunnable, maxSeconds * 1000L);

        Log.d(TAG, "Bridge active. Max duration: " + maxSeconds + "s");
    }

    private void endBridge() {
        if (phase == BridgePhase.ENDING || phase == BridgePhase.IDLE) return;
        phase = BridgePhase.ENDING;

        finalDuration = calculateDuration();
        Log.d(TAG, "Bridge ending. Duration: " + finalDuration + "s");

        cleanup();
        finishBridge(finalDuration, null);
    }

    private void onCallDisconnected(Call call) {
        if (phase == BridgePhase.ENDING || phase == BridgePhase.IDLE) return;

        if (call == iranCall || call == intlCall) {
            Log.d(TAG, "One party disconnected, ending bridge");
            endBridge();
        }
    }

    private final GatewayInCallService.CallEventListener bridgeCallListener = new GatewayInCallService.CallEventListener() {
        @Override
        public void onCallStateChanged(Call call, int state) {
            Log.d(TAG, "Bridge call state: " + GatewayInCallService.stateToString(state) +
                    " phase=" + phase);

            switch (phase) {
                case CALLING_IRAN:
                    if (state == Call.STATE_ACTIVE && iranCall == null) {
                        onIranCallConnected(call);
                    } else if (state == Call.STATE_DISCONNECTED) {
                        cancelTimeout(iranTimeoutRunnable);
                        iranTimeoutRunnable = null;
                        cleanup();
                        finishBridge(0, "Iran call failed");
                    }
                    break;

                case HOLDING_IRAN:
                case CALLING_INTL:
                    if (state == Call.STATE_ACTIVE && call != iranCall && intlCall == null) {
                        onIntlCallConnected(call);
                    } else if (state == Call.STATE_DISCONNECTED) {
                        if (call == iranCall) {
                            cancelTimeout(intlTimeoutRunnable);
                            intlTimeoutRunnable = null;
                            cleanup();
                            finishBridge(0, "Iran call dropped while calling intl");
                        } else if (intlCall == null) {
                            cancelTimeout(intlTimeoutRunnable);
                            intlTimeoutRunnable = null;
                            cleanup();
                            finishBridge(0, "International call failed");
                        }
                    }
                    break;

                case MERGING:
                case BRIDGED:
                    if (state == Call.STATE_DISCONNECTED) {
                        onCallDisconnected(call);
                    }
                    break;

                default:
                    break;
            }
        }

        @Override
        public void onCallAdded(Call call) {
            Log.d(TAG, "Bridge: call added in phase " + phase);
            if (phase == BridgePhase.CALLING_IRAN && iranCall == null) {
                iranCall = call;
            } else if ((phase == BridgePhase.CALLING_INTL || phase == BridgePhase.HOLDING_IRAN)
                    && intlCall == null) {
                intlCall = call;
            }
        }

        @Override
        public void onCallRemoved(Call call) {
            Log.d(TAG, "Bridge: call removed in phase " + phase);
            if (phase == BridgePhase.BRIDGED || phase == BridgePhase.MERGING) {
                onCallDisconnected(call);
            }
        }
    };

    private void placeCall(String number) {
        try {
            TelecomManager telecomManager = (TelecomManager) context.getSystemService(Context.TELECOM_SERVICE);
            Uri uri = Uri.fromParts("tel", number, null);
            android.os.Bundle extras = new android.os.Bundle();
            telecomManager.placeCall(uri, extras);
        } catch (SecurityException e) {
            Log.e(TAG, "Permission denied for placing call", e);
            cleanup();
            finishBridge(0, "Permission denied: CALL_PHONE");
        } catch (Exception e) {
            Log.e(TAG, "Error placing call", e);
            cleanup();
            finishBridge(0, "Failed to place call: " + e.getMessage());
        }
    }

    private void playHoldMessage(Runnable onComplete) {
        String voicePath = settings.getVoiceFilePath();

        if (voicePath.isEmpty()) {
            Log.d(TAG, "No voice file configured, skipping message");
            if (onComplete != null) onComplete.run();
            return;
        }

        try {
            File file = new File(voicePath);
            if (!file.exists()) {
                Log.w(TAG, "Voice file not found: " + voicePath);
                if (onComplete != null) onComplete.run();
                return;
            }

            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(voicePath);
            mediaPlayer.setAudioStreamType(AudioManager.STREAM_VOICE_CALL);
            mediaPlayer.setOnCompletionListener(mp -> {
                releaseMediaPlayer();
                if (onComplete != null) onComplete.run();
            });
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "MediaPlayer error: " + what + "/" + extra);
                releaseMediaPlayer();
                if (onComplete != null) onComplete.run();
                return true;
            });
            mediaPlayer.prepare();
            mediaPlayer.start();
            Log.d(TAG, "Playing hold message");
        } catch (Exception e) {
            Log.e(TAG, "Error playing hold message", e);
            releaseMediaPlayer();
            if (onComplete != null) onComplete.run();
        }
    }

    private void releaseMediaPlayer() {
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                }
                mediaPlayer.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing MediaPlayer", e);
            }
            mediaPlayer = null;
        }
    }

    private void playWarningBeep() {
        try {
            ToneGenerator toneGenerator = new ToneGenerator(AudioManager.STREAM_VOICE_CALL, 80);
            toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 500);
            handler.postDelayed(toneGenerator::release, 600);
        } catch (Exception e) {
            Log.e(TAG, "Error playing warning beep", e);
        }
    }

    private int calculateDuration() {
        if (bridgeStartTime <= 0) return 0;
        return (int) ((System.currentTimeMillis() - bridgeStartTime) / 1000);
    }

    private void cancelTimeout(Runnable runnable) {
        if (runnable != null) {
            handler.removeCallbacks(runnable);
        }
    }

    private void cleanup() {
        cancelTimeout(warningBeepRunnable);
        warningBeepRunnable = null;
        cancelTimeout(maxTimeRunnable);
        maxTimeRunnable = null;
        cancelTimeout(iranTimeoutRunnable);
        iranTimeoutRunnable = null;
        cancelTimeout(intlTimeoutRunnable);
        intlTimeoutRunnable = null;
        cancelTimeout(scheduleRunnable);
        scheduleRunnable = null;

        releaseMediaPlayer();

        if (!settings.isTestMode()) {
            GatewayInCallService inCallService = GatewayInCallService.getInstance();
            if (inCallService != null) {
                inCallService.disconnectAllCalls();
            }
        }

        iranCall = null;
        intlCall = null;
    }

    private void notifyState(GatewayState state) {
        if (callback != null) {
            callback.onBridgeStateChanged(state);
        }
    }

    private void finishBridge(int durationSeconds, String error) {
        if (phase == BridgePhase.IDLE) return;
        phase = BridgePhase.IDLE;

        GatewayInCallService inCallService = GatewayInCallService.getInstance();
        if (inCallService != null) {
            inCallService.setCallEventListener(null);
        }

        if (callback != null) {
            BridgeCallback cb = callback;
            callback = null;
            cb.onBridgeComplete(durationSeconds, error);
        }
    }

    public void cancel() {
        Log.d(TAG, "Bridge cancelled");
        cleanup();
        finishBridge(calculateDuration(), "Cancelled");
    }

    public boolean isActive() {
        return phase != BridgePhase.IDLE;
    }
}
