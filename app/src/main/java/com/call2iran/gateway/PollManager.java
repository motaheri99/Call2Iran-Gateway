package com.call2iran.gateway;

import android.content.Context;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.telecom.Call;
import android.telecom.TelecomManager;
import android.util.Log;

public class PollManager {

    private static final String TAG = "PollManager";
    private static final long DTMF_LISTEN_TIMEOUT_MS = 15000;
    private static final long LINE_SETTLE_DELAY_MS = 1000;

    public interface PollCallback {
        void onPollResult(JobData job, String error);
    }

    private final Context context;
    private final AppSettings settings;
    private final Handler handler;
    private DTMFDecoder dtmfDecoder;
    private DTMFSender dtmfSender;
    private AudioCaptureManager audioCaptureManager;
    private Call pollCall;
    private PollCallback callback;
    private Runnable timeoutRunnable;
    private volatile boolean pollInProgress = false;

    public PollManager(Context context, AppSettings settings) {
        this.context = context;
        this.settings = settings;
        this.handler = new Handler(Looper.getMainLooper());
        this.dtmfSender = new DTMFSender();
    }

    public void startPoll(PollCallback callback) {
        if (pollInProgress) {
            callback.onPollResult(null, "Poll already in progress");
            return;
        }

        this.callback = callback;
        this.pollInProgress = true;

        if (settings.isTestMode()) {
            Log.d(TAG, "Test mode: simulating poll");
            handler.postDelayed(() -> {
                pollInProgress = false;
                JobData fakeJob = JobData.parse("09121234567*14165551234*2");
                callback.onPollResult(fakeJob, null);
            }, 1000);
            return;
        }

        String telnyxNumber = settings.getTelnyxNumber();
        if (telnyxNumber.isEmpty()) {
            pollInProgress = false;
            callback.onPollResult(null, "No Telnyx number configured");
            return;
        }

        dtmfDecoder = new DTMFDecoder();
        audioCaptureManager = new AudioCaptureManager(dtmfDecoder);

        GatewayInCallService inCallService = GatewayInCallService.getInstance();
        if (inCallService == null) {
            pollInProgress = false;
            callback.onPollResult(null, "InCallService not available");
            return;
        }

        inCallService.setCallEventListener(pollCallListener);

        Log.d(TAG, "Placing poll call to " + telnyxNumber);
        placeCall(telnyxNumber);
    }

    private void placeCall(String number) {
        try {
            TelecomManager telecomManager = (TelecomManager) context.getSystemService(Context.TELECOM_SERVICE);
            Uri uri = Uri.fromParts("tel", number, null);
            android.os.Bundle extras = new android.os.Bundle();
            telecomManager.placeCall(uri, extras);
        } catch (SecurityException e) {
            Log.e(TAG, "Permission denied for placing call", e);
            finishPoll(null, "Permission denied: CALL_PHONE");
        } catch (Exception e) {
            Log.e(TAG, "Error placing poll call", e);
            finishPoll(null, "Failed to place call: " + e.getMessage());
        }
    }

    private final GatewayInCallService.CallEventListener pollCallListener = new GatewayInCallService.CallEventListener() {
        @Override
        public void onCallStateChanged(Call call, int state) {
            if (call != pollCall) return;

            Log.d(TAG, "Poll call state: " + GatewayInCallService.stateToString(state));

            if (state == Call.STATE_ACTIVE) {
                onPollCallConnected(call);
            } else if (state == Call.STATE_DISCONNECTED) {
                onPollCallDisconnected();
            }
        }

        @Override
        public void onCallAdded(Call call) {
            if (pollCall == null && pollInProgress) {
                pollCall = call;
                Log.d(TAG, "Poll call tracked");
            }
        }

        @Override
        public void onCallRemoved(Call call) {
            if (call == pollCall) {
                onPollCallDisconnected();
            }
        }
    };

    private void onPollCallConnected(Call call) {
        Log.d(TAG, "Poll call connected, setting up audio");

        GatewayInCallService inCallService = GatewayInCallService.getInstance();
        if (inCallService != null) {
            inCallService.setSpeakerphone(true);
            inCallService.setMute(true);
        }

        handler.postDelayed(() -> {
            if (!pollInProgress) return;

            String dtmfToSend;
            if (settings.hasPendingReport()) {
                int duration = settings.getPendingDuration();
                dtmfToSend = duration + "#";
                Log.d(TAG, "Reporting duration: " + dtmfToSend);
            } else {
                dtmfToSend = "0#";
                Log.d(TAG, "Nothing to report: 0#");
            }

            dtmfSender.sendDtmfString(call, dtmfToSend, () -> {
                Log.d(TAG, "DTMF sent, starting to listen for response");
                settings.clearPendingReport();
                startListeningForResponse();
            });
        }, LINE_SETTLE_DELAY_MS);
    }

    private void startListeningForResponse() {
        dtmfDecoder.setMessageListener(message -> {
            Log.d(TAG, "DTMF message received: " + message);
            handler.post(() -> {
                cancelTimeout();
                hangupPollCall();

                JobData job = JobData.parse(message + "#");
                if (job != null) {
                    Log.d(TAG, "Job received: " + job);
                } else {
                    Log.d(TAG, "No job (response was: " + message + ")");
                }
                finishPoll(job, null);
            });
        });

        if (!audioCaptureManager.startCapture()) {
            Log.e(TAG, "Failed to start audio capture");
            hangupPollCall();
            finishPoll(null, "Failed to start audio capture");
            return;
        }

        timeoutRunnable = () -> {
            Log.w(TAG, "DTMF listen timeout - no response from server");
            audioCaptureManager.stopCapture();
            hangupPollCall();
            finishPoll(null, "DTMF timeout");
        };
        handler.postDelayed(timeoutRunnable, DTMF_LISTEN_TIMEOUT_MS);
    }

    private void cancelTimeout() {
        if (timeoutRunnable != null) {
            handler.removeCallbacks(timeoutRunnable);
            timeoutRunnable = null;
        }
    }

    private void hangupPollCall() {
        if (pollCall != null) {
            try {
                pollCall.disconnect();
            } catch (Exception e) {
                Log.e(TAG, "Error disconnecting poll call", e);
            }
        }

        GatewayInCallService inCallService = GatewayInCallService.getInstance();
        if (inCallService != null) {
            inCallService.setSpeakerphone(false);
            inCallService.setMute(false);
        }

        if (audioCaptureManager != null && audioCaptureManager.isCapturing()) {
            audioCaptureManager.stopCapture();
        }
    }

    private void onPollCallDisconnected() {
        Log.d(TAG, "Poll call disconnected");
        cancelTimeout();

        if (audioCaptureManager != null && audioCaptureManager.isCapturing()) {
            audioCaptureManager.stopCapture();
        }

        if (pollInProgress && callback != null) {
            finishPoll(null, null);
        }
    }

    private void finishPoll(JobData job, String error) {
        if (!pollInProgress) return;
        pollInProgress = false;

        GatewayInCallService inCallService = GatewayInCallService.getInstance();
        if (inCallService != null) {
            inCallService.setCallEventListener(null);
        }

        pollCall = null;

        if (callback != null) {
            PollCallback cb = callback;
            callback = null;
            cb.onPollResult(job, error);
        }
    }

    public void cancel() {
        cancelTimeout();
        hangupPollCall();
        finishPoll(null, "Cancelled");
    }

    public boolean isPolling() {
        return pollInProgress;
    }
}
