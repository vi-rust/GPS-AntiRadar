package ru.gpsantiradar.app

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.*

object ThemeResolver {
    private const val SUNRISE_ALTITUDE_DEGREES = -0.833

    fun isDark(
        mode: ThemeMode?, epochMillis: Long, latitude: Double, longitude: Double, fallbackTimeZone: TimeZone?
    ): Boolean {
        val resolvedMode = mode ?: ThemeMode.AUTOMATIC
        if (resolvedMode == ThemeMode.LIGHT) return false
        if (resolvedMode == ThemeMode.DARK) return true
        if (validCoordinates(latitude, longitude)) {
            return solarAltitudeDegrees(epochMillis, latitude, longitude) < SUNRISE_ALTITUDE_DEGREES
        }
        val local = Calendar.getInstance(fallbackTimeZone ?: TimeZone.getDefault()).apply { timeInMillis = epochMillis }
        val hour = local.get(Calendar.HOUR_OF_DAY)
        return hour >= 20 || hour < 7
    }

    fun solarAltitudeDegrees(epochMillis: Long, latitude: Double, longitude: Double): Double {
        val julianDay = epochMillis / 86_400_000.0 + 2_440_587.5
        val daysSinceJ2000 = julianDay - 2_451_545.0
        val meanLongitude = normalizeDegrees(280.460 + 0.9856474 * daysSinceJ2000)
        val meanAnomaly = normalizeDegrees(357.528 + 0.9856003 * daysSinceJ2000)
        val eclipticLongitude = Math.toRadians(normalizeDegrees(
            meanLongitude + 1.915 * sin(Math.toRadians(meanAnomaly)) + 0.020 * sin(Math.toRadians(2.0 * meanAnomaly))
        ))
        val obliquity = Math.toRadians(23.439 - 0.0000004 * daysSinceJ2000)
        val rightAscension = atan2(cos(obliquity) * sin(eclipticLongitude), cos(eclipticLongitude))
        val declination = asin(sin(obliquity) * sin(eclipticLongitude))
        val siderealDegrees = normalizeDegrees(280.46061837 + 360.98564736629 * daysSinceJ2000 + longitude)
        val hourAngle = Math.toRadians(normalizeSignedDegrees(siderealDegrees - Math.toDegrees(rightAscension)))
        val latitudeRadians = Math.toRadians(latitude)
        val altitude = asin(
            sin(latitudeRadians) * sin(declination) +
                cos(latitudeRadians) * cos(declination) * cos(hourAngle)
        )
        return Math.toDegrees(altitude)
    }

    private fun validCoordinates(latitude: Double, longitude: Double) =
        latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0

    private fun normalizeDegrees(value: Double): Double {
        val normalized = value % 360.0
        return if (normalized < 0.0) normalized + 360.0 else normalized
    }

    private fun normalizeSignedDegrees(value: Double): Double {
        val normalized = normalizeDegrees(value)
        return if (normalized > 180.0) normalized - 360.0 else normalized
    }
}
