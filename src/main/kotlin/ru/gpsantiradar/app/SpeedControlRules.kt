package ru.gpsantiradar.app

import java.util.Calendar
import java.util.Locale

/** Encodes conditional RadarBase speed controls without adding a second SQL table. */
object SpeedControlRules {
    const val CAR = 1
    const val BIKE = 2
    const val TRUCK = 4
    const val BUS = 8

    fun encode(
        speed: Int, average: Boolean, fromMinute: Int, toMinute: Int,
        weekdayMask: Int, monthMask: Int, vehicleMask: Int
    ) = "$speed,${if (average) 1 else 0},$fromMinute,$toMinute,$weekdayMask,$monthMask,$vehicleMask"

    fun currentCarLimit(encoded: String?, timeMillis: Long): Int {
        if (encoded.isNullOrEmpty()) return 0
        val now = Calendar.getInstance().apply { this.timeInMillis = timeMillis }
        return select(encoded, now, false).takeIf { it > 0 } ?: select(encoded, now, true)
    }

    private fun select(encoded: String, now: Calendar, average: Boolean): Int {
        var result = 0
        for (item in encoded.split(';')) {
            val fields = item.split(',')
            if (fields.size != 7) continue
            try {
                val speed = fields[0].toInt()
                val ruleAverage = fields[1] == "1"
                val from = fields[2].toInt()
                val to = fields[3].toInt()
                val weekdays = fields[4].toInt()
                val months = fields[5].toInt()
                val vehicles = fields[6].toInt()
                if (speed <= 0 || ruleAverage != average) continue
                if (vehicles != 0 && vehicles and CAR == 0) continue
                if (!matchesDate(now, from, to, weekdays, months)) continue
                if (result == 0 || speed < result) result = speed
            } catch (_: NumberFormatException) { }
        }
        return result
    }

    private fun matchesDate(now: Calendar, from: Int, to: Int, weekdays: Int, months: Int): Boolean {
        val monthBit = 1 shl now.get(Calendar.MONTH)
        if (months != 0 && months and monthBit == 0) return false
        val mondayFirstDay = (now.get(Calendar.DAY_OF_WEEK) + 5) % 7
        if (weekdays != 0 && weekdays and (1 shl mondayFirstDay) == 0) return false
        if (from < 0 || to < 0 || from == to) return true
        val minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        return if (from < to) minute >= from && minute < to else minute >= from || minute < to
    }

    fun vehicleMask(vehicle: String?): Int = when (vehicle?.lowercase(Locale.US)) {
        null -> 0
        "car" -> CAR
        "bike" -> BIKE
        "truck" -> TRUCK
        "bus" -> BUS
        else -> 16
    }
}
