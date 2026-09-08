package ru.gpsantiradar.app

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

class RadarBaseUpdater(context: Context, private val gate: RadarBaseUpdateSingleFlight) {
    interface Listener : RadarBaseUpdateListenerRegistry.Listener
    private val context = context.applicationContext
    private val listenerRegistry: RadarBaseUpdateListenerRegistry

    init {
        val mainHandler = Handler(Looper.getMainLooper())
        listenerRegistry = RadarBaseUpdateListenerRegistry { callback -> mainHandler.post(callback) }
    }

    fun requestUpdate() {
        if (!gate.tryStart()) {
            publish(RadarBaseUpdateState.Status.ALREADY_RUNNING, 0, false, "Обновление базы RadarBase уже выполняется")
            return
        }
        publish(RadarBaseUpdateState.Status.STARTED, 0, false, "Обновление базы RadarBase начато")
        Thread(::updateInBackground, "radarbase-download").start()
    }
    fun latestState() = listenerRegistry.latestState()
    fun addListener(listener: Listener?, replayLatest: Boolean) = listenerRegistry.addListener(listener, replayLatest)
    fun removeListener(listener: Listener?) = listenerRegistry.removeListener(listener)

    private fun updateInBackground() {
        var connection: HttpURLConnection? = null
        try {
            val preferences = context.getSharedPreferences(AppSettings.PREFERENCES, Context.MODE_PRIVATE)
            prepareMigration(preferences)
            connection = URL(RADARBASE_URL).openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 120000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Accept-Encoding", "gzip")
            preferences.getString(RADARBASE_ETAG, "")?.takeIf { it.isNotEmpty() }?.let { connection.setRequestProperty("If-None-Match", it) }
            preferences.getString(RADARBASE_MODIFIED, "")?.takeIf { it.isNotEmpty() }?.let { connection.setRequestProperty("If-Modified-Since", it) }
            val status = connection.responseCode
            if (status == HttpURLConnection.HTTP_NOT_MODIFIED) {
                publish(RadarBaseUpdateState.Status.UNCHANGED, 0, false, "База RadarBase уже актуальна")
                return
            }
            if (status != HttpURLConnection.HTTP_OK) throw IOException("Сервер RadarBase: HTTP $status")
            val raw = connection.inputStream
            val input = if (connection.contentEncoding.equals("gzip", true)) GZIPInputStream(raw) else raw
            val result = input.use { source -> CameraDatabase(context).use { it.importRadarBase(source) } }
            preferences.edit().apply {
                connection.getHeaderField("ETag")?.let { putString(RADARBASE_ETAG, it) }
                connection.getHeaderField("Last-Modified")?.let { putString(RADARBASE_MODIFIED, it) }
                putBoolean(RADARBASE_COORDINATE_FIX, true)
                putInt(RADARBASE_IMPORT_FORMAT, CURRENT_RADARBASE_IMPORT_FORMAT)
                putLong(AppSettings.RADARBASE_LAST_SUCCESSFUL_DOWNLOAD, System.currentTimeMillis())
            }.apply()
            context.sendBroadcast(Intent(ACTION_DATABASE_UPDATED).setPackage(context.packageName))
            val suffix = if (result.coordinatesCorrected) " · координаты восстановлены" else " · без поправки координат"
            publish(RadarBaseUpdateState.Status.SUCCESS, result.count, result.coordinatesCorrected, "Импортировано: ${result.count}$suffix")
        } catch (error: Exception) {
            publish(RadarBaseUpdateState.Status.ERROR, 0, false,
                error.message?.takeIf { it.isNotBlank() } ?: "Не удалось загрузить JSON RadarBase")
        } finally {
            connection?.disconnect()
            gate.finish()
        }
    }

    private fun publish(status: RadarBaseUpdateState.Status, importedCount: Int, coordinatesCorrected: Boolean, message: String) {
        listenerRegistry.publish(status, importedCount, coordinatesCorrected, message)
    }

    companion object {
        const val ACTION_DATABASE_UPDATED = "ru.gpsantiradar.app.RADARBASE_UPDATED"
        private const val RADARBASE_URL = "https://radarbase.info/export/cache/RU/main_extended.json"
        private const val RADARBASE_ETAG = "radarbase_etag"
        private const val RADARBASE_MODIFIED = "radarbase_modified"
        private const val RADARBASE_COORDINATE_FIX = "radarbase_coordinate_fix_v1"
        private const val RADARBASE_IMPORT_FORMAT = "radarbase_import_format"
        private const val CURRENT_RADARBASE_IMPORT_FORMAT = 2
        private fun prepareMigration(preferences: SharedPreferences) {
            val coordinatesReady = preferences.getBoolean(RADARBASE_COORDINATE_FIX, false)
            val importReady = preferences.getInt(RADARBASE_IMPORT_FORMAT, 0) >= CURRENT_RADARBASE_IMPORT_FORMAT
            if (!coordinatesReady || !importReady) preferences.edit().remove(RADARBASE_ETAG).remove(RADARBASE_MODIFIED).commit()
        }
    }
}
