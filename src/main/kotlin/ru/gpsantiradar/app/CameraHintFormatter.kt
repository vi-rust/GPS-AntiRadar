package ru.gpsantiradar.app

/** Shared marker details for the phone and Android Auto map surfaces. */
object CameraHintFormatter {
    fun format(camera: CameraPoint?): String {
        if (camera == null) return ""
        val result = StringBuilder(camera.typeName())
        val speedLimit = camera.currentSpeedLimit()
        if (speedLimit > 0) result.append(" · ").append(speedLimit).append(" км/ч")
        result.append(if (camera.isCameraOrControl()) "\nЗона контроля: " else "\nОповещение: ")
            .append(camera.distanceMeters).append(" м")
            .append("\nНаправленность: ").append(camera.directionName())
        if (camera.isCameraOrControl() && camera.dirType == 0) result.append("\nФорма зоны: круг")
        return result.toString()
    }
}
