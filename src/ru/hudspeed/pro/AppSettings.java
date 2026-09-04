package ru.gpsantiradar.app;

public final class AppSettings {
    public static final String PREFERENCES = "settings";
    public static final String ALERT_DISTANCE = "alert_distance";
    public static final String OVERSPEED_THRESHOLD = "overspeed_threshold_kmh";
    public static final String RADARBASE_LAST_SUCCESSFUL_DOWNLOAD =
            "radarbase_last_successful_download";

    public static final int DEFAULT_OVERSPEED_THRESHOLD_KMH = 10;
    public static final int MAX_OVERSPEED_THRESHOLD_KMH = 20;

    private AppSettings() {}

    public static int clampOverspeedThreshold(int value) {
        return Math.max(0, Math.min(MAX_OVERSPEED_THRESHOLD_KMH, value));
    }
}
