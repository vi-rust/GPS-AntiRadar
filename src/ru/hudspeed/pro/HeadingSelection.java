package ru.gpsantiradar.app;

/** Keeps the immediate visual course independent from alert-direction smoothing. */
public final class HeadingSelection {
    public final float visualHeading;
    public final float alertHeading;

    private HeadingSelection(float visualHeading, float alertHeading) {
        this.visualHeading = visualHeading;
        this.alertHeading = alertHeading;
    }

    public static HeadingSelection forStrelka(float gpsHeading, float radarHeading,
                                              float proposedRadarHeading,
                                              boolean scanRequested,
                                              boolean hasPreviousRadarFix) {
        float alertHeading = gpsHeading;
        if (scanRequested && hasPreviousRadarFix) {
            alertHeading = proposedRadarHeading;
        } else if (!Float.isNaN(radarHeading)) {
            alertHeading = radarHeading;
        }
        return new HeadingSelection(gpsHeading, alertHeading);
    }
}
