package com.call2iran.gateway;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

public class BaleNotificationListener extends NotificationListenerService {

    private static final String TAG = "BaleNotifListener";
    private static final String BALE_PKG_1 = "ir.nasim.bale";
    private static final String BALE_PKG_2 = "com.bale.messenger";

    private static BaleNotificationListener instance;
    private static final List<OnBaleMessageListener> listeners = new ArrayList<>();
    private static OnLogListener logListener;

    private PendingIntent lastReplyIntent;
    private RemoteInput lastRemoteInput;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface OnBaleMessageListener {
        void onBaleMessage(String text);
    }

    public interface OnLogListener {
        void onLog(String msg);
    }

    public static BaleNotificationListener getInstance() {
        return instance;
    }

    public static void addMessageListener(OnBaleMessageListener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    public static void removeMessageListener(OnBaleMessageListener l) {
        listeners.remove(l);
    }

    public static void setLogListener(OnLogListener l) {
        logListener = l;
    }

    public static boolean isEnabled(Context ctx) {
        String flat = Settings.Secure.getString(ctx.getContentResolver(),
                "enabled_notification_listeners");
        if (flat == null) return false;
        ComponentName me = new ComponentName(ctx, BaleNotificationListener.class);
        return flat.contains(me.flattenToString());
    }

    private void uiLog(String msg) {
        Log.d(TAG, msg);
        if (logListener != null) {
            mainHandler.post(() -> logListener.onLog("NotifListener: " + msg));
        }
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        instance = this;
        uiLog("connected");
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        instance = null;
        uiLog("disconnected");
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        String pkg = sbn.getPackageName();

        Notification notification = sbn.getNotification();
        if (notification == null) return;

        Bundle extras = notification.extras;
        String text = "";
        if (extras != null) {
            CharSequence t = extras.getCharSequence(Notification.EXTRA_TEXT);
            if (t != null) text = t.toString();
            if (text.isEmpty()) {
                t = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
                if (t != null) text = t.toString();
            }
        }

        String preview = text.isEmpty() ? "(empty)" : text.substring(0, Math.min(text.length(), 30));
        uiLog("notif from: " + pkg + " text: " + preview);

        if (!BALE_PKG_1.equals(pkg) && !BALE_PKG_2.equals(pkg)) return;
        if (text.isEmpty()) return;

        uiLog("BALE notif: " + text.substring(0, Math.min(text.length(), 60)));

        captureReplyAction(notification);

        String messageText = text;
        mainHandler.post(() -> {
            for (OnBaleMessageListener l : new ArrayList<>(listeners)) {
                l.onBaleMessage(messageText);
            }
        });
    }

    private void captureReplyAction(Notification notification) {
        if (notification.actions == null) return;
        for (Notification.Action action : notification.actions) {
            if (action.getRemoteInputs() != null && action.getRemoteInputs().length > 0) {
                lastReplyIntent = action.actionIntent;
                lastRemoteInput = action.getRemoteInputs()[0];
                uiLog("reply action captured");
                return;
            }
        }
    }

    public boolean hasReplyAction() {
        return lastReplyIntent != null && lastRemoteInput != null;
    }

    public void sendReply(String text) {
        if (!hasReplyAction()) {
            uiLog("no reply action available");
            return;
        }

        try {
            Intent intent = new Intent();
            Bundle bundle = new Bundle();
            bundle.putCharSequence(lastRemoteInput.getResultKey(), text);
            RemoteInput.addResultsToIntent(new RemoteInput[]{lastRemoteInput}, intent, bundle);
            lastReplyIntent.send(this, 0, intent);
            uiLog("reply sent: " + text.substring(0, Math.min(text.length(), 40)));
        } catch (Exception e) {
            uiLog("reply failed: " + e.getMessage());
        }
    }
}
