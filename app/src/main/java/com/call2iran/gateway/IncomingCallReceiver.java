package com.call2iran.gateway;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.telephony.TelephonyManager;
import android.util.Log;

public class IncomingCallReceiver extends BroadcastReceiver {

    private static final String TAG = "IncomingCallReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;

        if (TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(intent.getAction())) {
            String state = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
            if (TelephonyManager.EXTRA_STATE_RINGING.equals(state)) {
                String number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);
                Log.d(TAG, "Incoming call from: " + number + " — will be rejected by InCallService");
            }
        }
    }
}
