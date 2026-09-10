package ru.gpsantiradar.app

/** Stateful approach confirmation and re-alert behavior reconstructed from Strelka HUD. */
class StrelkaAlertTracker {
    private val states = HashMap<Long, State>()

    fun update(observations: List<Observation>, speedKmh: Float, gpsRecovered: Boolean): Update {
        states.values.forEach { it.observed = false }
        for (observation in observations) {
            var state = states[observation.`object`.id]
            var created = false
            if (state == null && observation.matchesZone) {
                state = State(observation.`object`)
                states[observation.`object`.id] = state
                created = true
            }
            if (state == null) continue
            state.observed = true
            state.distanceMeters = observation.distanceMeters
            state.activationDistance = observation.activationDistance
            state.confidence = if (observation.dropImmediately) 0f else StrelkaAlertAlgorithm.updateConfidence(
                state.confidence, speedKmh, observation.matchesZone, gpsRecovered && created
            )
            if (!state.active && StrelkaAlertAlgorithm.shouldActivate(state.confidence, state.distanceMeters)) state.active = true
        }
        val exited = ArrayList<State>()
        val iterator = states.values.iterator()
        while (iterator.hasNext()) {
            val state = iterator.next()
            if (!state.observed || state.confidence <= 0f) {
                if (state.active) exited += state
                iterator.remove()
            }
        }
        return summarize(exited)
    }

    fun snapshot() = summarize(ArrayList())
    private fun summarize(exited: MutableList<State>): Update {
        val ordered = states.values.sortedWith(nearestFirst)
        exited.sortWith(nearestFirst)
        var closestActive: State? = null
        var pendingVoice: State? = null
        val closestTracking = ordered.firstOrNull()
        val active = ArrayList<State>()
        for (state in ordered) {
            if (!state.active) continue
            active += state
            if (closestActive == null) closestActive = state
            if (!state.spoken && pendingVoice == null) pendingVoice = state
        }
        return Update(exited, closestActive, pendingVoice, closestTracking, active)
    }

    fun markSpoken(objectId: Long) { states[objectId]?.spoken = true }
    fun clear() = states.clear()

    class Observation(
        val `object`: CameraPoint,
        val distanceMeters: Int,
        val activationDistance: Int,
        val matchesZone: Boolean,
        val dropImmediately: Boolean
    )

    class State internal constructor(val `object`: CameraPoint) {
        var confidence = 0f
        var active = false
        var spoken = false
        var observed = false
        var distanceMeters = 0
        var activationDistance = 0
    }

    class Update internal constructor(
        exited: List<State>,
        val closestActive: State?,
        val pendingVoice: State?,
        val closestTracking: State?,
        active: List<State>
    ) {
        val exited = exited.toList()
        private val active = active.toList()
        val activeCount = active.size
        val activeCameraIds: LongArray = active.map { it.`object`.id }.toLongArray()
        fun overspeedCandidate(speedKmh: Float) = overspeedCandidate(speedKmh, AppSettings.DEFAULT_OVERSPEED_THRESHOLD_KMH)
        fun overspeedCandidate(speedKmh: Float, thresholdKmh: Int): State? =
            active.firstOrNull { it.spoken && StrelkaAlertAlgorithm.isOverspeeding(it.`object`, speedKmh, thresholdKmh) }
    }

    companion object {
        private val nearestFirst = compareBy<State> { it.distanceMeters }.thenBy { it.`object`.id }
    }
}
