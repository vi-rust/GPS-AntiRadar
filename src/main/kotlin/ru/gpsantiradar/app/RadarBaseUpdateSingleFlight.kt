package ru.gpsantiradar.app

import java.util.concurrent.atomic.AtomicBoolean

class RadarBaseUpdateSingleFlight {
    private val running = AtomicBoolean()
    fun tryStart() = running.compareAndSet(false, true)
    fun finish() = running.set(false)
}
