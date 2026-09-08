package ru.gpsantiradar.app

import kotlin.math.max
import kotlin.math.roundToInt

/** Pure color decisions shared by camera coverage and the current-location marker. */
object MapVisualStyle {
    val LOCATION_PRIMARY_COLOR: Int = 0xFF1976D2.toInt()
    val LOCATION_HIGHLIGHT_COLOR: Int = 0xFF64B5F6.toInt()
    private val nightLocationPrimaryColor = 0xFFFFBE00.toInt()
    private val nightLocationHighlightColor = 0xFFFFD848.toInt()
    private const val STROKE_TRANSPARENCY_OFFSET_PERCENT = 15

    fun locationPrimaryColor(nightMode: Boolean) =
        if (nightMode) nightLocationPrimaryColor else LOCATION_PRIMARY_COLOR

    fun locationHighlightColor(nightMode: Boolean) =
        if (nightMode) nightLocationHighlightColor else LOCATION_HIGHLIGHT_COLOR

    fun coverage(
        baseColor: Int,
        cameraId: Long,
        activeCameraId: Long,
        zoneTransparencyPercent: Int = AppSettings.DEFAULT_ZONE_TRANSPARENCY_PERCENT,
        activeZoneTransparencyPercent: Int = AppSettings.DEFAULT_ACTIVE_ZONE_TRANSPARENCY_PERCENT,
    ): Coverage {
        val active = cameraId >= 0L && cameraId == activeCameraId
        val fillTransparency = AppSettings.clampZoneTransparency(
            if (active) activeZoneTransparencyPercent else zoneTransparencyPercent,
        )
        val strokeTransparency = max(0, fillTransparency - STROKE_TRANSPARENCY_OFFSET_PERCENT)
        return Coverage(
            withTransparency(baseColor, fillTransparency),
            withTransparency(baseColor, strokeTransparency),
        )
    }

    private fun withTransparency(baseColor: Int, transparencyPercent: Int): Int {
        val alpha = (255f * (100 - transparencyPercent) / 100f).roundToInt()
        return alpha shl 24 or (baseColor and 0x00FFFFFF)
    }

    class Coverage internal constructor(val fillColor: Int, val strokeColor: Int)
}
