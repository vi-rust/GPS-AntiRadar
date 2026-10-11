package ru.gpsantiradar.app

class RadarBaseUpdateNotificationGate {
    private var lastShownSequence = Long.MIN_VALUE

    @Synchronized
    fun shouldShow(state: RadarBaseUpdateState): Boolean {
        if (state.status == RadarBaseUpdateState.Status.IDLE) return false
        if (state.sequence <= lastShownSequence) return false
        lastShownSequence = state.sequence
        return true
    }
}
