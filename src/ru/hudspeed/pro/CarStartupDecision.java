package ru.gpsantiradar.app;

/** Separates radar availability from the optional Android Auto map surface. */
public final class CarStartupDecision {
    public final boolean startTracking;
    public final boolean showMap;

    private CarStartupDecision(boolean startTracking, boolean showMap) {
        this.startTracking = startTracking;
        this.showMap = showMap;
    }

    public static CarStartupDecision from(boolean locationGranted, boolean mapKitReady) {
        return new CarStartupDecision(locationGranted, locationGranted && mapKitReady);
    }
}
