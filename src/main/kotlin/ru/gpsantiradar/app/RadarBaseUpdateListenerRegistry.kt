package ru.gpsantiradar.app

import java.util.IdentityHashMap

class RadarBaseUpdateListenerRegistry(private val dispatcher: Dispatcher) {
    fun interface Listener { fun onRadarBaseUpdate(state: RadarBaseUpdateState) }
    fun interface Dispatcher { fun post(callback: Runnable) }
    private class Registration(val token: Long, val listener: Listener) { var lastDeliveredSequence = Long.MIN_VALUE }
    private val registrations = IdentityHashMap<Listener, Registration>()
    @Volatile private var latestState = RadarBaseUpdateState(0, RadarBaseUpdateState.Status.IDLE, 0, false, "")
    private var sequence = 0L
    private var nextRegistrationToken = 0L

    fun latestState() = latestState
    @Synchronized fun addListener(listener: Listener?, replayLatest: Boolean) {
        if (listener == null) return
        val registration = Registration(++nextRegistrationToken, listener)
        registrations[listener] = registration
        if (replayLatest) enqueue(registration, latestState)
    }
    @Synchronized fun removeListener(listener: Listener?) { registrations.remove(listener) }
    @Synchronized fun publish(
        status: RadarBaseUpdateState.Status, importedCount: Int, coordinatesCorrected: Boolean, message: String?
    ): RadarBaseUpdateState {
        val state = RadarBaseUpdateState(++sequence, status, importedCount, coordinatesCorrected, message)
        latestState = state
        registrations.values.toList().forEach { enqueue(it, state) }
        return state
    }
    private fun enqueue(registration: Registration, state: RadarBaseUpdateState) =
        dispatcher.post { deliver(registration, state) }
    private fun deliver(registration: Registration, state: RadarBaseUpdateState) {
        val listener: Listener
        synchronized(this) {
            val current = registrations[registration.listener]
            if (current !== registration || current.token != registration.token || state.sequence <= current.lastDeliveredSequence) return
            current.lastDeliveredSequence = state.sequence
            listener = current.listener
        }
        listener.onRadarBaseUpdate(state)
    }
}
