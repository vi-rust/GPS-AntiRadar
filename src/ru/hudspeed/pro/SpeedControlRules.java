package ru.gpsantiradar.app;

import java.util.Calendar;
import java.util.Locale;

/** Encodes conditional RadarBase speed controls without adding a second SQL table. */
public final class SpeedControlRules {
    public static final int CAR = 1;
    public static final int BIKE = 2;
    public static final int TRUCK = 4;
    public static final int BUS = 8;

    private SpeedControlRules() {}

    public static String encode(int speed, boolean average, int fromMinute, int toMinute,
                                int weekdayMask, int monthMask, int vehicleMask) {
        return speed + "," + (average ? 1 : 0) + "," + fromMinute + "," + toMinute
                + "," + weekdayMask + "," + monthMask + "," + vehicleMask;
    }

    public static int currentCarLimit(String encoded, long timeMillis) {
        if (encoded == null || encoded.isEmpty()) return 0;
        Calendar now = Calendar.getInstance();
        now.setTimeInMillis(timeMillis);
        int result = select(encoded, now, false);
        return result > 0 ? result : select(encoded, now, true);
    }

    private static int select(String encoded, Calendar now, boolean average) {
        int result = 0;
        for (String item : encoded.split(";")) {
            String[] fields = item.split(",", -1);
            if (fields.length != 7) continue;
            try {
                int speed = Integer.parseInt(fields[0]);
                boolean ruleAverage = "1".equals(fields[1]);
                int from = Integer.parseInt(fields[2]);
                int to = Integer.parseInt(fields[3]);
                int weekdays = Integer.parseInt(fields[4]);
                int months = Integer.parseInt(fields[5]);
                int vehicles = Integer.parseInt(fields[6]);
                if (speed <= 0 || ruleAverage != average) continue;
                if (vehicles != 0 && (vehicles & CAR) == 0) continue;
                if (!matchesDate(now, from, to, weekdays, months)) continue;
                if (result == 0 || speed < result) result = speed;
            } catch (NumberFormatException ignored) {
                // Ignore one malformed rule but keep the object usable.
            }
        }
        return result;
    }

    private static boolean matchesDate(Calendar now, int from, int to,
                                       int weekdays, int months) {
        int monthBit = 1 << now.get(Calendar.MONTH);
        if (months != 0 && (months & monthBit) == 0) return false;
        int mondayFirstDay = (now.get(Calendar.DAY_OF_WEEK) + 5) % 7;
        if (weekdays != 0 && (weekdays & (1 << mondayFirstDay)) == 0) return false;
        if (from < 0 || to < 0 || from == to) return true;
        int minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        return from < to ? minute >= from && minute < to : minute >= from || minute < to;
    }

    public static int vehicleMask(String vehicle) {
        if (vehicle == null) return 0;
        switch (vehicle.toLowerCase(Locale.US)) {
            case "car": return CAR;
            case "bike": return BIKE;
            case "truck": return TRUCK;
            case "bus": return BUS;
            default: return 16;
        }
    }
}
