package ru.gpsantiradar.app

/** Separates radar availability from the optional Android Auto map surface. */
class CarStartupDecision private constructor(
    val startTracking: Boolean,
    val showMap: Boolean
) {
    companion object {
        fun from(locationGranted: Boolean, mapKitReady: Boolean) =
            CarStartupDecision(locationGranted, locationGranted && mapKitReady)
    }
}
