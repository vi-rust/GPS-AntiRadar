package ru.gpsantiradar.app

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

object AppSettings {
    const val PREFERENCES = "settings"
    const val OVERSPEED_THRESHOLD = "overspeed_threshold_kmh"
    const val MAPKIT_PENDING = "mapkit_startup_pending"
    const val HUD_TRANSPARENCY = "hud_transparency"
    const val ZONE_TRANSPARENCY = "zone_transparency"
    const val ACTIVE_ZONE_TRANSPARENCY = "active_zone_transparency"
    const val ZONE_DISPLAY_MODE = "zone_display_mode"
    const val AUTO_ROTATE_MAP = "auto_rotate_map"
    const val LOCATION_ARROW_SCALE = "location_arrow_scale_tenths"
    const val UI_SCALE_PERCENT = "ui_scale_percent"
    const val MAP_SCALE_PERCENT = "map_scale_percent"
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
    const val DEFAULT_LOCATION_ARROW_SCALE_TENTHS = 10
    const val MIN_LOCATION_ARROW_SCALE_TENTHS = 10
    const val MAX_LOCATION_ARROW_SCALE_TENTHS = 20
    const val LOCATION_ARROW_SCALE_STEP_TENTHS = 1
    const val DEFAULT_UI_SCALE_PERCENT = 100
    const val MIN_UI_SCALE_PERCENT = 100
    const val MAX_UI_SCALE_PERCENT = 200
    const val UI_SCALE_STEP_PERCENT = 10
    const val DEFAULT_MAP_SCALE_PERCENT = 100
    const val MIN_MAP_SCALE_PERCENT = 100
    const val MAX_MAP_SCALE_PERCENT = 500
    const val MAP_SCALE_STEP_PERCENT = 10
    const val MIN_HUD_TRANSPARENCY_PERCENT = 0
    const val MAX_HUD_TRANSPARENCY_PERCENT = 80
    const val HUD_TRANSPARENCY_STEP_PERCENT = 5
    const val DEFAULT_ZONE_TRANSPARENCY_PERCENT = 85
    const val DEFAULT_ACTIVE_ZONE_TRANSPARENCY_PERCENT = 70
    const val MIN_ZONE_TRANSPARENCY_PERCENT = 10
    const val MAX_ZONE_TRANSPARENCY_PERCENT = 90
    const val ZONE_TRANSPARENCY_STEP_PERCENT = 5

    fun clampOverspeedThreshold(value: Int): Int =
        max(MIN_OVERSPEED_THRESHOLD_KMH, min(MAX_OVERSPEED_THRESHOLD_KMH, value))

    fun adjustOverspeedThreshold(value: Int, direction: Int): Int =
        clampOverspeedThreshold(clampOverspeedThreshold(value) + direction.sign * OVERSPEED_THRESHOLD_STEP_KMH)

    fun clampHudTransparency(value: Int): Int =
        floorToStep(value, MIN_HUD_TRANSPARENCY_PERCENT, MAX_HUD_TRANSPARENCY_PERCENT, HUD_TRANSPARENCY_STEP_PERCENT)

    fun adjustHudTransparency(value: Int, direction: Int): Int =
        clampHudTransparency(clampHudTransparency(value) + direction.sign * HUD_TRANSPARENCY_STEP_PERCENT)

    fun clampZoneTransparency(value: Int): Int =
        floorToStep(
            value,
            MIN_ZONE_TRANSPARENCY_PERCENT,
            MAX_ZONE_TRANSPARENCY_PERCENT,
            ZONE_TRANSPARENCY_STEP_PERCENT,
        )

    fun adjustZoneTransparency(value: Int, direction: Int): Int =
        clampZoneTransparency(clampZoneTransparency(value) + direction.sign * ZONE_TRANSPARENCY_STEP_PERCENT)

    fun clampLocationArrowScale(value: Int): Int =
        max(MIN_LOCATION_ARROW_SCALE_TENTHS, min(MAX_LOCATION_ARROW_SCALE_TENTHS, value))

    fun adjustLocationArrowScale(value: Int, direction: Int): Int =
        clampLocationArrowScale(
            clampLocationArrowScale(value) + direction.sign * LOCATION_ARROW_SCALE_STEP_TENTHS,
        )

    fun locationArrowScale(value: Int): Float = clampLocationArrowScale(value) / 10f

    fun normalizeUiScalePercent(value: Int): Int {
        val clamped = max(MIN_UI_SCALE_PERCENT, min(MAX_UI_SCALE_PERCENT, value))
        val steps = (clamped - MIN_UI_SCALE_PERCENT + UI_SCALE_STEP_PERCENT / 2) /
            UI_SCALE_STEP_PERCENT
        return MIN_UI_SCALE_PERCENT + steps * UI_SCALE_STEP_PERCENT
    }

    fun normalizeMapScalePercent(value: Int): Int {
        val clamped = max(MIN_MAP_SCALE_PERCENT, min(MAX_MAP_SCALE_PERCENT, value))
        val steps = (clamped - MIN_MAP_SCALE_PERCENT + MAP_SCALE_STEP_PERCENT / 2) /
            MAP_SCALE_STEP_PERCENT
        return MIN_MAP_SCALE_PERCENT + steps * MAP_SCALE_STEP_PERCENT
    }

    private fun floorToStep(value: Int, minValue: Int, maxValue: Int, step: Int): Int {
        val clamped = max(minValue, min(maxValue, value))
        return minValue + (clamped - minValue) / step * step
    }
}
