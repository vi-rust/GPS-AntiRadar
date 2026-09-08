package ru.gpsantiradar.app

import kotlin.math.*

object StrelkaAlertAlgorithm {
    const val SEARCH_RADIUS_METERS = 1600
    const val ACQUIRE_DIRECTION_TOLERANCE_DEGREES = 25
    const val RETAIN_DIRECTION_TOLERANCE_DEGREES = 45
    const val OVERSPEED_THRESHOLD_KMH = AppSettings.DEFAULT_OVERSPEED_THRESHOLD_KMH
    const val MAX_APPROACH_CONFIDENCE = 100f
    private const val ZONE_MARGIN = 1.1
    private const val CONFIDENCE_DECAY_FACTOR = 0.5f

    fun activationDistance(camera: CameraPoint?): Int =
        if (camera == null || camera.distanceMeters <= 0) 0 else max(50, min(SEARCH_RADIUS_METERS, camera.distanceMeters))

    fun matchesZone(camera: CameraPoint, distanceMeters: Double, vehicleHeading: Float, bearingToObject: Float): Boolean {
        val forwardDistance = activationDistance(camera)
        if (forwardDistance == 0) return false
        if (camera.dirType == 0) return distanceMeters < forwardDistance * ZONE_MARGIN
        if (!matchesDirection(camera, vehicleHeading, ACQUIRE_DIRECTION_TOLERANCE_DEGREES)) return false
        val reverseDistance = if (camera.reverseDistanceMeters > 0) camera.reverseDistanceMeters else forwardDistance
        if (distanceMeters > max(forwardDistance, reverseDistance) * ZONE_MARGIN) return false
        val rearBoundary = if (camera.dirType == 3 || camera.dirType == 4) -reverseDistance else -5
        if (insideDirectionalLeg(distanceMeters, bearingToObject, camera.direction, forwardDistance, rearBoundary, camera.angleDegrees)) return true
        return camera.dirType == 2 && insideDirectionalLeg(
            distanceMeters, bearingToObject, camera.direction + 180f, reverseDistance, rearBoundary, camera.angleDegrees
        )
    }

    fun matchesDirectionForAcquisition(camera: CameraPoint, vehicleHeading: Float) =
        camera.dirType == 0 || matchesDirection(camera, vehicleHeading, ACQUIRE_DIRECTION_TOLERANCE_DEGREES)

    fun mustDropImmediately(
        camera: CameraPoint, speedKmh: Float, distanceMeters: Double, vehicleHeading: Float, bearingToObject: Float
    ): Boolean {
        if (speedKmh < 8f) return false
        if (camera.dirType != 0 && !matchesDirection(camera, vehicleHeading, RETAIN_DIRECTION_TOLERANCE_DEGREES)) return true
        val longitudinal = cos(Math.toRadians(Geo.angleDifferenceSigned(vehicleHeading, bearingToObject).toDouble())) * distanceMeters
        val passedBoundary = if (camera.dirType == 3 || camera.dirType == 4) -max(0, camera.reverseDistanceMeters) else -5
        return longitudinal < passedBoundary
    }

    fun updateConfidence(current: Float, speedKmh: Float, matchesZone: Boolean, gpsRecovered: Boolean): Float {
        val speedMetersPerSecond = max(0f, speedKmh / 3.6f)
        if (matchesZone) {
            if (gpsRecovered && speedMetersPerSecond > 7f) return MAX_APPROACH_CONFIDENCE
            return min(MAX_APPROACH_CONFIDENCE, current + speedMetersPerSecond)
        }
        return max(0f, current - speedMetersPerSecond * CONFIDENCE_DECAY_FACTOR)
    }

    fun shouldActivate(confidence: Float, distanceMeters: Int) = confidence > distanceMeters * 0.1f
    fun isOverspeeding(camera: CameraPoint, speedKmh: Float) = isOverspeeding(camera, speedKmh, OVERSPEED_THRESHOLD_KMH)
    fun isOverspeeding(camera: CameraPoint, speedKmh: Float, thresholdKmh: Int): Boolean {
        val limit = camera.currentSpeedLimit()
        return limit > 0 && speedKmh > limit + AppSettings.clampOverspeedThreshold(thresholdKmh)
    }

    fun spokenDistance(distanceMeters: Int) = when {
        distanceMeters > 950 -> 1000; distanceMeters > 850 -> 900; distanceMeters > 750 -> 800
        distanceMeters > 650 -> 700; distanceMeters > 550 -> 600; distanceMeters > 450 -> 500
        distanceMeters > 350 -> 400; distanceMeters > 250 -> 300; distanceMeters > 175 -> 200
        distanceMeters > 125 -> 150; distanceMeters > 75 -> 100; distanceMeters > 45 -> 50
        distanceMeters > 35 -> 40; distanceMeters > 25 -> 30; distanceMeters > 15 -> 20
        distanceMeters > 5 -> 10; else -> 0
    }
    fun beepVolume(distanceMeters: Int) = max(0.05f, min(1f, 1.6f - distanceMeters * 0.002f))
    fun screenSummary() = screenSummary(OVERSPEED_THRESHOLD_KMH)
    fun screenSummary(thresholdKmh: Int) =
        "1600 м → коридор → подтверждение → голос один раз → сигнал при +${AppSettings.clampOverspeedThreshold(thresholdKmh)} км/ч → полный выход/сброс"

    private fun matchesDirection(camera: CameraPoint, vehicleHeading: Float, toleranceDegrees: Int): Boolean =
        Geo.angleDifference(vehicleHeading, camera.direction) <= toleranceDegrees ||
            camera.dirType == 2 && Geo.angleDifference(vehicleHeading, camera.direction + 180f) <= toleranceDegrees

    private fun insideDirectionalLeg(
        distanceMeters: Double, bearingToObject: Float, zoneDirection: Float,
        forwardBoundary: Int, rearBoundary: Int, fullAngleDegrees: Float
    ): Boolean {
        val radians = Math.toRadians((bearingToObject - zoneDirection).toDouble())
        val lateral = sin(radians) * distanceMeters
        val longitudinal = cos(radians) * distanceMeters
        val halfWidth = max(tan(Math.toRadians((max(0f, fullAngleDegrees) / 2f).toDouble())) * abs(longitudinal) + 3.0, 10.0)
        return abs(lateral) < halfWidth && longitudinal < forwardBoundary && longitudinal > rearBoundary
    }
}
