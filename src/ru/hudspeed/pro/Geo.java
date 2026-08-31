package ru.gpsantiradar.app;

public final class Geo {
    private static final double EARTH_RADIUS_M = 6371000.0;

    private Geo() {}

    public static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dp = Math.toRadians(lat2 - lat1);
        double dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return EARTH_RADIUS_M * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    public static float bearing(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dl = Math.toRadians(lon2 - lon1);
        double y = Math.sin(dl) * Math.cos(p2);
        double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        return normalize((float) Math.toDegrees(Math.atan2(y, x)));
    }

    public static float angleDifference(float a, float b) {
        float d = Math.abs(normalize(a) - normalize(b));
        return d > 180f ? 360f - d : d;
    }

    public static float normalize(float angle) {
        float result = angle % 360f;
        return result < 0 ? result + 360f : result;
    }
}
