package ru.gpsantiradar.app;

import java.util.List;

/** Pure screen-space hit testing used by projected Android Auto map clicks. */
public final class MapMarkerHitTest {
    private MapMarkerHitTest() {}

    public static CameraPoint nearest(float tapX, float tapY, float radius,
                                      List<Candidate> candidates) {
        if (!Float.isFinite(tapX) || !Float.isFinite(tapY)
                || !Float.isFinite(radius) || radius < 0f
                || candidates == null || candidates.isEmpty()) {
            return null;
        }
        float maximumSquared = radius * radius;
        float bestSquared = maximumSquared;
        CameraPoint best = null;
        for (Candidate candidate : candidates) {
            if (candidate == null || candidate.camera == null
                    || !Float.isFinite(candidate.x) || !Float.isFinite(candidate.y)) {
                continue;
            }
            float dx = candidate.x - tapX;
            float dy = candidate.y - tapY;
            float squared = dx * dx + dy * dy;
            if (squared > maximumSquared) continue;
            if (best == null || squared < bestSquared
                    || squared == bestSquared && candidate.camera.id < best.id) {
                best = candidate.camera;
                bestSquared = squared;
            }
        }
        return best;
    }

    public static final class Candidate {
        public final CameraPoint camera;
        public final float x;
        public final float y;

        public Candidate(CameraPoint camera, float x, float y) {
            this.camera = camera;
            this.x = x;
            this.y = y;
        }
    }
}
