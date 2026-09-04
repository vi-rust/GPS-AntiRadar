package ru.gpsantiradar.app;

public final class DrivingSnapshot {
    public final float speedKmh;
    public final int distanceMeters;
    public final String cameraName;
    public final long cameraId;
    public final int speedLimitKmh;
    public final int alertDistanceMeters;
    public final double latitude;
    public final double longitude;
    public final String alertState;
    public final String alertAlgorithm;

    public DrivingSnapshot(float speedKmh, int distanceMeters, String cameraName, long cameraId,
            int speedLimitKmh, int alertDistanceMeters, double latitude, double longitude,
            String alertState, String alertAlgorithm) {
        this.speedKmh = speedKmh;
        this.distanceMeters = distanceMeters;
        this.cameraName = cameraName == null ? "" : cameraName;
        this.cameraId = cameraId;
        this.speedLimitKmh = speedLimitKmh;
        this.alertDistanceMeters = alertDistanceMeters;
        this.latitude = latitude;
        this.longitude = longitude;
        this.alertState = alertState == null ? "" : alertState;
        this.alertAlgorithm = alertAlgorithm == null ? "" : alertAlgorithm;
    }

    public static DrivingSnapshot idle() {
        return new DrivingSnapshot(0, -1, "", -1, 0, 0,
                Double.NaN, Double.NaN, "", "");
    }

    public boolean hasLocation() {
        return !Double.isNaN(latitude) && !Double.isNaN(longitude);
    }

    public boolean hasObject() {
        return distanceMeters >= 0;
    }
}
