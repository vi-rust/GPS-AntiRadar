package ru.gpsantiradar.app

import org.junit.Assert.assertEquals
import org.junit.Test

class RouteHitTestTest {
    @Test
    fun pointOnSegmentHasZeroDistance() {
        assertEquals(
            0f,
            RouteHitTest.distanceSquared(5f, 0f, 0f, 0f, 10f, 0f),
            0.001f,
        )
    }

    @Test
    fun distanceIsMeasuredToSegment() {
        assertEquals(
            16f,
            RouteHitTest.distanceSquared(5f, 4f, 0f, 0f, 10f, 0f),
            0.001f,
        )
    }

    @Test
    fun distanceClampsToNearestEndpoint() {
        assertEquals(
            41f,
            RouteHitTest.distanceSquared(15f, 4f, 0f, 0f, 10f, 0f),
            0.001f,
        )
    }
}
