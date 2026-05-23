package com.call2iran.gateway;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class BaleClient {

    private static final String TAG = "BaleClient";
    private static final String BASE_URL = "https://tapi.bale.ai/bot";
    private static final int CONNECT_TIMEOUT = 10000;
    private static final int POLL_READ_TIMEOUT = 35000;
    private static final int SEND_READ_TIMEOUT = 10000;
    private static final long RETRY_DELAY_MS = 10000;

    public interface OnJobReceivedListener {
        void onJobReceived(String callId, String targetPhone, String callerPhone, int maxMinutes);
    }

    private final AppSettings settings;
    private final Handler mainHandler;
    private final ExecutorService executor;
    private final AtomicBoolean polling = new AtomicBoolean(false);
    private long lastUpdateId = 0;
    private long lastMessageTime = 0;
    private boolean connected = false;

    public interface OnLogListener {
        void onLog(String message);
    }

    private OnJobReceivedListener jobListener;
    private Runnable onMessageCallback;
    private OnLogListener logListener;

    public BaleClient(AppSettings settings) {
        this.settings = settings;
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.executor = Executors.newSingleThreadExecutor();
    }

    public void setJobListener(OnJobReceivedListener listener) {
        this.jobListener = listener;
    }

    public void setOnMessageCallback(Runnable callback) {
        this.onMessageCallback = callback;
    }

    public void setLogListener(OnLogListener listener) {
        this.logListener = listener;
    }

    private void uiLog(String msg) {
        Log.e(TAG, msg);
        if (logListener != null) {
            mainHandler.post(() -> logListener.onLog("Bale: " + msg));
        }
    }

    public void startPolling() {
        uiLog("startPolling called");
        if (polling.getAndSet(true)) {
            uiLog("already polling, skipping");
            return;
        }
        executor.submit(this::pollLoop);
    }

    public void stopPolling() {
        Log.e(TAG, "Stopping Bale polling");
        polling.set(false);
        executor.shutdownNow();
    }

    public boolean isConnected() {
        return connected;
    }

    public long getLastMessageTime() {
        return lastMessageTime;
    }

    public void sendReport(String callId, int durationSeconds) {
        executor.submit(() -> {
            try {
                String key = settings.getBaleEncryptionKey();
                JSONObject payload = new JSONObject();
                payload.put("callId", callId);
                payload.put("durationSeconds", durationSeconds);

                String encrypted = CryptoUtils.encrypt(payload.toString(), key);
                String text = "REPORT:" + encrypted;

                sendMessage(text);
                uiLog("report sent for call " + callId);
            } catch (Exception e) {
                uiLog("failed to send report: " + e.getMessage());
            }
        });
    }

    private void pollLoop() {
        uiLog("polling started");
        boolean firstConnect = true;

        while (polling.get()) {
            try {
                String token = settings.getBaleBotToken();
                if (token == null || token.isEmpty()) {
                    uiLog("token empty in poll loop, waiting...");
                    Thread.sleep(RETRY_DELAY_MS);
                    continue;
                }

                String urlStr = BASE_URL + token + "/getUpdates?offset=" + (lastUpdateId + 1) + "&timeout=30";
                HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
                conn.setConnectTimeout(CONNECT_TIMEOUT);
                conn.setReadTimeout(POLL_READ_TIMEOUT);
                conn.setRequestMethod("GET");

                int code = conn.getResponseCode();
                if (code == 200) {
                    String body = readResponse(conn);
                    connected = true;
                    if (firstConnect) {
                        uiLog("connected to server (HTTP 200)");
                        firstConnect = false;
                    }
                    JSONObject json = new JSONObject(body);

                    if (json.optBoolean("ok")) {
                        JSONArray results = json.optJSONArray("result");
                        if (results != null && results.length() > 0) {
                            for (int i = 0; i < results.length(); i++) {
                                JSONObject update = results.getJSONObject(i);
                                lastUpdateId = update.getLong("update_id");
                                processUpdate(update);
                            }
                        }
                    }
                } else {
                    uiLog("getUpdates HTTP " + code);
                    connected = false;
                    firstConnect = true;
                    Thread.sleep(RETRY_DELAY_MS);
                }

                conn.disconnect();
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                connected = false;
                firstConnect = true;
                uiLog("poll error: " + e.getMessage());
                try { Thread.sleep(RETRY_DELAY_MS); } catch (InterruptedException ie) { break; }
            }
        }

        uiLog("polling stopped");
    }

    private void processUpdate(JSONObject update) {
        try {
            JSONObject message = update.optJSONObject("message");
            if (message == null) return;

            String text = message.optString("text", "");
            if (text.isEmpty()) return;

            long chatId = message.optJSONObject("chat") != null
                    ? message.getJSONObject("chat").optLong("id", 0) : 0;

            String expectedChatId = settings.getBaleChatId();
            if (expectedChatId == null || expectedChatId.isEmpty()) return;
            if (chatId != Long.parseLong(expectedChatId)) return;

            lastMessageTime = System.currentTimeMillis();

            if (onMessageCallback != null) {
                mainHandler.post(onMessageCallback);
            }

            if (text.startsWith("JOB:")) {
                uiLog("got JOB message");
                handleJob(text.substring(4));
            } else if (text.startsWith("PING:")) {
                uiLog("got PING");
            } else {
                uiLog("got message: " + text.substring(0, Math.min(text.length(), 30)));
            }
        } catch (Exception e) {
            uiLog("error processing update: " + e.getMessage());
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

    private void sendMessage(String text) throws Exception {
        String token = settings.getBaleBotToken();
        String chatId = settings.getBaleChatId();

        JSONObject body = new JSONObject();
        body.put("chat_id", Long.parseLong(chatId));
        body.put("text", text);

        String urlStr = BASE_URL + token + "/sendMessage";
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(SEND_READ_TIMEOUT);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);

        OutputStream os = conn.getOutputStream();
        os.write(body.toString().getBytes("UTF-8"));
        os.close();

        int code = conn.getResponseCode();
        if (code != 200) {
            String resp = readResponse(conn);
            throw new Exception("sendMessage HTTP " + code + ": " + resp);
        }

        conn.disconnect();
    }

    private String readResponse(HttpURLConnection conn) throws Exception {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        reader.close();
        return sb.toString();
    }
}
