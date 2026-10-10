package ru.gpsantiradar.app

import kotlin.math.max
import kotlin.math.min

/** Produces one noise-filtered GPS signal for the speed HUD, radar scan, and route progress. */
internal class GpsSignalFilter {
    private var latitude = Double.NaN
    private var longitude = Double.NaN
    private var accuracyMeters = DEFAULT_ACCURACY_METERS
    private var headingDegrees = Float.NaN
    private var lastElapsedRealtimeMillis = 0L

    fun update(
        rawLatitude: Double,
        rawLongitude: Double,
        rawAccuracyMeters: Float,
        isGpsProvider: Boolean,
        rawSpeedMetersPerSecond: Float?,
        speedAccuracyMetersPerSecond: Float?,
        measuredHeadingDegrees: Float?,
        elapsedRealtimeMillis: Long,
    ): Fix {
        if (!rawLatitude.isFinite() || !rawLongitude.isFinite()) {
            return currentFix()
        }
        val speedMetersPerSecond = filteredSpeedMetersPerSecond(
            isGpsProvider,
            rawSpeedMetersPerSecond,
            speedAccuracyMetersPerSecond,
        )
        val accuracy = normalizedAccuracy(rawAccuracyMeters)
        if (!latitude.isFinite() || !longitude.isFinite() ||
            lastElapsedRealtimeMillis <= 0L ||
            elapsedRealtimeMillis - lastElapsedRealtimeMillis > RESET_AFTER_MILLIS
        ) {
            latitude = rawLatitude
            longitude = rawLongitude
            accuracyMeters = accuracy
            headingDegrees = measuredHeadingDegrees
                ?.takeIf { it.isFinite() && speedMetersPerSecond >= RELIABLE_BEARING_SPEED_METERS_PER_SECOND }
                ?.let(Geo::normalize)
                ?: headingDegrees
            lastElapsedRealtimeMillis = elapsedRealtimeMillis
            return currentFix(speedMetersPerSecond)
        }

        val previousLatitude = latitude
        val previousLongitude = longitude
        val elapsedSeconds = ((elapsedRealtimeMillis - lastElapsedRealtimeMillis) / 1_000.0)
            .coerceIn(MIN_ELAPSED_SECONDS, MAX_ELAPSED_SECONDS)
        val displacement = Geo.distanceMeters(
            previousLatitude,
            previousLongitude,
            rawLatitude,
            rawLongitude,
        )
        val speed = max(0f, speedMetersPerSecond).toDouble()
        val worstAccuracy = max(accuracyMeters, accuracy)
        val plausibleDisplacement = max(
            MIN_PLAUSIBLE_DISPLACEMENT_METERS,
            speed * elapsedSeconds * MAX_SPEED_MULTIPLIER + worstAccuracy * ACCURACY_ALLOWANCE,
        )

        if (displacement > plausibleDisplacement) {
            return currentFix(speedMetersPerSecond)
        }
        val deadband = if (speed < MOVING_SPEED_METERS_PER_SECOND) {
            max(MIN_STATIONARY_DEADBAND_METERS, accuracy * STATIONARY_ACCURACY_FACTOR)
        } else {
            min(MAX_MOVING_DEADBAND_METERS, max(MIN_MOVING_DEADBAND_METERS, accuracy * MOVING_ACCURACY_FACTOR))
        }
        val interpolation = if (displacement <= deadband || displacement == 0.0) {
            0.0
        } else {
            ((displacement - deadband) / displacement).coerceIn(0.0, 1.0)
        }
        latitude += (rawLatitude - latitude) * interpolation
        longitude += (rawLongitude - longitude) * interpolation

        val stableMovement = Geo.distanceMeters(
            previousLatitude,
            previousLongitude,
            latitude,
            longitude,
        )
        val measuredHeading = measuredHeadingDegrees?.takeIf(Float::isFinite)?.let(Geo::normalize)
        headingDegrees = when {
            measuredHeading != null && speed >= RELIABLE_BEARING_SPEED_METERS_PER_SECOND ->
                smoothHeading(headingDegrees, measuredHeading, HEADING_SMOOTHING_FACTOR)
            stableMovement >= max(MIN_HEADING_MOVEMENT_METERS, accuracy * HEADING_ACCURACY_FACTOR) ->
                smoothHeading(
                    headingDegrees,
                    Geo.bearing(previousLatitude, previousLongitude, latitude, longitude),
                    HEADING_SMOOTHING_FACTOR,
                )
            else -> headingDegrees
        }
        accuracyMeters = accuracy
        lastElapsedRealtimeMillis = elapsedRealtimeMillis
        return currentFix(speedMetersPerSecond)
    }

