package com.call2iran.gateway;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class GatewayService extends Service {

    private static final String TAG = "GatewayService";
    private static final String CHANNEL_ID = "call2iran_gateway";
    private static final int NOTIFICATION_ID = 1;
    private static final String ACTION_POLL_ALARM = "com.call2iran.gateway.POLL_ALARM";

    private static GatewayService instance;

    private AppSettings settings;
    private PollManager pollManager;
    private CallBridgeManager callBridgeManager;
    private BaleClient baleClient;
    private ChannelManager channelManager;
    private Handler handler;
    private PowerManager.WakeLock wakeLock;
    private GatewayState currentState = GatewayState.IDLE;

    private String currentCallId;
    private String lastPollTime = "-";
    private String lastJobInfo = "-";
    private String lastCallDuration = "-";
    private String activeChannel = "-";
    private final StringBuilder errorLog = new StringBuilder();
    private int errorCount = 0;

    private StatusUpdateListener statusListener;
    private Runnable pollTimerRunnable;

    public interface StatusUpdateListener {
        void onStatusUpdate(GatewayState state, String pollTime, String jobInfo,
                            String duration, String errors, String channel);
    }

    public static GatewayService getInstance() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        settings = new AppSettings(this);
        handler = new Handler(Looper.getMainLooper());
        pollManager = new PollManager(this, settings);
        callBridgeManager = new CallBridgeManager(this, settings);
        channelManager = new ChannelManager(settings);
        baleClient = new BaleClient(settings);

        createNotificationChannel();

        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CallIran::GatewayWakeLock");

        Log.d(TAG, "GatewayService created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "GatewayService onStartCommand");

        startForeground(NOTIFICATION_ID, buildNotification());
        settings.setServiceRunning(true);

        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire();
        }

        // Start Bale client if configured
        String bToken = settings.getBaleBotToken();
        String bChat = settings.getBaleChatId();
        String bKey = settings.getBaleEncryptionKey();
        logError("Bale check: token=" + (bToken != null && !bToken.isEmpty() ? bToken.length() + " chars" : "EMPTY")
                + " chatId=" + (bChat != null && !bChat.isEmpty() ? bChat : "EMPTY")
                + " keyLen=" + (bKey != null ? bKey.length() : 0) + "/64");

        if (channelManager.isBaleConfigured()) {
            if (!BaleNotificationListener.isEnabled(this)) {
                logError("Bale configured but Notification Access not granted — falling back to Phone");
                activeChannel = "Phone";
            } else {
                logError("Bale configured — listening via notifications");
                channelManager.markStarted();
                activeChannel = "Bale";
                setupBaleClient();
            }
        } else {
            String reason = "";
            if (bToken == null || bToken.isEmpty()) reason += "token empty; ";
            if (bChat == null || bChat.isEmpty()) reason += "chatId empty; ";
            if (bKey == null) reason += "key null; ";
            else if (bKey.length() != 64) reason += "key length is " + bKey.length() + " (need 64); ";
            logError("Bale NOT configured: " + reason + "falling back to Phone");
            activeChannel = "Phone";
        }

        if (intent != null && ACTION_POLL_ALARM.equals(intent.getAction())) {
            Log.d(TAG, "Poll alarm triggered");
            executePoll();
        } else {
            schedulePoll();
        }

        notifyStatusUpdate();
        return START_STICKY;
    }

    private void setupBaleClient() {
        baleClient.setLogListener(this::logError);
        baleClient.setJobListener((callId, targetPhone, callerPhone, maxMinutes) -> {
            Log.d(TAG, "Bale job received: " + callId);
            channelManager.onBaleMessageReceived();
            activeChannel = "Bale";

            if (currentState != GatewayState.IDLE) {
                logError("Bale job ignored — not idle (state=" + currentState + ")");
                return;
            }

            lastJobInfo = targetPhone + " <-> " + callerPhone + " (" + maxMinutes + "min)";
            currentCallId = callId;

            // Strip +98 prefix and leading zero for Iran number format
            String iranNum = targetPhone;
            if (iranNum.startsWith("+98")) iranNum = "0" + iranNum.substring(3);

            // Strip + prefix for international number
            String intlNum = callerPhone;
            if (intlNum.startsWith("+")) intlNum = intlNum.substring(1);

            JobData job = new JobData(iranNum, intlNum, maxMinutes);

            if (settings.isTestMode()) {
                Log.d(TAG, "Test mode: auto-reporting fake 120s call for " + callId);
                logError("[TEST] Bale job: " + job + " (callId: " + callId + ")");
                handler.postDelayed(() -> {
                    baleClient.sendReport(callId, 120);
                    logError("[TEST] Fake report sent: 120s");
                }, 10000);
                return;
            }

            startBridge(job);
        });

        baleClient.setOnMessageCallback(() -> {
            channelManager.onBaleMessageReceived();
            activeChannel = "Bale";
            notifyStatusUpdate();
        });

        baleClient.startPolling();
        Log.d(TAG, "Bale client started");
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "GatewayService destroyed");

        cancelPollTimer();
        cancelAlarm();

        if (pollManager.isPolling()) {
            pollManager.cancel();
        }

        baleClient.stopPolling();

        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }

        settings.setServiceRunning(false);
        instance = null;

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public void setStatusListener(StatusUpdateListener listener) {
        this.statusListener = listener;
        notifyStatusUpdate();
    }

    private void schedulePoll() {
        cancelPollTimer();

        int intervalSeconds = settings.getPollInterval();
        long intervalMs = intervalSeconds * 1000L;

        pollTimerRunnable = this::executePoll;
        handler.postDelayed(pollTimerRunnable, intervalMs);

        scheduleAlarmBackup(intervalMs);

        Log.d(TAG, "Next poll in " + intervalSeconds + " seconds");
    }

    private void scheduleAlarmBackup(long delayMs) {
        AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(this, GatewayService.class);
        intent.setAction(ACTION_POLL_ALARM);
        PendingIntent pendingIntent = PendingIntent.getService(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delayMs + 5000,
                pendingIntent
        );
    }

    private void cancelAlarm() {
        AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(this, GatewayService.class);
        intent.setAction(ACTION_POLL_ALARM);
        PendingIntent pendingIntent = PendingIntent.getService(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent);
        }
    }

    private void cancelPollTimer() {
        if (pollTimerRunnable != null) {
            handler.removeCallbacks(pollTimerRunnable);
            pollTimerRunnable = null;
        }
    }

    private void executePoll() {
        if (currentState != GatewayState.IDLE) {
            Log.d(TAG, "Not idle (state=" + currentState + "), skipping poll");
            schedulePoll();
            return;
        }

        // If Bale is active, skip phone polling
        if (channelManager.getActiveChannel() == ChannelManager.ActiveChannel.BALE) {
            Log.d(TAG, "Bale is active, skipping phone poll");
            activeChannel = "Bale";
            notifyStatusUpdate();
            schedulePoll();
            return;
        }

        activeChannel = "Phone";
        setState(GatewayState.POLLING);

        lastPollTime = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        notifyStatusUpdate();

        pollManager.startPoll((job, error) -> {
            handler.post(() -> {
                if (error != null) {
                    logError("Poll error: " + error);
                }

                if (job != null) {
                    lastJobInfo = job.toString();
                    currentCallId = null;
                    Log.d(TAG, "Job received: " + job);
                    notifyStatusUpdate();
                    startBridge(job);
                } else {
                    setState(GatewayState.IDLE);
                    notifyStatusUpdate();
                    schedulePoll();
                }
            });
        });
    }

    private void startBridge(JobData job) {
        callBridgeManager.startBridge(job, new CallBridgeManager.BridgeCallback() {
            @Override
            public void onBridgeStateChanged(GatewayState state) {
                handler.post(() -> {
                    setState(state);
                    notifyStatusUpdate();
                });
            }

            @Override
            public void onBridgeComplete(int durationSeconds, String error) {
                handler.post(() -> {
                    if (error != null) {
                        logError("Bridge error: " + error);
                    }

                    lastCallDuration = durationSeconds + "s";
                    Log.d(TAG, "Bridge complete. Duration: " + durationSeconds + "s");

                    // Report via Bale if connected, else save for phone polling
                    if (currentCallId != null && baleClient.isConnected()) {
                        baleClient.sendReport(currentCallId, durationSeconds);
                        Log.d(TAG, "Report sent via Bale for " + currentCallId);
                    } else {
                        settings.savePendingReport(currentCallId, durationSeconds);
                    }

                    currentCallId = null;
                    setState(GatewayState.IDLE);
                    notifyStatusUpdate();
                    schedulePoll();
                });
            }
        });
    }

    private void setState(GatewayState state) {
        currentState = state;
        updateNotification();
    }

    public void logError(String error) {
        Log.e(TAG, error);
        errorCount++;
        String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        String entry = "[" + timestamp + "] " + error + "\n";
        errorLog.insert(0, entry);

        String[] lines = errorLog.toString().split("\n");
        if (lines.length > 20) {
            errorLog.setLength(0);
            for (int i = 0; i < 20; i++) {
                errorLog.append(lines[i]).append("\n");
            }
        }

        notifyStatusUpdate();
    }

    private void notifyStatusUpdate() {
        if (statusListener != null) {
            statusListener.onStatusUpdate(
                    currentState,
                    lastPollTime,
                    lastJobInfo,
                    lastCallDuration,
                    errorLog.toString(),
                    activeChannel
            );
        }
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Call Iran Gateway",
                NotificationManager.IMPORTANCE_HIGH
        );
        channel.setDescription("Gateway service status");
        channel.setShowBadge(false);

        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);

        String subtitle = "State: " + currentState.getLabel() + " | " + activeChannel;

        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Call Iran Gateway")
                .setContentText(subtitle)
                .setSmallIcon(android.R.drawable.stat_sys_phone_call)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void updateNotification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.notify(NOTIFICATION_ID, buildNotification());
    }

    public GatewayState getCurrentState() {
        return currentState;
    }

    public ChannelManager getChannelManager() {
        return channelManager;
    }

    public String getErrorLog() {
        return errorLog.toString();
    }
}
