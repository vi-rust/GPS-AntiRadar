package ru.gpsantiradar.app;

import java.util.Locale;

public final class DrivingHudPresentation {
    public static final int COLOR_GREEN = 0xFF00A652;
    public static final int COLOR_ALERT = 0xFFFFBE37;
    public static final int COLOR_OVERSPEED = 0xFFE53935;

    public final String speedText;
    public final String distanceText;
    public final String cameraText;
    public final int speedColor;
    public final boolean hasActiveObject;

    private DrivingHudPresentation(String speedText, String distanceText,
            String cameraText, int speedColor, boolean hasActiveObject) {
        this.speedText = speedText;
        this.distanceText = distanceText;
        this.cameraText = cameraText;
        this.speedColor = speedColor;
        this.hasActiveObject = hasActiveObject;
    }

    public static DrivingHudPresentation from(DrivingSnapshot snapshot) {
        return from(snapshot, AppSettings.DEFAULT_OVERSPEED_THRESHOLD_KMH);
    }

    public static DrivingHudPresentation from(DrivingSnapshot snapshot,
                                               int overspeedThresholdKmh) {
        boolean hasActiveObject = snapshot.hasObject();
        int speedColor = color(snapshot, hasActiveObject, overspeedThresholdKmh);
        String speedText = Integer.toString(Math.round(snapshot.speedKmh));
        String distanceText = snapshot.distanceMeters < 0 ? "—"
                : formatDistance(snapshot.distanceMeters);
        String cameraText = hasActiveObject
                ? snapshot.cameraName + limitSuffix(snapshot.speedLimitKmh)
                : "Объектов впереди нет";
        return new DrivingHudPresentation(speedText, distanceText, cameraText,
                speedColor, hasActiveObject);
    }

    private static int color(DrivingSnapshot snapshot, boolean hasActiveObject,
                             int overspeedThresholdKmh) {
        if (!hasActiveObject || snapshot.distanceMeters > snapshot.alertDistanceMeters
                || snapshot.speedLimitKmh <= 0
                || snapshot.speedKmh < snapshot.speedLimitKmh) {
            return COLOR_GREEN;
        }
        int threshold = AppSettings.clampOverspeedThreshold(overspeedThresholdKmh);
        return snapshot.speedKmh >= snapshot.speedLimitKmh + threshold
                ? COLOR_OVERSPEED : COLOR_ALERT;
    }

    private static String formatDistance(int meters) {
        return meters >= 1000
                ? String.format(Locale.US, "%.1f км", meters / 1000.0)
                : meters + " м";
    }

    private static String limitSuffix(int speedLimitKmh) {
        return speedLimitKmh > 0 ? "  ·  " + speedLimitKmh + " км/ч" : "";
    }
}