    fun reset() {
        latitude = Double.NaN
        longitude = Double.NaN
        accuracyMeters = DEFAULT_ACCURACY_METERS
        headingDegrees = Float.NaN
        lastElapsedRealtimeMillis = 0L
    }

    private fun currentFix(speedMetersPerSecond: Float = 0f) = Fix(
        latitude,
        longitude,
        accuracyMeters.toFloat(),
        speedMetersPerSecond * 3.6f,
        headingDegrees,
    )

    private fun filteredSpeedMetersPerSecond(
        isGpsProvider: Boolean,
        rawSpeedMetersPerSecond: Float?,
        speedAccuracyMetersPerSecond: Float?,
    ): Float {
        if (!isGpsProvider || rawSpeedMetersPerSecond == null || !rawSpeedMetersPerSecond.isFinite()) {
            return 0f
        }
        val measured = max(0f, rawSpeedMetersPerSecond)
        val stationaryThreshold = max(
            MIN_RELIABLE_SPEED_METERS_PER_SECOND,
            speedAccuracyMetersPerSecond?.takeIf { it.isFinite() && it >= 0f } ?: 0f,
        )
        return if (measured <= stationaryThreshold) 0f else measured
    }

    private fun normalizedAccuracy(value: Float): Double =
        if (value.isFinite() && value > 0f) {
            value.toDouble().coerceIn(MIN_ACCURACY_METERS, MAX_ACCURACY_METERS)
        } else {
            DEFAULT_ACCURACY_METERS
        }

    private fun smoothHeading(current: Float, measured: Float, factor: Float): Float {
        if (!current.isFinite()) return measured
        return Geo.normalize(current + Geo.angleDifferenceSigned(current, measured) * factor)
    }

    internal data class Fix(
        val latitude: Double,
        val longitude: Double,
        val accuracyMeters: Float,
        val speedKmh: Float,
        val headingDegrees: Float,
    )

    private companion object {
        const val RESET_AFTER_MILLIS = 10_000L
        const val MIN_ELAPSED_SECONDS = 0.2
        const val MAX_ELAPSED_SECONDS = 5.0
        const val MIN_ACCURACY_METERS = 3.0
        const val MAX_ACCURACY_METERS = 100.0
        const val DEFAULT_ACCURACY_METERS = 15.0
        const val MIN_PLAUSIBLE_DISPLACEMENT_METERS = 15.0
        const val MAX_SPEED_MULTIPLIER = 2.5
        const val ACCURACY_ALLOWANCE = 1.5
        const val MOVING_SPEED_METERS_PER_SECOND = 1.5
        const val MIN_STATIONARY_DEADBAND_METERS = 4.0
        const val STATIONARY_ACCURACY_FACTOR = 0.75
        const val MIN_MOVING_DEADBAND_METERS = 1.5
        const val MAX_MOVING_DEADBAND_METERS = 5.0
        const val MOVING_ACCURACY_FACTOR = 0.35
        const val RELIABLE_BEARING_SPEED_METERS_PER_SECOND = 2.0
        const val MIN_HEADING_MOVEMENT_METERS = 5.0
        const val HEADING_ACCURACY_FACTOR = 0.5
        const val HEADING_SMOOTHING_FACTOR = 0.45f
        const val MIN_RELIABLE_SPEED_METERS_PER_SECOND = 0.8f
    }
}
