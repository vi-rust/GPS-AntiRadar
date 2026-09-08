package ru.gpsantiradar.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import androidx.car.app.Session
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

class GpsCarSession : Session() {
    private var surfaceController: CarSurfaceController? = null
    private var setupScreen: CarSetupScreen? = null
    private var updateNotifier: CarRadarBaseUpdateNotifier? = null
    private var updateReceiver: BroadcastReceiver? = null
    private var receiverRegistered = false
    private var surfaceFailureShown = false
    private var databaseLoadGeneration = 0

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                stopUpdateFeedback()
                destroyCarResources()
            }
        })
    }

    override fun onCreateScreen(intent: Intent): Screen {
        val context = carContext
        val application = context.applicationContext as GpsAntiRadarApplication
        startUpdateFeedback(application.radarBaseUpdater())
        surfaceFailureShown = false
        val locationGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val mapKitReady = GpsAntiRadarApplication.ensureMapKit(context)
        val startup = CarStartupDecision.from(locationGranted, mapKitReady)
        if (startup.startTracking) {
            context.startForegroundService(
                Intent(context, TrackingService::class.java).setAction(TrackingService.ACTION_START),
            )
        }
        if (!startup.showMap) {
            destroyCarResources()
            return CarSetupScreen(
                carContext,
                locationGranted,
                mapKitReady,
                startup.startTracking,
                null,
            ).also {
                setupScreen = it
                if (startup.startTracking) registerUpdateReceiver()
            }
        }

        destroyCarResources()
        val controller = CarSurfaceController(
            carContext,
            application,
            this::showSurfaceFailure,
        )
        surfaceController = controller
        registerUpdateReceiver()
        loadDatabaseCount()
        return CarMapScreen(carContext, controller)
    }

    private fun showSurfaceFailure(message: String) {
        if (surfaceFailureShown) return
        surfaceFailureShown = true
        carContext.getCarService(ScreenManager::class.java).push(
            CarSetupScreen(carContext, true, true, message),
        )
    }

    override fun onCarConfigurationChanged(newConfiguration: Configuration) {
        super.onCarConfigurationChanged(newConfiguration)
        surfaceController?.onCarConfigurationChanged()
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerUpdateReceiver() {
        if (receiverRegistered) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent ?: return
                val controller = surfaceController
                val setup = setupScreen
                if (TrackingService.ACTION_STOPPED == intent.action) {
                    controller?.onTrackingStopped()
                    setup?.setTrackingActive(false)
                    return
                }
                if (TrackingService.ACTION_UPDATE == intent.action && setup != null) {
                    setup.setTrackingActive(true)
                }
                controller ?: return
                when (intent.action) {
                    RadarBaseUpdater.ACTION_DATABASE_UPDATED -> {
                        controller.refreshVisible()
                        loadDatabaseCount()
                    }
                    TrackingService.ACTION_UPDATE -> {
                        controller.onDrivingSnapshot(DrivingSnapshotIntent.from(intent))
                    }
                }
            }
        }
        updateReceiver = receiver
        val filter = IntentFilter().apply {
            addAction(TrackingService.ACTION_UPDATE)
            addAction(TrackingService.ACTION_STOPPED)
            addAction(RadarBaseUpdater.ACTION_DATABASE_UPDATED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            carContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            carContext.registerReceiver(receiver, filter)
        }
        receiverRegistered = true
    }

    private fun startUpdateFeedback(updater: RadarBaseUpdater) {
        if (updateNotifier == null) {
            updateNotifier = CarRadarBaseUpdateNotifier(updater, this::showUpdateState)
        }
        updateNotifier?.start()
    }

    private fun stopUpdateFeedback() {
        updateNotifier?.stop()
        updateNotifier = null
    }

    private fun showUpdateState(state: RadarBaseUpdateState) {
        val duration = if (
            state.status == RadarBaseUpdateState.Status.STARTED ||
            state.status == RadarBaseUpdateState.Status.ALREADY_RUNNING
        ) {
            CarToast.LENGTH_SHORT
        } else {
            CarToast.LENGTH_LONG
        }
        CarToast.makeText(carContext, state.message, duration).show()
    }

    private fun loadDatabaseCount() {
        val generation = ++databaseLoadGeneration
        val context = carContext
        Thread({
            val count = try {
                CameraDatabase(context).use { it.count() }
            } catch (_: RuntimeException) {
                -1
            }
            Handler(Looper.getMainLooper()).post {
                val controller = surfaceController
                if (generation == databaseLoadGeneration && controller != null) {
                    controller.onDatabaseCount(count)
                }
            }
        }, "car-database-count").start()
    }

    private fun destroyCarResources() {
        databaseLoadGeneration++
        setupScreen = null
        if (receiverRegistered) {
            updateReceiver?.let { carContext.unregisterReceiver(it) }
            receiverRegistered = false
            updateReceiver = null
        }
        surfaceController?.destroy()
        surfaceController = null
    }
}
