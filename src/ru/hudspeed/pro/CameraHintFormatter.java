package ru.gpsantiradar.app;

/** Shared marker details for the phone and Android Auto map surfaces. */
public final class CameraHintFormatter {
    private CameraHintFormatter() {}

    public static String format(CameraPoint camera) {
        if (camera == null) return "";
        StringBuilder result = new StringBuilder(camera.typeName());
        int speedLimit = camera.currentSpeedLimit();
        if (speedLimit > 0) {
            result.append(" · ").append(speedLimit).append(" км/ч");
        }
        result.append(camera.isCameraOrControl() ? "\nЗона контроля: " : "\nОповещение: ")
                .append(camera.distanceMeters).append(" м");
        result.append("\nНаправленность: ").append(camera.directionName());
        if (camera.isCameraOrControl() && camera.dirType == 0) {
            result.append("\nФорма зоны: круг");
        }
        return result.toString();
    }
}
