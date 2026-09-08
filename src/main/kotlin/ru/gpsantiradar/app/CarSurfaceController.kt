package ru.gpsantiradar.app

import android.app.Presentation
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.util.Log
import android.view.Surface
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer

class CarSurfaceController internal constructor(
    carContext: CarContext?,
    @Suppress("UNUSED_PARAMETER") application: GpsAntiRadarApplication?,
    private val surfaceFactory: SurfaceFactory,
    private val surfaceReleaser: SurfaceReleaser,
    private val failureListener: FailureListener,
) : SurfaceCallback {
    fun interface SurfaceFactory {
        fun create(spec: CarSurfaceSpec, surface: Surface?): SurfaceResource?
    }

    fun interface SurfaceReleaser {
        fun release(surface: Surface)
    }

    fun interface FailureListener {
        fun onSurfaceFailure(message: String)
    }

    interface SurfaceResource {
        fun onDrivingSnapshot(snapshot: DrivingSnapshot)
        fun onDatabaseCount(count: Int) {}
        fun onTrackingStopped() {}
        fun refreshVisible()
        fun zoomBy(delta: Float)
        fun recenter()
        fun setPanMode(enabled: Boolean)
        fun onStableAreaChanged(area: Rect?)
        fun onVisibleAreaChanged(area: Rect?)
        fun onCarConfigurationChanged()
        fun onScroll(distanceX: Float, distanceY: Float)
        fun onFling(velocityX: Float, velocityY: Float)
        fun onScale(focusX: Float, focusY: Float, scaleFactor: Float)
        fun onClick(x: Float, y: Float)
        fun release()
    }

    private val appManager: AppManager
    private var surfaceResource: SurfaceResource? = null
    private var activeSurface: Surface? = null
    private var activeSpec: CarSurfaceSpec? = null
    private var latestSnapshot = DrivingSnapshot.idle()
    private var latestDatabaseCount = -1
    private var trackingStopped = false
    private var panMode = false
    private var destroyed = false

    constructor(carContext: CarContext?, application: GpsAntiRadarApplication?) : this(
        carContext,
        application,
        AndroidSurfaceFactory(carContext, application),
        SurfaceReleaser { it.release() },
        FailureListener {},
    )

    constructor(
        carContext: CarContext?,
        application: GpsAntiRadarApplication?,
        failureListener: FailureListener,
    ) : this(
        carContext,
        application,
        AndroidSurfaceFactory(carContext, application),
        SurfaceReleaser { it.release() },
        failureListener,
    )

    internal constructor(
        carContext: CarContext?,
        application: GpsAntiRadarApplication?,
        surfaceFactory: SurfaceFactory,
    ) : this(
        carContext,
        application,
        surfaceFactory,
        SurfaceReleaser { it.release() },
        FailureListener {},
    )

    init {
        val context = requireNotNull(carContext) { "carContext is required" }
        requireNotNull(surfaceFactory) { "surfaceFactory is required" }
        requireNotNull(surfaceReleaser) { "surfaceReleaser is required" }
        requireNotNull(failureListener) { "failureListener is required" }
        appManager = context.getCarService(AppManager::class.java)
        appManager.setSurfaceCallback(this)
    }

    @Synchronized
    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        val spec = CarSurfaceSpec.from(
            surfaceContainer.width,
            surfaceContainer.height,
            surfaceContainer.dpi,
        )
        val surface = surfaceContainer.surface
        val sameWrapper = surface != null && surfaceResource != null && activeSurface === surface
        if (sameWrapper) {
            if (!spec.isUsable()) {
                releaseResource(true)
                return
            }
            if (spec == activeSpec) {
                clearActiveAreas()
                return
            }
            releaseResource(false)
        } else {
            releaseResource(true)
            if (destroyed || !spec.isUsable()) {
                releaseSurfaceObject(surface)
                return
            }
        }
        try {
            val created = surfaceFactory.create(spec, surface)
                ?: throw IllegalStateException("Surface factory returned no resource")
            surfaceResource = created
            activeSurface = surface
            activeSpec = spec
            created.onCarConfigurationChanged()
            created.setPanMode(panMode)
            created.onDrivingSnapshot(latestSnapshot)
            if (latestDatabaseCount >= 0) created.onDatabaseCount(latestDatabaseCount)
            if (trackingStopped) created.onTrackingStopped()
        } catch (error: RuntimeException) {
            handleSurfaceCreationFailure(surface, error)
        } catch (error: LinkageError) {
            handleSurfaceCreationFailure(surface, error)
        }
    }

    private fun handleSurfaceCreationFailure(surface: Surface?, error: Throwable) {
        if (surfaceResource != null && activeSurface === surface) {
            releaseResource(true)
        } else {
            releaseSurfaceObject(surface)
        }
        notifySurfaceFailure(error)
    }

    @Synchronized
    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        val callbackSurface = surfaceContainer.surface
        val callbackSpec = CarSurfaceSpec.from(
            surfaceContainer.width,
            surfaceContainer.height,
            surfaceContainer.dpi,
        )
        if (surfaceResource != null && activeSpec != null && activeSpec != callbackSpec) {
            releaseSurfaceObject(callbackSurface)
            return
        }
        val ownedSurface = activeSurface
        releaseResource(true)
        if (callbackSurface !== ownedSurface) releaseSurfaceObject(callbackSurface)
    }

    @Synchronized
    override fun onStableAreaChanged(area: Rect) {
        surfaceResource?.onStableAreaChanged(if (area.isEmpty) null else Rect(area))
    }

    @Synchronized
    override fun onVisibleAreaChanged(area: Rect) {
        surfaceResource?.onVisibleAreaChanged(if (area.isEmpty) null else Rect(area))
    }

    @Synchronized
    override fun onScroll(distanceX: Float, distanceY: Float) {
        surfaceResource?.onScroll(distanceX, distanceY)
    }

    @Synchronized
    override fun onFling(velocityX: Float, velocityY: Float) {
        surfaceResource?.onFling(velocityX, velocityY)
    }

    @Synchronized
    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        surfaceResource?.onScale(focusX, focusY, scaleFactor)
    }

    @Synchronized
    override fun onClick(x: Float, y: Float) {
        surfaceResource?.onClick(x, y)
    }

    @Synchronized
    fun onDrivingSnapshot(snapshot: DrivingSnapshot?) {
        if (destroyed || snapshot == null) return
        latestSnapshot = snapshot
        trackingStopped = false
        surfaceResource?.onDrivingSnapshot(snapshot)
    }

    @Synchronized
    fun onDatabaseCount(count: Int) {
        if (destroyed || count < 0) return
        latestDatabaseCount = count
        surfaceResource?.onDatabaseCount(count)
    }

    @Synchronized
    fun onTrackingStopped() {
        if (destroyed) return
        trackingStopped = true
        latestSnapshot = DrivingSnapshot.idle()
        surfaceResource?.onTrackingStopped()
    }

    @Synchronized
    fun refreshVisible() {
        if (!destroyed) surfaceResource?.refreshVisible()
    }

    @Synchronized
    fun zoomBy(delta: Float) {
        if (!destroyed) surfaceResource?.zoomBy(delta)
    }

    @Synchronized
    fun recenter(): Boolean {
        val resource = surfaceResource
        if (destroyed || resource == null || !latestSnapshot.hasLocation()) return false
        resource.recenter()
        return true
    }

    @Synchronized
    fun setPanMode(enabled: Boolean) {
        if (destroyed) return
        panMode = enabled
        surfaceResource?.setPanMode(enabled)
    }

    @Synchronized
    fun onCarConfigurationChanged() {
        if (!destroyed) surfaceResource?.onCarConfigurationChanged()
    }

    @Synchronized
    fun refreshHudTransparency() {
        if (!destroyed) (surfaceResource as? AndroidSurfaceResource)?.refreshHudTransparency()
    }

    @Synchronized
    fun destroy() {
        if (destroyed) return
        destroyed = true
        appManager.setSurfaceCallback(null)
        releaseResource(true)
    }

    private fun releaseResource(releaseSurface: Boolean) {
        val existing = surfaceResource
        val surface = activeSurface
        surfaceResource = null
        activeSurface = null
        activeSpec = null
        try {
            existing?.release()
        } catch (error: RuntimeException) {
            Log.e(TAG, "Failed to release car surface content", error)
        } catch (error: LinkageError) {
            Log.e(TAG, "Failed to release car surface content", error)
        } finally {
            if (releaseSurface) releaseSurfaceObject(surface)
        }
    }

    private fun clearActiveAreas() {
        surfaceResource?.onStableAreaChanged(null)
        surfaceResource?.onVisibleAreaChanged(null)
    }

    private fun releaseSurfaceObject(surface: Surface?) {
        surface ?: return
        try {
            surfaceReleaser.release(surface)
        } catch (error: RuntimeException) {
            Log.e(TAG, "Failed to release host surface", error)
        } catch (error: LinkageError) {
            Log.e(TAG, "Failed to release host surface", error)
        }
    }

    private fun notifySurfaceFailure(error: Throwable) {
        Log.e(TAG, "Unable to create car map surface", error)
        try {
            failureListener.onSurfaceFailure(SURFACE_ERROR)
        } catch (callbackError: RuntimeException) {
            Log.e(TAG, "Unable to report car map surface failure", callbackError)
        }
    }

    private class AndroidSurfaceFactory(
        private val carContext: CarContext?,
        application: GpsAntiRadarApplication?,
    ) : SurfaceFactory {
        private val application = requireNotNull(application) { "application is required" }

        override fun create(spec: CarSurfaceSpec, surface: Surface?): SurfaceResource =
            AndroidSurfaceResource(requireNotNull(carContext), application, spec, surface)
    }

    private class AndroidSurfaceResource(
        carContext: CarContext,
        application: GpsAntiRadarApplication,
        spec: CarSurfaceSpec,
        surface: Surface?,
    ) : SurfaceResource {
        private var virtualDisplay: VirtualDisplay? = null
        private var presentation: Presentation? = null
        private var content: CarMapPresentation? = null
        private var released = false

        init {
            val displayManager = carContext.getSystemService(DisplayManager::class.java)
                ?: throw IllegalStateException("DisplayManager is unavailable")
            try {
                val display = displayManager.createVirtualDisplay(
                    "gps-antiradar-car-map",
                    spec.width,
                    spec.height,
                    spec.dpi,
                    surface,
                    0,
                )
                virtualDisplay = display
                val targetDisplay = display?.display
                    ?: throw IllegalStateException("Unable to create car map display")
                val nextPresentation = Presentation(carContext, targetDisplay)
                presentation = nextPresentation
                val nextContent = CarMapPresentation(nextPresentation.context, application, carContext)
                content = nextContent
                nextContent.start()
                nextPresentation.setContentView(nextContent.rootView())
                nextPresentation.show()
            } catch (error: RuntimeException) {
                release()
                throw error
            } catch (error: LinkageError) {
                release()
                throw error
            }
        }

        override fun onDrivingSnapshot(snapshot: DrivingSnapshot) {
            if (!released) content?.onDrivingSnapshot(snapshot)
        }
        override fun onDatabaseCount(count: Int) {
            if (!released) content?.onDatabaseCount(count)
        }
        override fun onTrackingStopped() {
            if (!released) content?.onTrackingStopped()
        }
        override fun refreshVisible() {
            if (!released) content?.refreshVisible()
        }
        override fun zoomBy(delta: Float) {
            if (!released) content?.zoomBy(delta)
        }
        override fun recenter() {
            if (!released) content?.recenter()
        }
        override fun setPanMode(enabled: Boolean) {
            if (!released) content?.setPanMode(enabled)
        }
        override fun onStableAreaChanged(area: Rect?) {
            if (!released) content?.onStableAreaChanged(area)
        }
        override fun onVisibleAreaChanged(area: Rect?) {
            if (!released) content?.onVisibleAreaChanged(area)
        }
        override fun onCarConfigurationChanged() {
            if (!released) content?.onCarConfigurationChanged()
        }
        override fun onScroll(distanceX: Float, distanceY: Float) {
            if (!released) content?.onScroll(distanceX, distanceY)
        }
        override fun onFling(velocityX: Float, velocityY: Float) {
            if (!released) content?.onFling(velocityX, velocityY)
        }
        override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
            if (!released) content?.onScale(focusX, focusY, scaleFactor)
        }
        override fun onClick(x: Float, y: Float) {
            if (!released) content?.onClick(x, y)
        }

        fun refreshHudTransparency() {
            if (!released) content?.refreshHudTransparency()
        }

        override fun release() {
            if (released) return
            released = true
            content?.let {
                try {
                    it.destroy()
                } catch (error: RuntimeException) {
                    Log.e(TAG, "Failed to destroy car map content", error)
                } catch (error: LinkageError) {
                    Log.e(TAG, "Failed to destroy car map content", error)
                }
            }
            content = null
            presentation?.let {
                try {
                    it.dismiss()
                } catch (error: RuntimeException) {
                    Log.e(TAG, "Failed to dismiss car presentation", error)
                } catch (error: LinkageError) {
                    Log.e(TAG, "Failed to dismiss car presentation", error)
                }
            }
            presentation = null
            virtualDisplay?.let {
                try {
                    it.release()
                } catch (error: RuntimeException) {
                    Log.e(TAG, "Failed to release car virtual display", error)
                } catch (error: LinkageError) {
                    Log.e(TAG, "Failed to release car virtual display", error)
                }
            }
            virtualDisplay = null
        }
    }

    companion object {
        private const val TAG = "CarSurfaceController"
        private const val SURFACE_ERROR = "Не удалось отобразить карту. Переподключите Android Auto."
    }
}
