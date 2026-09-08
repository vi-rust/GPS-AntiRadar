package ru.gpsantiradar.app

import kotlin.math.*

object Geo {
    private const val EARTH_RADIUS_M = 6371000.0

    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = Math.toRadians(lat2 - lat1)
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return EARTH_RADIUS_M * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return normalize(Math.toDegrees(atan2(y, x)).toFloat())
    }

    fun angleDifference(a: Float, b: Float): Float {
        val difference = abs(normalize(a) - normalize(b))
        return if (difference > 180f) 360f - difference else difference
    }

    fun angleDifferenceSigned(first: Float, second: Float): Float {
        var difference = (second - first) % 360f
        if (difference > 180f) difference -= 360f
        if (difference < -180f) difference += 360f
        return difference
    }

    fun normalize(angle: Float): Float {
        val result = angle % 360f
        return if (result < 0) result + 360f else result
    }
}
