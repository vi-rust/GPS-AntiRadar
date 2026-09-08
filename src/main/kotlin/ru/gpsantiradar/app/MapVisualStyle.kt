package ru.gpsantiradar.app

/** Pure color decisions shared by camera coverage and the current-location marker. */
object MapVisualStyle {
    val LOCATION_PRIMARY_COLOR: Int = 0xFF1976D2.toInt()
    val LOCATION_HIGHLIGHT_COLOR: Int = 0xFF64B5F6.toInt()
    private val nightLocationPrimaryColor = 0xFFFFBE00.toInt()
    private val nightLocationHighlightColor = 0xFFFFD848.toInt()
    private const val NORMAL_FILL_ALPHA = 0x26
    private const val NORMAL_STROKE_ALPHA = 0x4D
    private const val ACTIVE_FILL_ALPHA = 0x4D
    private const val ACTIVE_STROKE_ALPHA = 0x73

    fun locationPrimaryColor(nightMode: Boolean) =
        if (nightMode) nightLocationPrimaryColor else LOCATION_PRIMARY_COLOR

    fun locationHighlightColor(nightMode: Boolean) =
        if (nightMode) nightLocationHighlightColor else LOCATION_HIGHLIGHT_COLOR

    fun coverage(baseColor: Int, cameraId: Long, activeCameraId: Long): Coverage {
        val active = cameraId >= 0L && cameraId == activeCameraId
        return Coverage(
            argb(baseColor, if (active) ACTIVE_FILL_ALPHA else NORMAL_FILL_ALPHA),
            argb(baseColor, if (active) ACTIVE_STROKE_ALPHA else NORMAL_STROKE_ALPHA)
        )
    }

    private fun argb(baseColor: Int, alpha: Int) = alpha shl 24 or (baseColor and 0x00FFFFFF)

    class Coverage internal constructor(val fillColor: Int, val strokeColor: Int)
}
