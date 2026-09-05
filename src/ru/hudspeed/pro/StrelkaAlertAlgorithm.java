package ru.gpsantiradar.app;

public final class StrelkaAlertAlgorithm {
    public static final int SEARCH_RADIUS_METERS = 1600;
    public static final int ACQUIRE_DIRECTION_TOLERANCE_DEGREES = 25;
    public static final int RETAIN_DIRECTION_TOLERANCE_DEGREES = 45;
    public static final int OVERSPEED_THRESHOLD_KMH =
            AppSettings.DEFAULT_OVERSPEED_THRESHOLD_KMH;
    public static final float MAX_APPROACH_CONFIDENCE = 100f;

    private static final double ZONE_MARGIN = 1.1d;
    private static final float CONFIDENCE_DECAY_FACTOR = 0.5f;

    private StrelkaAlertAlgorithm() {}

    public static int activationDistance(CameraPoint object) {
        if (object == null || object.distanceMeters <= 0) return 0;
        return Math.max(50, Math.min(SEARCH_RADIUS_METERS, object.distanceMeters));
    }

    public static boolean matchesZone(CameraPoint object, double distanceMeters,
                                      float vehicleHeading, float bearingToObject) {
        int forwardDistance = activationDistance(object);
        if (forwardDistance == 0) return false;
        if (object.dirType == 0) {
            return distanceMeters < forwardDistance * ZONE_MARGIN;
        }
        if (!matchesDirection(object, vehicleHeading,
                ACQUIRE_DIRECTION_TOLERANCE_DEGREES)) {
            return false;
        }

        int reverseDistance = object.reverseDistanceMeters > 0
                ? object.reverseDistanceMeters : forwardDistance;
        if (distanceMeters > Math.max(forwardDistance, reverseDistance) * ZONE_MARGIN) {
            return false;
        }

        int rearBoundary = object.dirType == 3 || object.dirType == 4
                ? -reverseDistance : -5;
        if (insideDirectionalLeg(distanceMeters, bearingToObject, object.direction,
                forwardDistance, rearBoundary, object.angleDegrees)) {
            return true;
        }
        return object.dirType == 2
                && insideDirectionalLeg(distanceMeters, bearingToObject,
                object.direction + 180f, reverseDistance, rearBoundary,
                object.angleDegrees);
    }

    public static boolean matchesDirectionForAcquisition(CameraPoint object,
                                                          float vehicleHeading) {
        return object.dirType == 0 || matchesDirection(object, vehicleHeading,
                ACQUIRE_DIRECTION_TOLERANCE_DEGREES);
    }

    public static boolean mustDropImmediately(CameraPoint object, float speedKmh,
                                              double distanceMeters, float vehicleHeading,
                                              float bearingToObject) {
        if (speedKmh < 8f) return false;
        if (object.dirType != 0 && !matchesDirection(object, vehicleHeading,
                RETAIN_DIRECTION_TOLERANCE_DEGREES)) {
            return true;
        }
        double longitudinal = Math.cos(Math.toRadians(
                Geo.angleDifferenceSigned(vehicleHeading, bearingToObject))) * distanceMeters;
        int passedBoundary = object.dirType == 3 || object.dirType == 4
                ? -Math.max(0, object.reverseDistanceMeters) : -5;
        return longitudinal < passedBoundary;
    }

    public static float updateConfidence(float current, float speedKmh,
                                         boolean matchesZone, boolean gpsRecovered) {
        float speedMetersPerSecond = Math.max(0f, speedKmh / 3.6f);
        if (matchesZone) {
            if (gpsRecovered && speedMetersPerSecond > 7f) {
                return MAX_APPROACH_CONFIDENCE;
            }
            return Math.min(MAX_APPROACH_CONFIDENCE,
                    current + speedMetersPerSecond);
        }
        return Math.max(0f,
                current - speedMetersPerSecond * CONFIDENCE_DECAY_FACTOR);
    }

    public static boolean shouldActivate(float confidence, int distanceMeters) {
        return confidence > distanceMeters * 0.1f;
    }

    public static boolean isOverspeeding(CameraPoint object, float speedKmh) {
        return isOverspeeding(object, speedKmh, OVERSPEED_THRESHOLD_KMH);
    }

    public static boolean isOverspeeding(CameraPoint object, float speedKmh,
                                         int thresholdKmh) {
        int limit = object.currentSpeedLimit();
        return limit > 0 && speedKmh > limit
                + AppSettings.clampOverspeedThreshold(thresholdKmh);
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

    public static float beepVolume(int distanceMeters) {
        return Math.max(0.05f, Math.min(1f, 1.6f - distanceMeters * 0.002f));
    }

    public static String screenSummary() {
        return screenSummary(OVERSPEED_THRESHOLD_KMH);
    }

    public static String screenSummary(int thresholdKmh) {
        return "1600 м → коридор → подтверждение → голос один раз → "
                + "сигнал при +" + AppSettings.clampOverspeedThreshold(thresholdKmh)
                + " км/ч → полный выход/сброс";
    }

    private static boolean matchesDirection(CameraPoint object, float vehicleHeading,
                                            int toleranceDegrees) {
        if (Geo.angleDifference(vehicleHeading, object.direction) <= toleranceDegrees) {
            return true;
        }
        return object.dirType == 2
                && Geo.angleDifference(vehicleHeading, object.direction + 180f)
                <= toleranceDegrees;
    }

    private static boolean insideDirectionalLeg(double distanceMeters,
                                                float bearingToObject,
                                                float zoneDirection,
                                                int forwardBoundary,
                                                int rearBoundary,
                                                float fullAngleDegrees) {
        double radians = Math.toRadians(bearingToObject - zoneDirection);
        double lateral = Math.sin(radians) * distanceMeters;
        double longitudinal = Math.cos(radians) * distanceMeters;
        double halfWidth = Math.max(Math.tan(Math.toRadians(
                Math.max(0f, fullAngleDegrees) / 2f)) * Math.abs(longitudinal) + 3d, 10d);
        return Math.abs(lateral) < halfWidth
                && longitudinal < forwardBoundary
                && longitudinal > rearBoundary;
    }
}
