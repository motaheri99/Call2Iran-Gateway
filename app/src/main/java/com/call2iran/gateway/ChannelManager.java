package com.call2iran.gateway;

import android.util.Log;

public class ChannelManager {
    private static final String TAG = "ChannelManager";

    public enum ActiveChannel { BALE, PHONE_POLLING }

    private final AppSettings settings;
    private long lastBaleMessageTime = 0;

    public ChannelManager(AppSettings settings) {
        this.settings = settings;
    }

    public ActiveChannel getActiveChannel() {
        if (!isBaleConfigured()) return ActiveChannel.PHONE_POLLING;

        long elapsed = System.currentTimeMillis() - lastBaleMessageTime;
        long timeoutMs = getBaleTimeoutMinutes() * 60L * 1000L;

        if (lastBaleMessageTime > 0 && elapsed < timeoutMs) {
            return ActiveChannel.BALE;
        }

        return ActiveChannel.PHONE_POLLING;
    }

    public void onBaleMessageReceived() {
        lastBaleMessageTime = System.currentTimeMillis();
    }

    public long getLastBaleMessageTime() {
        return lastBaleMessageTime;
    }

    public int getBaleTimeoutMinutes() {
        return settings.getBaleTimeout();
    }

    public boolean isBaleConfigured() {
        String token = settings.getBaleBotToken();
        String chatId = settings.getBaleChatId();
        String key = settings.getBaleEncryptionKey();
        boolean result = token != null && !token.isEmpty()
                && chatId != null && !chatId.isEmpty()
                && key != null && key.length() == 64;
        if (!result) {
            Log.e(TAG, "isBaleConfigured=false: tokenEmpty=" + (token == null || token.isEmpty())
                    + " chatIdEmpty=" + (chatId == null || chatId.isEmpty())
                    + " keyLen=" + (key != null ? key.length() : "null"));
        }
        return result;
    }

    public String getStatusText() {
        if (!isBaleConfigured()) return "Not configured";

        ActiveChannel ch = getActiveChannel();
        if (ch == ActiveChannel.BALE) {
            long ago = (System.currentTimeMillis() - lastBaleMessageTime) / 1000;
            if (ago < 60) return "Connected (" + ago + "s ago)";
            return "Connected (" + (ago / 60) + "min ago)";
        }
        return "Disconnected — using phone polling";
    }
}
