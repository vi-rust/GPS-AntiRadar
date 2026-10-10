package ru.gpsantiradar.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsSignalFilterTest {
    @Test
    fun stationaryAccuracyNoiseDoesNotMoveSharedPosition() {
        val filter = GpsSignalFilter()
        val first = filter.update(55.0, 56.0, 8f, true, 0.4f, 0.5f, 90f, 1_000L)
        val second = filter.update(55.00003, 56.00003, 8f, true, 0.4f, 0.5f, 270f, 2_000L)

        assertEquals(first.latitude, second.latitude, 0.0)
        assertEquals(first.longitude, second.longitude, 0.0)
        assertEquals(0f, second.speedKmh, 0f)
        assertTrue(second.headingDegrees.isNaN())
    }

    @Test
    fun reliableMovementUpdatesPositionSpeedAndHeadingTogether() {
        val filter = GpsSignalFilter()
        filter.update(55.0, 56.0, 5f, true, 0f, 0.5f, null, 1_000L)
        val moved = filter.update(55.0, 56.00030, 5f, true, 20f, 0.5f, 90f, 2_000L)
        val movement = Geo.distanceMeters(55.0, 56.0, moved.latitude, moved.longitude)

        assertTrue(movement > 15.0)
        assertTrue(moved.speedKmh > 70f)
        assertEquals(90f, moved.headingDegrees, 0.01f)
    }

    @Test
    fun singleImplausibleJumpIsRejectedForEveryConsumer() {
        val filter = GpsSignalFilter()
        val first = filter.update(55.0, 56.0, 5f, true, 0f, 0.5f, null, 1_000L)
        val jumped = filter.update(55.01, 56.01, 5f, true, 0f, 0.5f, 180f, 2_000L)

        assertEquals(first.latitude, jumped.latitude, 0.0)
        assertEquals(first.longitude, jumped.longitude, 0.0)
        assertEquals(0f, jumped.speedKmh, 0f)
    }
}
