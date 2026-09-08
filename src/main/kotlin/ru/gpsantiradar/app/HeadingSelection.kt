package ru.gpsantiradar.app

/** Keeps the immediate visual course independent from alert-direction smoothing. */
class HeadingSelection private constructor(
    val visualHeading: Float,
    val alertHeading: Float
) {
    companion object {
        fun forStrelka(
            gpsHeading: Float, radarHeading: Float, proposedRadarHeading: Float,
            scanRequested: Boolean, hasPreviousRadarFix: Boolean
        ): HeadingSelection = HeadingSelection(
            gpsHeading,
            when {
                scanRequested && hasPreviousRadarFix -> proposedRadarHeading
                !radarHeading.isNaN() -> radarHeading
                else -> gpsHeading
            }
        )
    }
}
