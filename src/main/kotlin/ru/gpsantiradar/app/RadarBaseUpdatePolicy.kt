package ru.gpsantiradar.app

object RadarBaseUpdatePolicy {
    const val STARTUP_UPDATE_INTERVAL_MS = 60L * 60L * 1000L

    fun shouldRequestUpdateOnApplicationStart(lastSuccessfulDownload: Long, now: Long): Boolean {
        if (lastSuccessfulDownload <= 0L) return true
        return now >= lastSuccessfulDownload &&
            now - lastSuccessfulDownload >= STARTUP_UPDATE_INTERVAL_MS
    }
}
