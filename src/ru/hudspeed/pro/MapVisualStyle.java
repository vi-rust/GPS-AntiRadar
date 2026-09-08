package ru.gpsantiradar.app;

/** Pure color decisions shared by camera coverage and the current-location marker. */
public final class MapVisualStyle {
    public static final int LOCATION_PRIMARY_COLOR = 0xFF1976D2;
    public static final int LOCATION_HIGHLIGHT_COLOR = 0xFF64B5F6;
    private static final int NIGHT_LOCATION_PRIMARY_COLOR = 0xFFFFBE00;
    private static final int NIGHT_LOCATION_HIGHLIGHT_COLOR = 0xFFFFD848;

    private static final int NORMAL_FILL_ALPHA = 0x26;
    private static final int NORMAL_STROKE_ALPHA = 0x4D;
    private static final int ACTIVE_FILL_ALPHA = 0x4D;
    private static final int ACTIVE_STROKE_ALPHA = 0x73;

    private MapVisualStyle() {}

    public static int locationPrimaryColor(boolean nightMode) {
        return nightMode ? NIGHT_LOCATION_PRIMARY_COLOR : LOCATION_PRIMARY_COLOR;
    }

    public static int locationHighlightColor(boolean nightMode) {
        return nightMode ? NIGHT_LOCATION_HIGHLIGHT_COLOR : LOCATION_HIGHLIGHT_COLOR;
    }

    public static Coverage coverage(int baseColor, long cameraId, long activeCameraId) {
        boolean active = cameraId >= 0L && cameraId == activeCameraId;
        return new Coverage(
                argb(baseColor, active ? ACTIVE_FILL_ALPHA : NORMAL_FILL_ALPHA),
                argb(baseColor, active ? ACTIVE_STROKE_ALPHA : NORMAL_STROKE_ALPHA));
    }

    private static int argb(int baseColor, int alpha) {
        return (alpha << 24) | (baseColor & 0x00FFFFFF);
    }

    public static final class Coverage {
        public final int fillColor;
        public final int strokeColor;

        private Coverage(int fillColor, int strokeColor) {
            this.fillColor = fillColor;
            this.strokeColor = strokeColor;
        }
    }
}
