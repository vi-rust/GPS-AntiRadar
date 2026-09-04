package ru.gpsantiradar.app;

/** Pure heading decisions shared by location-marker and map-camera updates. */
public final class MapOrientation {
    private static final float MOVING_SPEED_KMH = 3f;

    private MapOrientation() {}

    public static float stableHeading(float previousHeading, float measuredHeading,
                                      float speedKmh) {
        if (speedKmh < MOVING_SPEED_KMH || Float.isNaN(measuredHeading)
                || Float.isInfinite(measuredHeading)) {
            return previousHeading;
        }
        return Geo.normalize(measuredHeading);
    }

    public static float cameraAzimuth(boolean autoRotate, float speedKmh,
                                      float heading, float currentAzimuth) {
        return autoRotate && speedKmh >= MOVING_SPEED_KMH
                ? heading : currentAzimuth;
    }
}
