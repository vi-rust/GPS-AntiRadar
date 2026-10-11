package ru.gpsantiradar.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadarBaseUpdateNotificationGateTest {
    @Test
    fun replayedStateIsShownOnlyOnce() {
        val gate = RadarBaseUpdateNotificationGate()
        val success = state(7L, RadarBaseUpdateState.Status.SUCCESS)

        assertTrue(gate.shouldShow(success))
        assertFalse(gate.shouldShow(success))
    }

    @Test
    fun everyNewUpdateStateIsShown() {
        val gate = RadarBaseUpdateNotificationGate()

        assertTrue(gate.shouldShow(state(1L, RadarBaseUpdateState.Status.STARTED)))
        assertTrue(gate.shouldShow(state(2L, RadarBaseUpdateState.Status.SUCCESS)))
    }

    @Test
    fun idleStateIsNeverShown() {
        val gate = RadarBaseUpdateNotificationGate()

        assertFalse(gate.shouldShow(state(0L, RadarBaseUpdateState.Status.IDLE)))
    }

    private fun state(sequence: Long, status: RadarBaseUpdateState.Status) =
        RadarBaseUpdateState(sequence, status, 0, false, status.name)
}
