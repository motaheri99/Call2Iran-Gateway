package com.call2iran.gateway;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;

        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.d(TAG, "Boot completed");

            AppSettings settings = new AppSettings(context);
            if (settings.isServiceRunning()) {
                Log.d(TAG, "Service was running before reboot, restarting");
                Intent serviceIntent = new Intent(context, GatewayService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
            } else {
                Log.d(TAG, "Service was not running before reboot, not starting");
            }
        }
    }
}
