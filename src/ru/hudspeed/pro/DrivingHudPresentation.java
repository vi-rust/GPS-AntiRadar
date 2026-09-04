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
        boolean hasActiveObject = snapshot.hasObject();
        int speedColor = color(snapshot, hasActiveObject);
        String speedText = Integer.toString(Math.round(snapshot.speedKmh));
        String distanceText = snapshot.distanceMeters < 0 ? "—"
                : formatDistance(snapshot.distanceMeters);
        String cameraText = hasActiveObject
                ? snapshot.cameraName + limitSuffix(snapshot.speedLimitKmh)
                : "Объектов впереди нет";
        return new DrivingHudPresentation(speedText, distanceText, cameraText,
                speedColor, hasActiveObject);
    }

    private static int color(DrivingSnapshot snapshot, boolean hasActiveObject) {
        if (!hasActiveObject) return COLOR_GREEN;
        if (snapshot.speedLimitKmh > 0 && snapshot.speedKmh > snapshot.speedLimitKmh) {
            return COLOR_OVERSPEED;
        }
        return snapshot.distanceMeters <= snapshot.alertDistanceMeters
                ? COLOR_ALERT : COLOR_GREEN;
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
