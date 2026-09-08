package ru.gpsantiradar.app

class ProcessLaunchGuard {
    private var claimed = false
    @Synchronized fun claim(): Boolean {
        if (claimed) return false
        claimed = true
        return true
    }
}
