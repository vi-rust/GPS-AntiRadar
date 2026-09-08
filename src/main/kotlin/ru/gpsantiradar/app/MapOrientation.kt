package ru.gpsantiradar.app

/** Pure heading decisions shared by location-marker and map-camera updates. */
object MapOrientation {
    private const val MOVING_SPEED_KMH = 3f

    fun stableHeading(previousHeading: Float, measuredHeading: Float, speedKmh: Float): Float {
        if (speedKmh < MOVING_SPEED_KMH || measuredHeading.isNaN() || measuredHeading.isInfinite()) return previousHeading
        return Geo.normalize(measuredHeading)
    }

    fun cameraAzimuth(autoRotate: Boolean, speedKmh: Float, heading: Float, currentAzimuth: Float): Float =
        if (autoRotate && speedKmh >= MOVING_SPEED_KMH) heading else currentAzimuth
}
