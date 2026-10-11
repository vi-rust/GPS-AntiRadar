package ru.gpsantiradar.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadarBaseUpdatePolicyTest {
    @Test
    fun startupUpdateStartsWhenThereWasNoSuccessfulDownload() {
        assertTrue(RadarBaseUpdatePolicy.shouldRequestUpdateOnApplicationStart(0L, 10_000L))
    }

    @Test
    fun startupUpdateWaitsForAFullHour() {
        val lastDownload = 10_000L
        val interval = RadarBaseUpdatePolicy.STARTUP_UPDATE_INTERVAL_MS

        assertFalse(
            RadarBaseUpdatePolicy.shouldRequestUpdateOnApplicationStart(
                lastDownload,
                lastDownload + interval - 1L,
            ),
        )
        assertTrue(
            RadarBaseUpdatePolicy.shouldRequestUpdateOnApplicationStart(
                lastDownload,
                lastDownload + interval,
            ),
        )
    }

    @Test
    fun futureTimestampDoesNotTriggerAnEarlyStartupUpdate() {
        assertFalse(RadarBaseUpdatePolicy.shouldRequestUpdateOnApplicationStart(20_000L, 10_000L))
    }
}
