package ru.gpsantiradar.app;

public final class AppSettings {
    public static final String PREFERENCES = "settings";
    public static final String ALERT_DISTANCE = "alert_distance";
    public static final String OVERSPEED_THRESHOLD = "overspeed_threshold_kmh";
    public static final String MAPKIT_KEY = "yandex_mapkit_key";
    public static final String HUD_TRANSPARENCY = "hud_transparency";
    public static final String AUTO_ROTATE_MAP = "auto_rotate_map";
    public static final String RADARBASE_LAST_SUCCESSFUL_DOWNLOAD =
            "radarbase_last_successful_download";

    public static final int DEFAULT_ALERT_DISTANCE_METERS = 800;
    public static final int MIN_ALERT_DISTANCE_METERS = 300;
    public static final int MAX_ALERT_DISTANCE_METERS = 2000;
    public static final int ALERT_DISTANCE_STEP_METERS = 100;
    public static final int DEFAULT_OVERSPEED_THRESHOLD_KMH = 10;
    public static final int MIN_OVERSPEED_THRESHOLD_KMH = 0;
    public static final int MAX_OVERSPEED_THRESHOLD_KMH = 20;
    public static final int OVERSPEED_THRESHOLD_STEP_KMH = 1;
    public static final int DEFAULT_HUD_TRANSPARENCY_PERCENT = 10;
    public static final boolean DEFAULT_AUTO_ROTATE_MAP = false;
    public static final int MIN_HUD_TRANSPARENCY_PERCENT = 0;
    public static final int MAX_HUD_TRANSPARENCY_PERCENT = 80;
    public static final int HUD_TRANSPARENCY_STEP_PERCENT = 5;

    private AppSettings() {}

    public static int clampAlertDistance(int value) {
        return floorToStep(value, MIN_ALERT_DISTANCE_METERS,
                MAX_ALERT_DISTANCE_METERS, ALERT_DISTANCE_STEP_METERS);
    }

    public static int adjustAlertDistance(int value, int direction) {
        int normalized = clampAlertDistance(value);
        return clampAlertDistance(normalized
                + Integer.signum(direction) * ALERT_DISTANCE_STEP_METERS);
    }

    public static int clampOverspeedThreshold(int value) {
        return Math.max(MIN_OVERSPEED_THRESHOLD_KMH,
                Math.min(MAX_OVERSPEED_THRESHOLD_KMH, value));
    }

    public static int adjustOverspeedThreshold(int value, int direction) {
        int normalized = clampOverspeedThreshold(value);
        return clampOverspeedThreshold(normalized
                + Integer.signum(direction) * OVERSPEED_THRESHOLD_STEP_KMH);
    }

    public static int clampHudTransparency(int value) {
        return floorToStep(value, MIN_HUD_TRANSPARENCY_PERCENT,
                MAX_HUD_TRANSPARENCY_PERCENT, HUD_TRANSPARENCY_STEP_PERCENT);
    }

    public static int adjustHudTransparency(int value, int direction) {
        int normalized = clampHudTransparency(value);
        return clampHudTransparency(normalized
                + Integer.signum(direction) * HUD_TRANSPARENCY_STEP_PERCENT);
    }

    private static int floorToStep(int value, int min, int max, int step) {
        int clamped = Math.max(min, Math.min(max, value));
        return min + ((clamped - min) / step) * step;
    }
}
