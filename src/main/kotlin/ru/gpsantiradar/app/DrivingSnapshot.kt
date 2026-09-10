package ru.gpsantiradar.app

class DrivingSnapshot(
    val speedKmh: Float,
    val accuracyMeters: Float,
    val distanceMeters: Int,
    cameraName: String?,
    val cameraId: Long,
    val speedLimitKmh: Int,
    val alertDistanceMeters: Int,
    val latitude: Double,
    val longitude: Double,
    val headingDegrees: Float,
    alertState: String?,
    alertAlgorithm: String?,
    activeCameraIds: LongArray? = null
) {
    val cameraName: String = cameraName ?: ""
    val alertState: String = alertState ?: ""
    val alertAlgorithm: String = alertAlgorithm ?: ""
    val activeCameraIds: LongArray = activeCameraIds?.copyOf() ?: longArrayOf()

    fun hasLocation(): Boolean = !latitude.isNaN() && !longitude.isNaN()
    fun hasObject(): Boolean = distanceMeters >= 0
    fun hasActiveCamera(): Boolean = cameraId >= 0L && activeCameraIds.contains(cameraId)

    companion object {
        fun idle() = DrivingSnapshot(
            0f, Float.NaN, -1, "", -1, 0, 0,
            Double.NaN, Double.NaN, Float.NaN, "", ""
        )
    }
}
