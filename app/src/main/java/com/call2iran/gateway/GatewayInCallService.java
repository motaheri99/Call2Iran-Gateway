package com.call2iran.gateway;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.telecom.Call;
import android.telecom.InCallService;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class GatewayInCallService extends InCallService {

    private static final String TAG = "GatewayInCall";

    private static GatewayInCallService instance;
    private final CopyOnWriteArrayList<Call> activeCalls = new CopyOnWriteArrayList<>();
    private CallEventListener callEventListener;

    public interface CallEventListener {
        void onCallStateChanged(Call call, int state);
        void onCallAdded(Call call);
        void onCallRemoved(Call call);
    }

    public static GatewayInCallService getInstance() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        Log.d(TAG, "InCallService created");
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
        Log.d(TAG, "InCallService destroyed");
    }

    public void setCallEventListener(CallEventListener listener) {
        this.callEventListener = listener;
    }

    @Override
    public void onCallAdded(Call call) {
        super.onCallAdded(call);
        Log.d(TAG, "Call added: " + call.getDetails().getHandle() + " state=" + call.getState());

        if (call.getState() == Call.STATE_RINGING) {
            Log.d(TAG, "Rejecting incoming call");
            call.reject(false, null);
            return;
        }

        activeCalls.add(call);
        call.registerCallback(callCallback);

        if (callEventListener != null) {
            callEventListener.onCallAdded(call);
        }
    }

    @Override
    public void onCallRemoved(Call call) {
        super.onCallRemoved(call);
        Log.d(TAG, "Call removed: " + call.getDetails().getHandle());

        call.unregisterCallback(callCallback);
        activeCalls.remove(call);

        if (callEventListener != null) {
            callEventListener.onCallRemoved(call);
        }
    }

    private final Call.Callback callCallback = new Call.Callback() {
        @Override
        public void onStateChanged(Call call, int state) {
            Log.d(TAG, "Call state changed: " + stateToString(state));
            if (callEventListener != null) {
                callEventListener.onCallStateChanged(call, state);
            }
        }
    };

    public void holdCall(Call call) {
        if (call != null && call.getState() == Call.STATE_ACTIVE) {
            call.hold();
        }
    }

    public void unholdCall(Call call) {
        if (call != null && call.getState() == Call.STATE_HOLDING) {
            call.unhold();
        }
    }

    public void mergeConference(Call call) {
        if (call != null) {
            call.conference(getLastCall(call));
        }
    }

    public void mergeConferenceDirect(Call call1, Call call2) {
        if (call1 != null && call2 != null) {
            call1.conference(call2);
        }
    }

    public void disconnectCall(Call call) {
        if (call != null) {
            call.disconnect();
        }
    }

    public void disconnectAllCalls() {
        for (Call call : activeCalls) {
            try {
                call.disconnect();
            } catch (Exception e) {
                Log.e(TAG, "Error disconnecting call", e);
            }
        }
    }

    public void sendDtmf(Call call, char digit) {
        if (call != null) {
            call.playDtmfTone(digit);
        }
    }

    public void stopDtmf(Call call) {
        if (call != null) {
            call.stopDtmfTone();
        }
    }

    public void setMute(boolean mute) {
        setMuted(mute);
    }

    public void setSpeakerphone(boolean on) {
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            audioManager.setSpeakerphoneOn(on);
        }
    }

    public List<Call> getActiveCalls() {
        return new ArrayList<>(activeCalls);
    }

    public Call getLastCall(Call exclude) {
        for (int i = activeCalls.size() - 1; i >= 0; i--) {
            Call c = activeCalls.get(i);
            if (c != exclude) {
                return c;
            }
        }
        return null;
    }

    public static String stateToString(int state) {
        switch (state) {
            case Call.STATE_NEW: return "NEW";
            case Call.STATE_DIALING: return "DIALING";
            case Call.STATE_RINGING: return "RINGING";
            case Call.STATE_HOLDING: return "HOLDING";
            case Call.STATE_ACTIVE: return "ACTIVE";
            case Call.STATE_DISCONNECTED: return "DISCONNECTED";
            case Call.STATE_CONNECTING: return "CONNECTING";
            case Call.STATE_DISCONNECTING: return "DISCONNECTING";
            case Call.STATE_SELECT_PHONE_ACCOUNT: return "SELECT_PHONE_ACCOUNT";
            case Call.STATE_PULLING_CALL: return "PULLING_CALL";
            default: return "UNKNOWN(" + state + ")";
        }
    }
}
