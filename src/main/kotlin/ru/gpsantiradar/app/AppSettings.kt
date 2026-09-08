package ru.gpsantiradar.app

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

object AppSettings {
    const val PREFERENCES = "settings"
    const val OVERSPEED_THRESHOLD = "overspeed_threshold_kmh"
    const val MAPKIT_KEY = "yandex_mapkit_key"
    const val MAPKIT_PENDING = "mapkit_startup_pending"
    const val MAPKIT_SAFE_MIGRATION = "mapkit_safe_startup_v4"
    const val MAPKIT_MARKER_FIX = "mapkit_marker_fix_v5"
    const val MAPKIT_KEY_REENTRY = "mapkit_key_reentry_v6"
    const val HUD_TRANSPARENCY = "hud_transparency"
    const val AUTO_ROTATE_MAP = "auto_rotate_map"
    const val THEME_MODE = "theme_mode"
    const val THEME_LATITUDE = "theme_latitude"
    const val THEME_LONGITUDE = "theme_longitude"
    const val RADARBASE_LAST_SUCCESSFUL_DOWNLOAD = "radarbase_last_successful_download"

    const val DEFAULT_OVERSPEED_THRESHOLD_KMH = 10
    const val MIN_OVERSPEED_THRESHOLD_KMH = 0
    const val MAX_OVERSPEED_THRESHOLD_KMH = 20
    const val OVERSPEED_THRESHOLD_STEP_KMH = 1
    const val DEFAULT_HUD_TRANSPARENCY_PERCENT = 10
    const val DEFAULT_AUTO_ROTATE_MAP = false
    const val MIN_HUD_TRANSPARENCY_PERCENT = 0
    const val MAX_HUD_TRANSPARENCY_PERCENT = 80
    const val HUD_TRANSPARENCY_STEP_PERCENT = 5

    fun clampOverspeedThreshold(value: Int): Int =
        max(MIN_OVERSPEED_THRESHOLD_KMH, min(MAX_OVERSPEED_THRESHOLD_KMH, value))

    fun adjustOverspeedThreshold(value: Int, direction: Int): Int =
        clampOverspeedThreshold(clampOverspeedThreshold(value) + direction.sign * OVERSPEED_THRESHOLD_STEP_KMH)

    fun clampHudTransparency(value: Int): Int =
        floorToStep(value, MIN_HUD_TRANSPARENCY_PERCENT, MAX_HUD_TRANSPARENCY_PERCENT, HUD_TRANSPARENCY_STEP_PERCENT)

    fun adjustHudTransparency(value: Int, direction: Int): Int =
        clampHudTransparency(clampHudTransparency(value) + direction.sign * HUD_TRANSPARENCY_STEP_PERCENT)

    private fun floorToStep(value: Int, minValue: Int, maxValue: Int, step: Int): Int {
        val clamped = max(minValue, min(maxValue, value))
        return minValue + (clamped - minValue) / step * step
    }
}
