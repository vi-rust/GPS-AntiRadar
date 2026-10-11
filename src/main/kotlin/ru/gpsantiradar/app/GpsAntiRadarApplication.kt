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
    private val phoneRadarBaseNotificationGate = RadarBaseUpdateNotificationGate()
    private lateinit var radarBaseUpdater: RadarBaseUpdater
    private val routeManager = RouteManager()

    override fun onCreate() {
        super.onCreate()
        radarBaseUpdater = RadarBaseUpdater(this, radarBaseUpdateGuard)
        val lastSuccessfulDownload = getSharedPreferences(
            AppSettings.PREFERENCES,
            MODE_PRIVATE,
        ).getLong(AppSettings.RADARBASE_LAST_SUCCESSFUL_DOWNLOAD, 0L)
        if (RadarBaseUpdatePolicy.shouldRequestUpdateOnApplicationStart(
                lastSuccessfulDownload,
                System.currentTimeMillis(),
            )
        ) {
            radarBaseUpdater.requestUpdate()
        }
    }
    fun acquireMapKit() = mapKitLifecycle.acquire()
    fun releaseMapKit() = mapKitLifecycle.release()
    fun radarBaseUpdater(): RadarBaseUpdater = radarBaseUpdater
    fun shouldShowPhoneRadarBaseUpdate(state: RadarBaseUpdateState): Boolean =
        phoneRadarBaseNotificationGate.shouldShow(state)
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
