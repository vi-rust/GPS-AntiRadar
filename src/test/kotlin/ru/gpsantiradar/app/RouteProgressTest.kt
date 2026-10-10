package ru.gpsantiradar.app

import com.yandex.mapkit.geometry.Point
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteProgressTest {
    @Test
    fun projectsLocationOntoClosestRouteSegment() {
        val projection = RouteProgress.closestPosition(
            listOf(Point(0.0, 0.0), Point(0.0, 0.01), Point(0.01, 0.01)),
            Point(0.0, 0.004),
        )!!

        assertEquals(0, projection.segmentIndex)
        assertEquals(0.4, projection.segmentPosition, 0.001)
        assertEquals(0.0, projection.distanceMeters, 0.01)
        assertEquals(0.004, projection.point.longitude, 0.000001)
    }

    @Test
    fun trimsCompletedPartOfRouteGeometry() {
        val remaining = RouteProgress.remainingPoints(
            listOf(Point(0.0, 0.0), Point(0.0, 0.01), Point(0.01, 0.01)),
            segmentIndex = 0,
            segmentPosition = 0.5,
        )

        assertEquals(3, remaining.size)
        assertEquals(0.005, remaining.first().longitude, 0.000001)
        assertEquals(0.01, remaining.last().latitude, 0.000001)
    }

    @Test
    fun completedVertexIsNotDuplicated() {
        val remaining = RouteProgress.remainingPoints(
            listOf(Point(0.0, 0.0), Point(0.0, 0.01), Point(0.01, 0.01)),
            segmentIndex = 0,
            segmentPosition = 1.0,
        )

        assertEquals(2, remaining.size)
        assertEquals(0.01, remaining.first().longitude, 0.000001)
    }

    @Test
    fun progressCannotMoveBackwards() {
        val behind = RouteProjection(3, 0.9, Point(0.0, 0.0), 0.0)
        val ahead = RouteProjection(5, 0.1, Point(0.0, 0.0), 0.0)

        assertFalse(RouteProgress.isAheadOrEqual(behind, 4, 0.2))
        assertTrue(RouteProgress.isAheadOrEqual(ahead, 4, 0.2))
    }

    @Test
    fun measuresForwardProgressAcrossRouteSegments() {
        val points = listOf(Point(0.0, 0.0), Point(0.0, 0.001), Point(0.001, 0.001))
        val projection = RouteProjection(1, 0.5, Point(0.0005, 0.001), 0.0)

        val distance = RouteProgress.forwardDistanceMeters(points, 0, 0.5, projection)

        assertEquals(111.2, distance, 1.0)
    }

    @Test
    fun routeProgressStepGrowsWithGpsUncertainty() {
        assertEquals(5.0, RouteRefreshPolicy.progressStepMeters(4f), 0.0)
        assertEquals(10.0, RouteRefreshPolicy.progressStepMeters(20f), 0.0)
        assertEquals(15.0, RouteRefreshPolicy.progressStepMeters(80f), 0.0)
    }

    @Test
    fun rerouteRequiresRealDeviationAndCooldown() {
        assertTrue(RouteRefreshPolicy.shouldReroute(100.0, 5f, 50_000L, 10_000L, false))
        assertFalse(RouteRefreshPolicy.shouldReroute(100.0, 5f, 20_000L, 10_000L, false))
        assertFalse(RouteRefreshPolicy.shouldReroute(100.0, 60f, 50_000L, 10_000L, false))
        assertFalse(RouteRefreshPolicy.shouldReroute(100.0, 5f, 50_000L, 10_000L, true))
    }
}
