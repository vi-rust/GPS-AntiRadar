package ru.gpsantiradar.app

data class CarMapCameraState(
    val latitude: Double,
    val longitude: Double,
    val zoom: Float,
    val azimuth: Float,
    val tilt: Float,
    val centeredOnGps: Boolean,
    val followPauseRemainingMs: Long,
) {
    fun isValid(): Boolean =
        latitude.isFinite() && latitude in -90.0..90.0 &&
            longitude.isFinite() && longitude in -180.0..180.0 &&
            zoom.isFinite() && zoom in 2f..21f &&
            azimuth.isFinite() && tilt.isFinite() &&
            followPauseRemainingMs >= 0L
}
