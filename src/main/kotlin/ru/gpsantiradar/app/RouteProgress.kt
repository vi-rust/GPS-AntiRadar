package ru.gpsantiradar.app

import com.yandex.mapkit.geometry.Point
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt

internal data class RouteProjection(
    val segmentIndex: Int,
    val segmentPosition: Double,
    val point: Point,
    val distanceMeters: Double,
)

internal object RouteProgress {
    fun closestPosition(
        points: List<Point>,
        location: Point,
        minimumSegmentIndex: Int = 0,
        maximumSegmentIndex: Int = Int.MAX_VALUE,
    ): RouteProjection? {
        if (points.size < 2) return null
        val firstSegment = minimumSegmentIndex.coerceIn(0, points.lastIndex - 1)
        val lastSegment = maximumSegmentIndex.coerceIn(firstSegment, points.lastIndex - 1)
        val latitudeScale = 111_320.0
        val longitudeScale = latitudeScale * max(0.01, cos(Math.toRadians(location.latitude)))
        var best: RouteProjection? = null
        for (index in firstSegment..lastSegment) {
            val start = points[index]
            val end = points[index + 1]
            val ax = (start.longitude - location.longitude) * longitudeScale
            val ay = (start.latitude - location.latitude) * latitudeScale
            val bx = (end.longitude - location.longitude) * longitudeScale
            val by = (end.latitude - location.latitude) * latitudeScale
            val dx = bx - ax
            val dy = by - ay
            val lengthSquared = dx * dx + dy * dy
            val position = if (lengthSquared == 0.0) {
                0.0
            } else {
                (-(ax * dx + ay * dy) / lengthSquared).coerceIn(0.0, 1.0)
            }
            val projectedX = ax + position * dx
            val projectedY = ay + position * dy
            val distance = sqrt(projectedX * projectedX + projectedY * projectedY)
            if (best == null || distance < best.distanceMeters) {
                best = RouteProjection(
                    segmentIndex = index,
                    segmentPosition = position,
                    point = interpolate(start, end, position),
                    distanceMeters = distance,
                )
            }
        }
        return best
    }

    fun isAheadOrEqual(
        projection: RouteProjection,
        segmentIndex: Int,
        segmentPosition: Double,
    ): Boolean = projection.segmentIndex > segmentIndex ||
        projection.segmentIndex == segmentIndex && projection.segmentPosition >= segmentPosition

    fun forwardDistanceMeters(
        points: List<Point>,
        segmentIndex: Int,
        segmentPosition: Double,
        projection: RouteProjection,
    ): Double {
        if (points.size < 2 || !isAheadOrEqual(projection, segmentIndex, segmentPosition)) return 0.0
        val fromIndex = segmentIndex.coerceIn(0, points.lastIndex - 1)
        val fromPosition = segmentPosition.coerceIn(0.0, 1.0)
        val fromPoint = interpolate(points[fromIndex], points[fromIndex + 1], fromPosition)
        if (projection.segmentIndex == fromIndex) {
            return Geo.distanceMeters(
                fromPoint.latitude,
                fromPoint.longitude,
                projection.point.latitude,
                projection.point.longitude,
            )
        }
        var distance = Geo.distanceMeters(
            fromPoint.latitude,
            fromPoint.longitude,
            points[fromIndex + 1].latitude,
            points[fromIndex + 1].longitude,
        )
        for (index in fromIndex + 1 until projection.segmentIndex) {
            distance += Geo.distanceMeters(
                points[index].latitude,
                points[index].longitude,
                points[index + 1].latitude,
                points[index + 1].longitude,
            )
        }
        val targetStart = points[projection.segmentIndex]
        distance += Geo.distanceMeters(
            targetStart.latitude,
            targetStart.longitude,
            projection.point.latitude,
            projection.point.longitude,
        )
        return distance
    }

    fun remainingPoints(points: List<Point>, segmentIndex: Int, segmentPosition: Double): List<Point> {
        if (points.size < 2) return points
        val index = segmentIndex.coerceIn(0, points.lastIndex - 1)
        val position = segmentPosition.coerceIn(0.0, 1.0)
        val current = interpolate(points[index], points[index + 1], position)
        val remaining = ArrayList<Point>(points.size - index)
        remaining.add(current)
        for (tailIndex in index + 1..points.lastIndex) {
            val point = points[tailIndex]
            val previous = remaining.last()
            if (previous.latitude != point.latitude || previous.longitude != point.longitude) {
                remaining.add(point)
            }
        }
        return remaining
    }

    private fun interpolate(start: Point, end: Point, position: Double) = Point(
        start.latitude + (end.latitude - start.latitude) * position,
        start.longitude + (end.longitude - start.longitude) * position,
    )
}

internal object RouteRefreshPolicy {
    const val MAX_LOCATION_ACCURACY_METERS = 100f
    const val ROUTE_DEVIATION_METERS = 60.0
    const val REROUTE_COOLDOWN_MILLIS = 20_000L
    const val MIN_PROGRESS_UPDATE_METERS = 5.0
    const val MAX_PROGRESS_UPDATE_METERS = 15.0
    const val PROGRESS_UPDATE_ACCURACY_FACTOR = 0.5
    const val MAX_PROGRESS_SILENCE_MILLIS = 3_000L

    fun acceptsAccuracy(accuracyMeters: Float): Boolean =
        !accuracyMeters.isFinite() || accuracyMeters <= MAX_LOCATION_ACCURACY_METERS

    fun snapDistanceMeters(accuracyMeters: Float): Double = max(
        ROUTE_DEVIATION_METERS,
        if (accuracyMeters.isFinite()) accuracyMeters * 2.0 else ROUTE_DEVIATION_METERS,
    )

    fun progressStepMeters(accuracyMeters: Float): Double = if (accuracyMeters.isFinite()) {
        (accuracyMeters * PROGRESS_UPDATE_ACCURACY_FACTOR)
            .coerceIn(MIN_PROGRESS_UPDATE_METERS, MAX_PROGRESS_UPDATE_METERS)
    } else {
        MIN_PROGRESS_UPDATE_METERS
    }

    fun shouldReroute(
        distanceFromRouteMeters: Double,
        accuracyMeters: Float,
        nowMillis: Long,
        lastAttemptMillis: Long,
        requestActive: Boolean,
    ): Boolean = acceptsAccuracy(accuracyMeters) &&
        !requestActive &&
        distanceFromRouteMeters > snapDistanceMeters(accuracyMeters) &&
        (lastAttemptMillis <= 0L || nowMillis - lastAttemptMillis >= REROUTE_COOLDOWN_MILLIS)
}
