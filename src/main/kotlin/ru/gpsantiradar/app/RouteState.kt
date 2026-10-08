package ru.gpsantiradar.app

import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.navigation.JamType

enum class RouteStatus {
    IDLE,
    BUILDING,
    READY,
    ERROR,
}

data class RouteSnapshot(
    val requestId: Long = 0L,
    val status: RouteStatus = RouteStatus.IDLE,
    val points: List<Point> = emptyList(),
    val jamTypes: List<JamType> = emptyList(),
    val destination: Point? = null,
    val distanceText: String = "",
    val durationText: String = "",
    val errorMessage: String = "",
)

object RouteSummaryFormatter {
    fun format(distanceText: String, durationText: String): String =
        listOf(distanceText.trim(), durationText.trim())
            .filter(String::isNotEmpty)
            .joinToString(" · ")
}
