package ru.gpsantiradar.app;

/** Separates radar-scan eligibility from committing a successfully completed scan. */
public final class RadarScanGate {
    private static final long GPS_RECOVERY_MILLIS = 5000L;
    private long lastObservedFixElapsed;
    private boolean recoveryPending;

    public Decision assess(long elapsedRealtime, boolean hasAccuracy,
                           float accuracyMeters, double movementMeters) {
        float requiredMovement = hasAccuracy ? Math.max(3f, accuracyMeters) : 3f;
        if (lastObservedFixElapsed > 0L
                && elapsedRealtime - lastObservedFixElapsed > GPS_RECOVERY_MILLIS) {
            recoveryPending = true;
        }
        lastObservedFixElapsed = elapsedRealtime;
        boolean scanRequested = requiredMovement < 60f
                && movementMeters > requiredMovement;
        return new Decision(elapsedRealtime, scanRequested,
                scanRequested && recoveryPending);
    }

    public void accept(Decision decision) {
        if (decision == null || !decision.scanRequested) {
            throw new IllegalArgumentException("Only a requested radar scan can be accepted");
        }
        recoveryPending = false;
    }

    public static final class Decision {
        final long elapsedRealtime;
        public final boolean scanRequested;
        public final boolean gpsRecovered;

        Decision(long elapsedRealtime, boolean scanRequested, boolean gpsRecovered) {
            this.elapsedRealtime = elapsedRealtime;
            this.scanRequested = scanRequested;
            this.gpsRecovered = gpsRecovered;
        }
    }
}
