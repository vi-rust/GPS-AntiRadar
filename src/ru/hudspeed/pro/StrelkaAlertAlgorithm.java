package ru.gpsantiradar.app;

public final class StrelkaAlertAlgorithm {
    public static final int SEARCH_RADIUS_METERS = 1600;
    public static final int COURSE_TOLERANCE_DEGREES = 45;

    private StrelkaAlertAlgorithm() {}

    public static int activationDistance(CameraPoint object, int fallbackMeters) {
        int configured = object.distanceMeters > 0 ? object.distanceMeters : fallbackMeters;
        return Math.max(50, Math.min(SEARCH_RADIUS_METERS, configured));
    }

    public static boolean matchesApproach(CameraPoint object, float speedKmh,
                                          double distanceMeters, float vehicleHeading,
                                          float bearingToObject) {
        if (speedKmh < 8 || distanceMeters <= 60) return true;
        if (Geo.angleDifference(vehicleHeading, bearingToObject) > COURSE_TOLERANCE_DEGREES) {
            return false;
        }
        if (object.dirType == 0) return true;
        if (Geo.angleDifference(vehicleHeading, object.direction) <= COURSE_TOLERANCE_DEGREES) {
            return true;
        }
        return object.dirType == 2
                && Geo.angleDifference(vehicleHeading, object.direction + 180f)
                <= COURSE_TOLERANCE_DEGREES;
    }

    public static int spokenDistance(int distanceMeters) {
        if (distanceMeters > 950) return 1000;
        if (distanceMeters > 850) return 900;
        if (distanceMeters > 750) return 800;
        if (distanceMeters > 650) return 700;
        if (distanceMeters > 550) return 600;
        if (distanceMeters > 450) return 500;
        if (distanceMeters > 350) return 400;
        if (distanceMeters > 250) return 300;
        if (distanceMeters > 175) return 200;
        if (distanceMeters > 125) return 150;
        if (distanceMeters > 75) return 100;
        if (distanceMeters > 45) return 50;
        if (distanceMeters > 35) return 40;
        if (distanceMeters > 25) return 30;
        if (distanceMeters > 15) return 20;
        if (distanceMeters > 5) return 10;
        return 0;
    }

    public static long beepIntervalMillis(int distanceMeters) {
        if (distanceMeters > 700) return 6000L;
        if (distanceMeters > 500) return 4500L;
        if (distanceMeters > 300) return 3000L;
        if (distanceMeters > 150) return 1800L;
        return 1000L;
    }

    public static float beepVolume(int distanceMeters) {
        return Math.max(0.05f, Math.min(1f, 1.6f - distanceMeters * 0.002f));
    }

    public static String screenSummary() {
        return "Zone, course, type, limit, distance, mode, signal";
    }
}
