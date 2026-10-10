package ru.gpsantiradar.app

import java.util.Locale

/** Shared marker details for the phone and Android Auto map surfaces. */
object CameraHintFormatter {
    fun format(camera: CameraPoint?): String {
        if (camera == null) return ""
        val speedLimit = camera.currentSpeedLimit()
        val result = StringBuilder()
            .append("Тип объекта: ").append(camera.typeName())
            .append("\nКоординаты: ").append(
                String.format(Locale.US, "%.6f, %.6f", camera.latitude, camera.longitude),
            )
            .append("\nНаправленность: ").append(camera.directionName())
            .append("\nНаправление: ").append(formatDegrees(camera.direction))
            .append("\nДистанция оповещения: ").append(camera.distanceMeters).append(" м")
            .append("\nОбратная дистанция: ").append(camera.reverseDistanceMeters).append(" м")
            .append("\nУгол сектора: ").append(formatDegrees(camera.angleDegrees))
            .append("\nОграничение скорости: ")
            .append(if (speedLimit > 0) "$speedLimit км/ч" else "нет")
        if (camera.userDefined) {
            result.append("\nПеретаскивание: ")
                .append(if (camera.draggingLocked) "запрещено" else "разрешено")
        }
        if (camera.isCameraOrControl() && camera.dirType == 0) result.append("\nФорма зоны: круг")
        return result.toString()
    }

    private fun formatDegrees(value: Float): String = "${value.toString().removeSuffix(".0")}°"
}
