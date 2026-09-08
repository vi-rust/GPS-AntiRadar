package ru.gpsantiradar.app

/** Pure screen-space hit testing used by projected Android Auto map clicks. */
object MapMarkerHitTest {
    fun nearest(tapX: Float, tapY: Float, radius: Float, candidates: List<Candidate?>?): CameraPoint? {
        if (!tapX.isFinite() || !tapY.isFinite() || !radius.isFinite() || radius < 0f || candidates.isNullOrEmpty()) return null
        val maximumSquared = radius * radius
        var bestSquared = maximumSquared
        var best: CameraPoint? = null
        for (candidate in candidates) {
            if (candidate?.camera == null || !candidate.x.isFinite() || !candidate.y.isFinite()) continue
            val dx = candidate.x - tapX
            val dy = candidate.y - tapY
            val squared = dx * dx + dy * dy
            if (squared > maximumSquared) continue
            if (best == null || squared < bestSquared || squared == bestSquared && candidate.camera.id < best.id) {
                best = candidate.camera
                bestSquared = squared
            }
        }
        return best
    }

    class Candidate(val camera: CameraPoint?, val x: Float, val y: Float)
}
