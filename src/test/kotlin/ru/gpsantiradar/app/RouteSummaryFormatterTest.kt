package ru.gpsantiradar.app

import org.junit.Assert.assertEquals
import org.junit.Test

class RouteSummaryFormatterTest {
    @Test
    fun joinsDistanceAndDuration() {
        assertEquals(
            "12 km · 18 min",
            RouteSummaryFormatter.format("12 km", "18 min"),
        )
    }

    @Test
    fun ignoresMissingValues() {
        assertEquals("18 min", RouteSummaryFormatter.format("", "18 min"))
        assertEquals("12 km", RouteSummaryFormatter.format("12 km", ""))
    }

    @Test
    fun trimsLocalizedValues() {
        assertEquals(
            "12 km · 18 min",
            RouteSummaryFormatter.format(" 12 km ", " 18 min "),
        )
    }
}
