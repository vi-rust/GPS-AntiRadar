package ru.gpsantiradar.app

/** Defaults applied when a point is created by a long press on the phone map. */
object UserCameraDefaults {
    const val DISTANCE_METERS = 300
    const val ANGLE_DEGREES = 30f

    fun create(latitude: Double, longitude: Double, type: Int, gpsHeading: Float): CameraPoint =
        CameraPoint().apply {
            this.latitude = latitude
            this.longitude = longitude
            this.type = type
            dirType = 1
            direction = if (gpsHeading.isFinite()) {
                ((gpsHeading % 360f) + 360f) % 360f
            } else {
                0f
            }
            distanceMeters = DISTANCE_METERS
            reverseDistanceMeters = 0
            angleDegrees = ANGLE_DEGREES
            speedRules = ""
        }
}
