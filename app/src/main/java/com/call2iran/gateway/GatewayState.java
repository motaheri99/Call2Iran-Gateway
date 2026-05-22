package com.call2iran.gateway;

public enum GatewayState {
    IDLE("IDLE"),
    POLLING("POLLING"),
    CALLING_IRAN("CALLING_IRAN"),
    WAITING_CONFIRMATION("WAITING_CONFIRMATION"),
    CALLING_INTL("CALLING_INTL"),
    BRIDGED("BRIDGED"),
    WAITING_SCHEDULE("WAITING_SCHEDULE"),
    CALL_ENDED("CALL_ENDED");

    private final String label;

    GatewayState(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
