package ru.gpsantiradar.app

class MapKitLifecycle(private val delegate: Delegate) {
    interface Delegate { fun onStart(); fun onStop() }
    private var owners = 0

    @Synchronized fun acquire() { if (owners++ == 0) delegate.onStart() }
    @Synchronized fun release() {
        if (owners == 0) return
        if (--owners == 0) delegate.onStop()
    }
}
