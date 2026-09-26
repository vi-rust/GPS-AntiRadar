package ru.gpsantiradar.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

class TrackingService : Service(), LocationListener {
    private lateinit var locationManager: LocationManager
    private lateinit var database: CameraDatabase
    private lateinit var soundPlayer: StrelkaSoundPlayer
    private var previousGps: Location? = null
    private var lastRadarScan: Location? = null
    private var radarHeading = Float.NaN
    private val alertTracker = StrelkaAlertTracker()
    private val radarScanGate = RadarScanGate()

    override fun onCreate() {
        super.onCreate()
        database = CameraDatabase(this)
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        soundPlayer = StrelkaSoundPlayer(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTracking()
            return START_NOT_STICKY
        }
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification("Ожидание сигнала GPS"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification("Ожидание сигнала GPS"))
        }
        startTracking()
        return START_STICKY
    }

    private fun startTracking() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            stopSelf()
            return
        }
        locationManager.removeUpdates(this)
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this)
        } catch (_: RuntimeException) {
        }
        try {
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000L, 5f, this)
        } catch (_: RuntimeException) {
        }
    }

    override fun onLocationChanged(location: Location) {
        onStrelkaLocationChanged(location)
    }

    private fun onStrelkaLocationChanged(location: Location) {
        val gpsLocation = LocationManager.GPS_PROVIDER == location.provider
        if (!gpsLocation && hasRecentGpsFix()) return

        val speedKmh = speed(location)
        val visualHeading = heading(location)
        val previousScan = lastRadarScan
        val movementSinceScan = if (previousScan == null) {
            Double.MAX_VALUE
        } else {
            Geo.distanceMeters(
                previousScan.latitude,
                previousScan.longitude,
                location.latitude,
                location.longitude,
            )
        }
        val now = SystemClock.elapsedRealtime()
        val scanDecision = radarScanGate.assess(
            now,
            location.hasAccuracy(),
            location.accuracy,
            movementSinceScan,
        )
        var proposedRadarHeading = radarHeading
        if (scanDecision.scanRequested && previousScan != null) {
            val measuredHeading = Geo.bearing(
                previousScan.latitude,
                previousScan.longitude,
                location.latitude,
                location.longitude,
            )
            proposedRadarHeading = if (radarHeading.isNaN()) {
                measuredHeading
            } else {
                averageHeading(radarHeading, measuredHeading)
            }
        }
        val headings = HeadingSelection.forStrelka(
            visualHeading,
            radarHeading,
            proposedRadarHeading,
            scanDecision.scanRequested,
            previousScan != null,
        )
        val alertHeading = headings.alertHeading
        val overspeedThresholdKmh = AppSettings.clampOverspeedThreshold(
            getSharedPreferences(AppSettings.PREFERENCES, MODE_PRIVATE).getInt(
                AppSettings.OVERSPEED_THRESHOLD,
                AppSettings.DEFAULT_OVERSPEED_THRESHOLD_KMH,
            ),
        )
        var nearest: CameraPoint? = null
        var nearestDistance = Double.MAX_VALUE
        var nearestAlertDistance = 0

        val observations = ArrayList<StrelkaAlertTracker.Observation>()
        var candidates = database.nearby(
            location.latitude,
            location.longitude,
            StrelkaAlertAlgorithm.SEARCH_RADIUS_METERS.toDouble(),
        )
        val candidatesUnavailable = candidates == null
        val scanPerformed = scanDecision.scanRequested && !candidatesUnavailable
        if (candidates == null) candidates = emptyList()
        for (`object` in candidates) {
            val alertDistance = StrelkaAlertAlgorithm.activationDistance(`object`)
            if (alertDistance == 0) continue
            val distance = Geo.distanceMeters(
                location.latitude,
                location.longitude,
                `object`.latitude,
                `object`.longitude,
            )
            if (distance > StrelkaAlertAlgorithm.SEARCH_RADIUS_METERS) continue
            val bearing = Geo.bearing(
                location.latitude,
                location.longitude,
                `object`.latitude,
                `object`.longitude,
            )
            val matchesZone = StrelkaAlertAlgorithm.matchesZone(
                `object`,
                distance,
                alertHeading,
                bearing,
            )
            val dropImmediately = StrelkaAlertAlgorithm.mustDropImmediately(
                `object`,
                speedKmh,
                distance,
                alertHeading,
                bearing,
            )
            observations.add(
                StrelkaAlertTracker.Observation(
                    `object`,
                    distance.roundToInt(),
                    alertDistance,
                    matchesZone,
                    dropImmediately,
                ),
            )

            val relevant = !dropImmediately &&
                (matchesZone || StrelkaAlertAlgorithm.matchesDirectionForAcquisition(`object`, alertHeading))
            if (relevant && distance < nearestDistance) {
                nearest = `object`
                nearestDistance = distance
                nearestAlertDistance = alertDistance
            }
        }

        val alertUpdate: StrelkaAlertTracker.Update
        if (scanPerformed) {
            alertUpdate = alertTracker.update(observations, speedKmh, scanDecision.gpsRecovered)
            radarScanGate.accept(scanDecision)
            if (previousScan != null) radarHeading = proposedRadarHeading
            lastRadarScan = Location(location)
        } else {
            alertUpdate = alertTracker.snapshot()
        }
        if (candidatesUnavailable && alertUpdate.closestTracking != null) {
            val tracked = alertUpdate.closestTracking!!
            nearest = tracked.`object`
            nearestDistance = tracked.distanceMeters.toDouble()
            nearestAlertDistance = tracked.activationDistance
        }
        val closestActive = alertUpdate.closestActive
        if (closestActive != null) {
            nearest = closestActive.`object`
            nearestDistance = closestActive.distanceMeters.toDouble()
            nearestAlertDistance = closestActive.activationDistance
        }
        var finishedObject: CameraPoint? = null
        for (exited in alertUpdate.exited) {
            if (exited.spoken) {
                finishedObject = exited.`object`
                break
            }
        }
        if (finishedObject != null && alertUpdate.activeCount == 0) {
            soundPlayer.objectFinished(finishedObject)
        }

        val alertState = runAlertSequence(
            alertUpdate,
            speedKmh,
            overspeedThresholdKmh,
            scanPerformed,
            nearest,
            nearestDistance,
            nearestAlertDistance,
        )
        sendUpdate(
            location,
            speedKmh,
            headings.visualHeading,
            nearest,
            nearestDistance,
            nearestAlertDistance,
            alertState,
            alertUpdate.activeCameraIds,
        )

        val line = if (nearest == null) {
            "${speedKmh.roundToInt()} km/h"
        } else {
            "${nearest.typeName()} - ${formatDistance(nearestDistance)}"
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, notification(line))
        if (gpsLocation) previousGps = Location(location)
    }

    private fun runAlertSequence(
        update: StrelkaAlertTracker.Update,
        speedKmh: Float,
        overspeedThresholdKmh: Int,
        scanPerformed: Boolean,
        nearest: CameraPoint?,
        nearestDistance: Double,
        nearestAlertDistance: Int,
    ): String {
        val pending = update.pendingVoice
        if (pending != null) {
            soundPlayer.announce(pending.`object`, pending.distanceMeters, update.activeCount > 1)
            alertTracker.markSpoken(pending.`object`.id)
            return "Вход подтверждён: голос · ${pending.distanceMeters} м"
        }
        val closest = update.closestActive
        val overspeed = update.overspeedCandidate(speedKmh, overspeedThresholdKmh)
        if (overspeed != null) {
            val signaled = scanPerformed && soundPlayer.beepIfIdle(overspeed.distanceMeters)
            return "В зоне: ${overspeed.distanceMeters} м · превышение +" +
                "$overspeedThresholdKmh · ${if (signaled) "сигнал" else "ожидание сигнала"}"
        }
        if (closest != null && closest.spoken) {
            return "В зоне: ${closest.distanceMeters} м · без превышения"
        }
        val tracking = update.closestTracking
        if (tracking != null) {
            val required = (tracking.distanceMeters * 0.1f).roundToInt()
            return "Подтверждение подхода: ${tracking.confidence.roundToInt()} / $required"
        }
        if (nearest != null) {
            return "До зоны: ${nearestDistance.roundToInt()} м / $nearestAlertDistance м"
        }
        return "Поиск впереди: ${StrelkaAlertAlgorithm.SEARCH_RADIUS_METERS} м"
    }

    private fun speed(location: Location): Float {
        if (LocationManager.GPS_PROVIDER != location.provider || !location.hasSpeed()) return 0f
        val metersPerSecond = max(0f, location.speed)
        var stationaryThreshold = 0.8f
        if (location.hasSpeedAccuracy()) {
            stationaryThreshold = max(stationaryThreshold, location.speedAccuracyMetersPerSecond)
        }
        return if (metersPerSecond <= stationaryThreshold) 0f else metersPerSecond * 3.6f
    }

    private fun heading(location: Location): Float {
        if (location.hasBearing() && location.speed > 1.5f) return location.bearing
        val previous = previousGps
        if (previous != null && LocationManager.GPS_PROVIDER == location.provider) {
            return Geo.bearing(
                previous.latitude,
                previous.longitude,
                location.latitude,
                location.longitude,
            )
        }
        return 0f
    }

    private fun hasRecentGpsFix(): Boolean {
        val previous = previousGps ?: return false
        val ageNanos = SystemClock.elapsedRealtimeNanos() - previous.elapsedRealtimeNanos
        return ageNanos >= 0 && ageNanos < 10_000_000_000L
    }

    private fun formatDistance(meters: Double): String = if (meters >= 1000) {
        String.format(Locale.US, "%.1f км", meters / 1000.0)
    } else {
        "${meters.roundToInt()} м"
    }

    private fun sendUpdate(
        location: Location,
        speedKmh: Float,
        heading: Float,
        nearest: CameraPoint?,
        nearestDistance: Double,
        alertDistance: Int,
        alertState: String,
        activeCameraIds: LongArray,
    ) {
        val update = Intent(ACTION_UPDATE).setPackage(packageName)
        update.putExtra(EXTRA_SPEED, speedKmh)
        update.putExtra(EXTRA_ACCURACY, location.accuracy)
        update.putExtra(EXTRA_LATITUDE, location.latitude)
        update.putExtra(EXTRA_LONGITUDE, location.longitude)
        update.putExtra(EXTRA_HEADING, heading)
        update.putExtra(EXTRA_DISTANCE, if (nearest == null) -1 else nearestDistance.roundToInt())
        update.putExtra(EXTRA_CAMERA, nearest?.typeName() ?: "")
        update.putExtra(EXTRA_CAMERA_ID, nearest?.id ?: -1L)
        update.putExtra(
            EXTRA_LIMIT,
            if (nearest == null || nearest.isRoadObject()) 0 else nearest.currentSpeedLimit(),
        )
        update.putExtra(EXTRA_ALERT_DISTANCE, if (nearest == null) 0 else alertDistance)
        update.putExtra(EXTRA_ALERT_STATE, alertState)
        update.putExtra(EXTRA_ACTIVE_CAMERA_IDS, activeCameraIds)
        val overspeedThresholdKmh = AppSettings.clampOverspeedThreshold(
            getSharedPreferences(AppSettings.PREFERENCES, MODE_PRIVATE).getInt(
                AppSettings.OVERSPEED_THRESHOLD,
                AppSettings.DEFAULT_OVERSPEED_THRESHOLD_KMH,
            ),
        )
        update.putExtra(
            EXTRA_ALERT_ALGORITHM,
            StrelkaAlertAlgorithm.screenSummary(overspeedThresholdKmh),
        )
        sendBroadcast(update)
    }

    private fun notification(text: String): Notification {
        val open = Intent(this, MainActivity::class.java)
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val content = PendingIntent.getActivity(this, 0, open, pendingFlags)
        val builder = Notification.Builder(this, CHANNEL)
        return builder.setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("GPS AntiRadar работает")
            .setContentText(text)
            .setContentIntent(content)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL,
            "GPS-предупреждения",
            NotificationManager.IMPORTANCE_LOW,
        )
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun stopTracking() {
        try {
            locationManager.removeUpdates(this)
        } catch (_: RuntimeException) {
        }
        sendBroadcast(Intent(ACTION_STOPPED).setPackage(packageName))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    @Deprecated("Deprecated in Android")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try {
            locationManager.removeUpdates(this)
        } catch (_: RuntimeException) {
        }
        alertTracker.clear()
        soundPlayer.release()
        database.close()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "ru.gpsantiradar.app.START"
        const val ACTION_STOP = "ru.gpsantiradar.app.STOP"
        const val ACTION_UPDATE = "ru.gpsantiradar.app.UPDATE"
        const val ACTION_STOPPED = "ru.gpsantiradar.app.STOPPED"
        const val EXTRA_SPEED = "speed"
        const val EXTRA_DISTANCE = "distance"
        const val EXTRA_CAMERA = "camera"
        const val EXTRA_CAMERA_ID = "camera_id"
        const val EXTRA_ACTIVE_CAMERA_IDS = "active_camera_ids"
        const val EXTRA_LIMIT = "limit"
        const val EXTRA_ALERT_DISTANCE = "alert_distance"
        const val EXTRA_ACCURACY = "accuracy"
        const val EXTRA_LATITUDE = "latitude"
        const val EXTRA_LONGITUDE = "longitude"
        const val EXTRA_HEADING = "heading"
        const val EXTRA_ALERT_STATE = "alert_state"
        const val EXTRA_ALERT_ALGORITHM = "alert_algorithm"

        private const val CHANNEL = "tracking"
        private const val NOTIFICATION_ID = 41

        fun requestStop(context: Context?) {
            context ?: return
            val application = context.applicationContext
            application.sendBroadcast(Intent(ACTION_STOPPED).setPackage(application.packageName))
            application.stopService(Intent(application, TrackingService::class.java))
        }

        private fun averageHeading(first: Float, second: Float): Float {
            val x = cos(Math.toRadians(first.toDouble())) + cos(Math.toRadians(second.toDouble()))
            val y = sin(Math.toRadians(first.toDouble())) + sin(Math.toRadians(second.toDouble()))
            return Geo.normalize(Math.toDegrees(atan2(y, x)).toFloat())
        }
    }
}
