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

    override fun onCreate() {
        super.onCreate()
        radarBaseUpdater = RadarBaseUpdater(this, radarBaseUpdateGuard)
        radarBaseUpdater.requestUpdate()
    }
    fun acquireMapKit() = mapKitLifecycle.acquire()
    fun releaseMapKit() = mapKitLifecycle.release()
    fun radarBaseUpdater(): RadarBaseUpdater = radarBaseUpdater

    companion object {
        private var initialized = false
        @Synchronized fun ensureMapKit(context: Context): Boolean {
            if (initialized) return true
            var key = BuildConfig.MAPKIT_API_KEY?.trim().orEmpty()
            if (key.isEmpty()) key = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getString("yandex_mapkit_key", "")?.trim().orEmpty()
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
