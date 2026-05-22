package com.call2iran.gateway;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
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
    private Handler handler;
    private PowerManager.WakeLock wakeLock;
    private GatewayState currentState = GatewayState.IDLE;

    private String lastPollTime = "-";
    private String lastJobInfo = "-";
    private String lastCallDuration = "-";
    private final StringBuilder errorLog = new StringBuilder();
    private int errorCount = 0;

    private StatusUpdateListener statusListener;
    private Runnable pollTimerRunnable;

    public interface StatusUpdateListener {
        void onStatusUpdate(GatewayState state, String pollTime, String jobInfo,
                            String duration, String errors);
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

        if (intent != null && ACTION_POLL_ALARM.equals(intent.getAction())) {
            Log.d(TAG, "Poll alarm triggered");
            executePoll();
        } else {
            schedulePoll();
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "GatewayService destroyed");

        cancelPollTimer();
        cancelAlarm();

        if (pollManager.isPolling()) {
            pollManager.cancel();
        }

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
                PendingIntent.FLAG_UPDATE_CURRENT);

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
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_NO_CREATE);
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

                    settings.savePendingDuration(durationSeconds);

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

    private void logError(String error) {
        Log.e(TAG, error);
        errorCount++;
        String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        String entry = "[" + timestamp + "] " + error + "\n";
        errorLog.insert(0, entry);

        String[] lines = errorLog.toString().split("\n");
        if (lines.length > 10) {
            errorLog.setLength(0);
            for (int i = 0; i < 10; i++) {
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
                    errorLog.toString()
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
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, 0);

        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Call Iran Gateway")
                .setContentText("State: " + currentState.getLabel())
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

    public String getErrorLog() {
        return errorLog.toString();
    }
}
