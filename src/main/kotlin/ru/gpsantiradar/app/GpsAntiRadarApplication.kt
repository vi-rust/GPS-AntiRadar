package ru.gpsantiradar.app

import android.app.Application
import android.content.Context
import com.yandex.mapkit.MapKitFactory

class GpsAntiRadarApplication : Application() {
    private val mapKitLifecycle = MapKitLifecycle(object : MapKitLifecycle.Delegate {
        override fun onStart() = MapKitFactory.getInstance().onStart()
        override fun onStop() = MapKitFactory.getInstance().onStop()
    })
    private val radarBaseUpdateGuard = RadarBaseUpdateSingleFlight()
    private lateinit var radarBaseUpdater: RadarBaseUpdater
    private val routeManager = RouteManager()

    override fun onCreate() {
        super.onCreate()
        radarBaseUpdater = RadarBaseUpdater(this, radarBaseUpdateGuard)
        radarBaseUpdater.requestUpdate()
    }
    fun acquireMapKit() = mapKitLifecycle.acquire()
    fun releaseMapKit() = mapKitLifecycle.release()
    fun radarBaseUpdater(): RadarBaseUpdater = radarBaseUpdater
    fun routeManager(): RouteManager = routeManager

    companion object {
        private var initialized = false
        @Synchronized fun ensureMapKit(context: Context): Boolean {
            if (initialized) return true
            val key = BuildConfig.MAPKIT_API_KEY.trim()
            if (key.isEmpty()) return false
            return try {
                MapKitFactory.setApiKey(key)
                MapKitFactory.initialize(context.applicationContext)
                initialized = true
                true
            } catch (_: Throwable) { false }
        }
    }
}
