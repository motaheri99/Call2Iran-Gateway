package com.call2iran.gateway;

public class JobData {
    private final String iranNumber;
    private final String intlNumber;
    private final int maxMinutes;

    public JobData(String iranNumber, String intlNumber, int maxMinutes) {
        this.iranNumber = iranNumber;
        this.intlNumber = intlNumber;
        this.maxMinutes = maxMinutes;
    }

    public String getIranNumber() {
        return iranNumber;
    }

    public String getIntlNumber() {
        return intlNumber;
    }

    public int getMaxMinutes() {
        return maxMinutes;
    }

    public String getIntlDialNumber() {
        return "00" + intlNumber;
    }

    @Override
    public String toString() {
        return iranNumber + " <-> " + intlNumber + " (" + maxMinutes + "min)";
    }

    public static JobData parse(String dtmfString) {
        if (dtmfString == null || dtmfString.isEmpty()) {
            return null;
        }

        String data = dtmfString;
        if (data.endsWith("#")) {
            data = data.substring(0, data.length() - 1);
        }

        if (data.equals("0")) {
            return null;
        }

        String[] parts = data.split("\\*");
        if (parts.length != 3) {
            return null;
        }

        try {
            String iranNumber = parts[0];
            String intlNumber = parts[1];
            int maxMinutes = Integer.parseInt(parts[2]);

            if (iranNumber.isEmpty() || intlNumber.isEmpty() || maxMinutes <= 0) {
                return null;
            }

            return new JobData(iranNumber, intlNumber, maxMinutes);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
