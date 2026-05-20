package com.call2iran.gateway;

import android.content.Context;
import android.content.SharedPreferences;

public class AppSettings {

    private static final String PREFS_NAME = "call2iran_prefs";
    private static final String KEY_TELNYX_NUMBER = "telnyx_number";
    private static final String KEY_POLL_INTERVAL = "poll_interval";
    private static final String KEY_PHONE_ID = "phone_id";
    private static final String KEY_VOICE_FILE_PATH = "voice_file_path";
    private static final String KEY_SERVICE_RUNNING = "service_running";
    private static final String KEY_TEST_MODE = "test_mode";
    private static final String KEY_PENDING_DURATION = "pending_duration";
    private static final String KEY_HAS_PENDING_REPORT = "has_pending_report";

    private static final int DEFAULT_POLL_INTERVAL = 30;
    private static final String DEFAULT_PHONE_ID = "phone1";

    private final SharedPreferences prefs;

    public AppSettings(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public String getTelnyxNumber() {
        return prefs.getString(KEY_TELNYX_NUMBER, "");
    }

    public void setTelnyxNumber(String number) {
        prefs.edit().putString(KEY_TELNYX_NUMBER, number).apply();
    }

    public int getPollInterval() {
        return prefs.getInt(KEY_POLL_INTERVAL, DEFAULT_POLL_INTERVAL);
    }

    public void setPollInterval(int seconds) {
        prefs.edit().putInt(KEY_POLL_INTERVAL, seconds).apply();
    }

    public String getPhoneId() {
        return prefs.getString(KEY_PHONE_ID, DEFAULT_PHONE_ID);
    }

    public void setPhoneId(String id) {
        prefs.edit().putString(KEY_PHONE_ID, id).apply();
    }

    public String getVoiceFilePath() {
        return prefs.getString(KEY_VOICE_FILE_PATH, "");
    }

    public void setVoiceFilePath(String path) {
        prefs.edit().putString(KEY_VOICE_FILE_PATH, path).apply();
    }

    public boolean isServiceRunning() {
        return prefs.getBoolean(KEY_SERVICE_RUNNING, false);
    }

    public void setServiceRunning(boolean running) {
        prefs.edit().putBoolean(KEY_SERVICE_RUNNING, running).apply();
    }

    public boolean isTestMode() {
        return prefs.getBoolean(KEY_TEST_MODE, false);
    }

    public void setTestMode(boolean testMode) {
        prefs.edit().putBoolean(KEY_TEST_MODE, testMode).apply();
    }

    public void savePendingDuration(int durationSeconds) {
        prefs.edit()
                .putInt(KEY_PENDING_DURATION, durationSeconds)
                .putBoolean(KEY_HAS_PENDING_REPORT, true)
                .apply();
    }

    public boolean hasPendingReport() {
        return prefs.getBoolean(KEY_HAS_PENDING_REPORT, false);
    }

    public int getPendingDuration() {
        return prefs.getInt(KEY_PENDING_DURATION, 0);
    }

    public void clearPendingReport() {
        prefs.edit()
                .putBoolean(KEY_HAS_PENDING_REPORT, false)
                .putInt(KEY_PENDING_DURATION, 0)
                .apply();
    }
}
