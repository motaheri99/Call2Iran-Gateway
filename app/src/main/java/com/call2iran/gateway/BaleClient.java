package com.call2iran.gateway;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

public class BaleClient implements BaleNotificationListener.OnBaleMessageListener {

    private static final String TAG = "BaleClient";

    public interface OnJobReceivedListener {
        void onJobReceived(String callId, String targetPhone, String callerPhone, int maxMinutes);
    }

    public interface OnLogListener {
        void onLog(String message);
    }

    private final AppSettings settings;
    private final Handler mainHandler;
    private boolean listening = false;

    private OnJobReceivedListener jobListener;
    private Runnable onMessageCallback;
    private OnLogListener logListener;

    public BaleClient(AppSettings settings) {
        this.settings = settings;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void setJobListener(OnJobReceivedListener listener) {
        this.jobListener = listener;
    }

    public void setOnMessageCallback(Runnable callback) {
        this.onMessageCallback = callback;
    }

    public void setLogListener(OnLogListener listener) {
        this.logListener = listener;
        BaleNotificationListener.setLogListener(listener != null
                ? listener::onLog : null);
    }

    private void uiLog(String msg) {
        Log.d(TAG, msg);
        if (logListener != null) {
            mainHandler.post(() -> logListener.onLog("Bale: " + msg));
        }
    }

    public void startPolling() {
        if (listening) {
            uiLog("already listening");
            return;
        }
        listening = true;
        BaleNotificationListener.addMessageListener(this);
        uiLog("listening via NotificationListener");
    }

    public void stopPolling() {
        listening = false;
        BaleNotificationListener.removeMessageListener(this);
        uiLog("stopped listening");
    }

    public boolean isConnected() {
        return BaleNotificationListener.getInstance() != null;
    }

    @Override
    public void onBaleMessage(String text) {
        uiLog("msg: " + text.substring(0, Math.min(text.length(), 50)));

        if (onMessageCallback != null) {
            mainHandler.post(onMessageCallback);
        }

        if (text.startsWith("JOB:")) {
            handleJob(text.substring(4));
        } else if (text.startsWith("PING:")) {
            uiLog("ping received");
        }
    }

    private void handleJob(String encryptedPayload) {
        try {
            String key = settings.getBaleEncryptionKey();
            String decrypted = CryptoUtils.decrypt(encryptedPayload, key);

            JSONObject job = new JSONObject(decrypted);
            String callId = job.getString("callId");
            String targetPhone = job.getString("targetPhone");
            String callerPhone = job.getString("callerPhone");
            int maxMinutes = job.getInt("maxMinutes");

            uiLog("job decoded: " + targetPhone + " -> " + callerPhone);

            if (jobListener != null) {
                mainHandler.post(() -> jobListener.onJobReceived(callId, targetPhone, callerPhone, maxMinutes));
            }
        } catch (Exception e) {
            uiLog("failed to process job: " + e.getMessage());
        }
    }

    public void sendReport(String callId, int durationSeconds) {
        BaleNotificationListener listener = BaleNotificationListener.getInstance();
        if (listener == null || !listener.hasReplyAction()) {
            uiLog("no reply action — saving report for later");
            return;
        }

        try {
            String key = settings.getBaleEncryptionKey();
            JSONObject payload = new JSONObject();
            payload.put("callId", callId);
            payload.put("durationSeconds", durationSeconds);

            String encrypted = CryptoUtils.encrypt(payload.toString(), key);
            String text = "REPORT:" + encrypted;

            listener.sendReply(text);
            uiLog("report sent for call " + callId);
        } catch (Exception e) {
            uiLog("failed to send report: " + e.getMessage());
        }
    }
}
