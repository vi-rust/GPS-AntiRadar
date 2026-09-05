package ru.gpsantiradar.app;

import java.util.Calendar;
import java.util.TimeZone;

public final class ThemeResolver {
    private static final double SUNRISE_ALTITUDE_DEGREES = -0.833;

    private ThemeResolver() {}

    public static boolean isDark(ThemeMode mode, long epochMillis,
                                 double latitude, double longitude,
                                 TimeZone fallbackTimeZone) {
        ThemeMode resolvedMode = mode == null ? ThemeMode.AUTOMATIC : mode;
        if (resolvedMode == ThemeMode.LIGHT) return false;
        if (resolvedMode == ThemeMode.DARK) return true;
        if (validCoordinates(latitude, longitude)) {
            return solarAltitudeDegrees(epochMillis, latitude, longitude)
                    < SUNRISE_ALTITUDE_DEGREES;
        }
        Calendar local = Calendar.getInstance(
                fallbackTimeZone == null ? TimeZone.getDefault() : fallbackTimeZone);
        local.setTimeInMillis(epochMillis);
        int hour = local.get(Calendar.HOUR_OF_DAY);
        return hour >= 20 || hour < 7;
    }

    static double solarAltitudeDegrees(long epochMillis,
                                       double latitude, double longitude) {
        double julianDay = epochMillis / 86_400_000.0 + 2_440_587.5;
        double daysSinceJ2000 = julianDay - 2_451_545.0;
        double meanLongitude = normalizeDegrees(280.460
                + 0.9856474 * daysSinceJ2000);
        double meanAnomaly = normalizeDegrees(357.528
                + 0.9856003 * daysSinceJ2000);
        double eclipticLongitude = Math.toRadians(normalizeDegrees(meanLongitude
                + 1.915 * Math.sin(Math.toRadians(meanAnomaly))
                + 0.020 * Math.sin(Math.toRadians(2.0 * meanAnomaly))));
        double obliquity = Math.toRadians(23.439
                - 0.0000004 * daysSinceJ2000);
        double rightAscension = Math.atan2(
                Math.cos(obliquity) * Math.sin(eclipticLongitude),
                Math.cos(eclipticLongitude));
        double declination = Math.asin(
                Math.sin(obliquity) * Math.sin(eclipticLongitude));
        double siderealDegrees = normalizeDegrees(280.46061837
                + 360.98564736629 * daysSinceJ2000 + longitude);
        double hourAngle = Math.toRadians(normalizeSignedDegrees(
                siderealDegrees - Math.toDegrees(rightAscension)));
        double latitudeRadians = Math.toRadians(latitude);
        double altitude = Math.asin(
                Math.sin(latitudeRadians) * Math.sin(declination)
                        + Math.cos(latitudeRadians) * Math.cos(declination)
                        * Math.cos(hourAngle));
        return Math.toDegrees(altitude);
    }

    private static boolean validCoordinates(double latitude, double longitude) {
        return !Double.isNaN(latitude) && !Double.isInfinite(latitude)
                && !Double.isNaN(longitude) && !Double.isInfinite(longitude)
                && latitude >= -90.0 && latitude <= 90.0
                && longitude >= -180.0 && longitude <= 180.0;
    }

    private static double normalizeDegrees(double value) {
        double normalized = value % 360.0;
        return normalized < 0.0 ? normalized + 360.0 : normalized;
    }

    private static double normalizeSignedDegrees(double value) {
        double normalized = normalizeDegrees(value);
        return normalized > 180.0 ? normalized - 360.0 : normalized;
    }
}
