package com.call2iran.gateway;

public class JobData {
    private final String iranNumber;
    private final String intlNumber;
    private final int maxMinutes;
    private final int delayMinutes;

    public JobData(String iranNumber, String intlNumber, int maxMinutes, int delayMinutes) {
        this.iranNumber = iranNumber;
        this.intlNumber = intlNumber;
        this.maxMinutes = maxMinutes;
        this.delayMinutes = delayMinutes;
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

    public int getDelayMinutes() {
        return delayMinutes;
    }

    public String getIntlDialNumber() {
        return "00" + intlNumber;
    }

    @Override
    public String toString() {
        return iranNumber + " <-> " + intlNumber + " (" + maxMinutes + "min, delay=" + delayMinutes + "min)";
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
        if (parts.length != 4) {
            return null;
        }

        try {
            String iranNumber = parts[0];
            String intlNumber = parts[1];
            int maxMinutes = Integer.parseInt(parts[2]);
            int delayMinutes = Integer.parseInt(parts[3]);

            if (iranNumber.isEmpty() || intlNumber.isEmpty() || maxMinutes <= 0 || delayMinutes < 0) {
                return null;
            }
            if (delayMinutes > 1440) {
                return null;
            }

            return new JobData(iranNumber, intlNumber, maxMinutes, delayMinutes);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
