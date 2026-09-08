package ru.gpsantiradar.app

import kotlin.math.max

/** Separates radar-scan eligibility from committing a successfully completed scan. */
class RadarScanGate {
    private var lastObservedFixElapsed = 0L
    private var recoveryPending = false

    fun assess(elapsedRealtime: Long, hasAccuracy: Boolean, accuracyMeters: Float, movementMeters: Double): Decision {
        val requiredMovement = if (hasAccuracy) max(3f, accuracyMeters) else 3f
        if (lastObservedFixElapsed > 0 && elapsedRealtime - lastObservedFixElapsed > GPS_RECOVERY_MILLIS) recoveryPending = true
        lastObservedFixElapsed = elapsedRealtime
        val scanRequested = requiredMovement < 60f && movementMeters > requiredMovement
        return Decision(elapsedRealtime, scanRequested, scanRequested && recoveryPending)
    }

    fun accept(decision: Decision?) {
        require(decision != null && decision.scanRequested) { "Only a requested radar scan can be accepted" }
        recoveryPending = false
    }

    class Decision internal constructor(
        val elapsedRealtime: Long,
        val scanRequested: Boolean,
        val gpsRecovered: Boolean
    )

    companion object { private const val GPS_RECOVERY_MILLIS = 5000L }
}
