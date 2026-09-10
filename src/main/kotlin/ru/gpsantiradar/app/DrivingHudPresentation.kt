package ru.gpsantiradar.app

import java.util.Locale
import kotlin.math.roundToInt

class DrivingHudPresentation private constructor(
    val speedText: String,
    val distanceText: String,
    val cameraText: String,
    val speedColor: Int,
    val hasObject: Boolean,
    val hasActiveObject: Boolean
) {
    companion object {
        val COLOR_GREEN = 0xFF00A652.toInt()
        val COLOR_ALERT = 0xFFFFBE37.toInt()
        val COLOR_OVERSPEED = 0xFFE53935.toInt()

        fun from(snapshot: DrivingSnapshot) = from(snapshot, AppSettings.DEFAULT_OVERSPEED_THRESHOLD_KMH)

        fun from(snapshot: DrivingSnapshot, overspeedThresholdKmh: Int): DrivingHudPresentation {
            val hasObject = snapshot.hasObject()
            val hasActiveObject = snapshot.hasActiveCamera()
            val speedColor = color(snapshot, hasActiveObject, overspeedThresholdKmh)
            val distanceText = if (snapshot.distanceMeters < 0) "—" else formatDistance(snapshot.distanceMeters)
            val cameraText = if (hasObject) snapshot.cameraName + limitSuffix(snapshot.speedLimitKmh)
                else "Объектов впереди нет"
            return DrivingHudPresentation(
                snapshot.speedKmh.roundToInt().toString(), distanceText, cameraText, speedColor,
                hasObject, hasActiveObject,
            )
        }

        private fun color(snapshot: DrivingSnapshot, hasActiveObject: Boolean, overspeedThresholdKmh: Int): Int {
            if (!hasActiveObject || snapshot.speedLimitKmh <= 0 || snapshot.speedKmh < snapshot.speedLimitKmh
            ) return COLOR_GREEN
            val threshold = AppSettings.clampOverspeedThreshold(overspeedThresholdKmh)
            return if (snapshot.speedKmh >= snapshot.speedLimitKmh + threshold) COLOR_OVERSPEED else COLOR_ALERT
        }

        private fun formatDistance(meters: Int) =
            if (meters >= 1000) String.format(Locale.US, "%.1f км", meters / 1000.0) else "$meters м"
        private fun limitSuffix(speedLimitKmh: Int) = if (speedLimitKmh > 0) "  ·  $speedLimitKmh км/ч" else ""
    }
}
