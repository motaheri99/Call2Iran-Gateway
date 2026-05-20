package com.call2iran.gateway;

import android.os.Handler;
import android.os.Looper;
import android.telecom.Call;

public class DTMFSender {

    private static final long TONE_DURATION_MS = 100;
    private static final long TONE_GAP_MS = 100;

    public interface SendCompleteListener {
        void onSendComplete();
    }

    private final Handler handler;

    public DTMFSender() {
        handler = new Handler(Looper.getMainLooper());
    }

    public void sendDtmfString(Call call, String digits, SendCompleteListener listener) {
        if (call == null || digits == null || digits.isEmpty()) {
            if (listener != null) {
                listener.onSendComplete();
            }
            return;
        }

        sendDigitAtIndex(call, digits, 0, listener);
    }

    private void sendDigitAtIndex(Call call, String digits, int index, SendCompleteListener listener) {
        if (index >= digits.length()) {
            if (listener != null) {
                handler.post(listener::onSendComplete);
            }
            return;
        }

        char digit = digits.charAt(index);
        call.playDtmfTone(digit);

        handler.postDelayed(() -> {
            call.stopDtmfTone();

            handler.postDelayed(() -> sendDigitAtIndex(call, digits, index + 1, listener), TONE_GAP_MS);
        }, TONE_DURATION_MS);
    }
}
