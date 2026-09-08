package ru.gpsantiradar.app

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.car.app.CarContext
import com.yandex.mapkit.ScreenPoint
import com.yandex.mapkit.ScreenRect
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.map.MapWindow
import com.yandex.mapkit.mapview.MapView
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class CarMapPresentation(
    private val context: Context,
    private val application: GpsAntiRadarApplication?,
    @Suppress("UNUSED_PARAMETER") carContext: CarContext,
) {
    private val preferences = context.getSharedPreferences(
        AppSettings.PREFERENCES,
        Context.MODE_PRIVATE,
    )
    private val mainHandler = Handler(Looper.getMainLooper())
    private val root = FrameLayout(context)
    private val hudPanel = LinearLayout(context)
    private val speedView: TextView
    private val unitView: TextView
    private val distanceView: TextView
    private val cameraView: TextView
    private val hintView: TextView
    private var mapView: MapView? = null
    private var mapLayer: SharedCameraMapLayer? = null
    private var gestureController: CarMapGestureController? = null
    private var stableArea: Rect? = null
    private var visibleArea: Rect? = null
    private var mapKitAcquired = false
    private var mapStarted = false
    private var destroyed = false
    private var darkTheme = ThemeSettings.isDark(context)
    private var themeApplied = false
    private var settingsRegistered = false
    private var databaseEmpty = false
    private var trackingStopped = false
    private var hintGeneration = 0
    private var latestSnapshot = DrivingSnapshot.idle()

    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (!destroyed) mainHandler.post { applySettingChange(key) }
    }
    private val themeRefresh = object : Runnable {
        override fun run() {
            if (destroyed) return
            applyTheme(ThemeSettings.isDark(context))
            if (!destroyed) root.postDelayed(this, 60_000L)
        }
    }

    init {
        root.setBackgroundColor(
            if (darkTheme) Color.rgb(18, 18, 18) else Color.rgb(242, 244, 246),
        )

        hudPanel.orientation = LinearLayout.VERTICAL
        hudPanel.gravity = Gravity.START
        hudPanel.setPadding(dp(14), dp(10), dp(14), dp(12))
        hudPanel.elevation = dp(4).toFloat()
        val hudParams = FrameLayout.LayoutParams(
            dp(300),
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.START,
        ).apply { setMargins(dp(8), 0, 0, dp(8)) }
        root.addView(hudPanel, hudParams)

        hudPanel.addView(text("Скорость", 14, GREEN, Typeface.BOLD))
        speedView = text("0", 46, GREEN, Typeface.BOLD).apply {
            includeFontPadding = false
        }
        hudPanel.addView(speedView)
        unitView = text("км/ч", 13, Color.DKGRAY, Typeface.NORMAL)
        hudPanel.addView(unitView)
        distanceView = text("—", 24, Color.rgb(30, 30, 30), Typeface.BOLD)
        hudPanel.addView(distanceView)
        cameraView = text("Объектов впереди нет", 13, GREEN, Typeface.BOLD)
        hudPanel.addView(cameraView)

        hintView = text("", 13, Color.BLACK, Typeface.BOLD).apply {
            setPadding(dp(10), dp(7), dp(10), dp(7))
            visibility = View.GONE
        }
        val hintParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL,
        ).apply { setMargins(0, dp(8), 0, 0) }
        root.addView(hintView, hintParams)

        onCarConfigurationChanged()
    }

    fun start() {
        check(!destroyed) { "Car map presentation is destroyed" }
        if (mapView != null) return
        registerSettingsListener()
        try {
            val view = MapView(context)
            mapView = view
            root.addView(
                view,
                0,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            startMap()
            root.removeCallbacks(themeRefresh)
            root.postDelayed(themeRefresh, 60_000L)
        } catch (error: RuntimeException) {
            destroy()
            throw error
        } catch (error: LinkageError) {
            destroy()
            throw error
        }
    }

    fun rootView(): View = root

    fun onDrivingSnapshot(snapshot: DrivingSnapshot?) {
        if (destroyed || snapshot == null) return
        latestSnapshot = snapshot
        trackingStopped = false
        if (snapshot.hasLocation()) {
            ThemeSettings.rememberLocation(context, snapshot.latitude, snapshot.longitude)
            applyTheme(
                ThemeSettings.isDark(
                    context,
                    System.currentTimeMillis(),
                    snapshot.latitude,
                    snapshot.longitude,
                ),
            )
        } else {
            applyTheme(ThemeSettings.isDark(context))
        }
        renderHud(snapshot)
        mapLayer?.let { layer ->
            layer.updateActiveCamera(snapshot.cameraId)
            if (snapshot.hasLocation()) {
                layer.updateCurrentLocation(
                    snapshot.latitude,
                    snapshot.longitude,
                    snapshot.speedKmh,
                    snapshot.headingDegrees,
                )
            }
        }
    }

    private fun renderHud(snapshot: DrivingSnapshot) {
        val overspeedThresholdKmh = AppSettings.clampOverspeedThreshold(
            preferences.getInt(
                AppSettings.OVERSPEED_THRESHOLD,
                AppSettings.DEFAULT_OVERSPEED_THRESHOLD_KMH,
            ),
        )
        val presentation = DrivingHudPresentation.from(snapshot, overspeedThresholdKmh)
        speedView.text = presentation.speedText
        speedView.setTextColor(presentation.speedColor)
        distanceView.text = presentation.distanceText
        cameraView.text = if (databaseEmpty && !presentation.hasActiveObject) {
            "База объектов пуста — обновите RadarBase"
        } else {
            presentation.cameraText
        }
        cameraView.setTextColor(presentation.speedColor)
    }

    fun onDatabaseCount(count: Int) {
        if (destroyed || count < 0) return
        databaseEmpty = count == 0
        if (!trackingStopped) renderHud(latestSnapshot)
    }

    fun onTrackingStopped() {
        if (destroyed) return
        trackingStopped = true
        latestSnapshot = DrivingSnapshot.idle()
        speedView.text = "0"
        speedView.setTextColor(GREEN)
        distanceView.text = "—"
        cameraView.text = "Антирадар остановлен"
        cameraView.setTextColor(GREEN)
        mapLayer?.updateActiveCamera(-1L)
    }

    fun refreshVisible() {
        if (!destroyed) mapLayer?.refreshVisible()
    }

    fun zoomBy(delta: Float) { gestureController?.zoomBy(delta) }
    fun recenter() { gestureController?.recenter() }
    fun setPanMode(enabled: Boolean) { gestureController?.setPanMode(enabled) }
    fun onScroll(distanceX: Float, distanceY: Float) {
        gestureController?.onScroll(distanceX, distanceY)
    }
    fun onFling(velocityX: Float, velocityY: Float) {
        gestureController?.onFling(velocityX, velocityY)
    }
    fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        gestureController?.onScale(focusX, focusY, scaleFactor)
    }
    fun onClick(x: Float, y: Float) { gestureController?.onClick(x, y) }

    fun onStableAreaChanged(area: Rect?) {
        if (destroyed) return
        stableArea = if (area == null || area.isEmpty) null else Rect(area)
        applyStableArea()
        root.post(this::applyStableArea)
    }

    fun onVisibleAreaChanged(area: Rect?) {
        if (destroyed) return
        visibleArea = if (area == null || area.isEmpty) null else Rect(area)
        applyVisibleArea()
        root.post(this::applyVisibleArea)
    }

    fun onCarConfigurationChanged() {
        if (!destroyed) applyTheme(ThemeSettings.isDark(context))
    }

    fun refreshHudTransparency() {
        if (destroyed) return
        val percent = AppSettings.clampHudTransparency(
            preferences.getInt(
                AppSettings.HUD_TRANSPARENCY,
                AppSettings.DEFAULT_HUD_TRANSPARENCY_PERCENT,
            ),
        )
        val alpha = (255f * (100 - percent) / 100f).roundToInt()
        val base = if (darkTheme) Color.rgb(28, 30, 32) else Color.WHITE
        hudPanel.background = roundedBackground(
            Color.argb(alpha, Color.red(base), Color.green(base), Color.blue(base)),
        )
    }

    fun refreshCoverageSettings() {
        if (!destroyed) mapLayer?.refreshCoverageSettings()
    }

    fun cameraState(): CarMapCameraState? = if (destroyed) null else mapLayer?.cameraState()

    fun restoreCameraState(state: CarMapCameraState?) {
        if (!destroyed) mapLayer?.restoreCameraState(state)
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        root.removeCallbacks(themeRefresh)
        unregisterSettingsListener()
        mapLayer?.let { layer ->
            try {
                layer.destroy()
            } catch (error: RuntimeException) {
                Log.e(TAG, "Failed to destroy shared camera layer", error)
            } catch (error: LinkageError) {
                Log.e(TAG, "Failed to destroy shared camera layer", error)
            }
        }
        mapLayer = null
        gestureController = null
        val view = mapView
        if (mapStarted && view != null) {
            try {
                view.onStop()
            } catch (error: RuntimeException) {
                Log.e(TAG, "Failed to stop car MapView", error)
            } catch (error: LinkageError) {
                Log.e(TAG, "Failed to stop car MapView", error)
            }
            mapStarted = false
        }
        if (view != null) {
            try {
                view.destroy()
            } catch (error: RuntimeException) {
                Log.e(TAG, "Failed to destroy car MapView", error)
            } catch (error: LinkageError) {
                Log.e(TAG, "Failed to destroy car MapView", error)
            }
            mapView = null
        }
        if (mapKitAcquired) {
            try {
                application!!.releaseMapKit()
            } catch (error: RuntimeException) {
                Log.e(TAG, "Failed to release MapKit", error)
            } catch (error: LinkageError) {
                Log.e(TAG, "Failed to release MapKit", error)
            }
            mapKitAcquired = false
        }
    }

    private fun startMap() {
        mapKitAcquired = true
        application!!.acquireMapKit()
        mapStarted = true
        val view = checkNotNull(mapView)
        view.onStart()
        val mapWindow = view.mapWindow
        val layer = SharedCameraMapLayer(context, mapWindow, object : SharedCameraMapLayer.Host {
            override fun postToUi(action: Runnable) {
                root.post(action)
            }

            override fun onMarkerPresentationChanged() {
                hintView.visibility = View.GONE
                hintGeneration++
            }

            override fun onCameraTapped(camera: CameraPoint, position: Point) {
                hintView.text = CameraHintFormatter.format(camera)
                hintView.visibility = View.VISIBLE
                val generation = ++hintGeneration
                root.postDelayed({
                    if (!destroyed && generation == hintGeneration) {
                        hintView.visibility = View.GONE
                    }
                }, HINT_VISIBLE_MS)
            }
        })
        mapLayer = layer
        layer.setNightMode(darkTheme)
        gestureController = CarMapGestureController(mapWindow, layer) {
            hintGeneration++
            hintView.visibility = View.GONE
        }
        layer.loadInitial(true)
    }

    private fun applyTheme(dark: Boolean) {
        if (destroyed || themeApplied && darkTheme == dark) return
        darkTheme = dark
        themeApplied = true
        val primary = if (dark) Color.rgb(238, 238, 238) else Color.rgb(30, 30, 30)
        val secondary = if (dark) Color.rgb(185, 190, 195) else Color.DKGRAY
        val hintSurface = if (dark) Color.rgb(32, 35, 38) else Color.WHITE
        root.setBackgroundColor(if (dark) Color.rgb(18, 18, 18) else Color.rgb(242, 244, 246))
        unitView.setTextColor(secondary)
        distanceView.setTextColor(primary)
        hintView.setTextColor(primary)
        hintView.background = roundedBackground(hintSurface)
        refreshHudTransparency()
        mapLayer?.setNightMode(dark)
    }

    private fun registerSettingsListener() {
        if (settingsRegistered) return
        preferences.registerOnSharedPreferenceChangeListener(settingsListener)
        settingsRegistered = true
    }

    private fun unregisterSettingsListener() {
        if (!settingsRegistered) return
        preferences.unregisterOnSharedPreferenceChangeListener(settingsListener)
        settingsRegistered = false
    }

    private fun applySettingChange(key: String?) {
        if (destroyed || key == null) return
        when (key) {
            AppSettings.HUD_TRANSPARENCY -> refreshHudTransparency()
            AppSettings.THEME_MODE -> applyTheme(ThemeSettings.isDark(context))
            AppSettings.OVERSPEED_THRESHOLD -> if (!trackingStopped) renderHud(latestSnapshot)
            AppSettings.ZONE_TRANSPARENCY,
            AppSettings.ACTIVE_ZONE_TRANSPARENCY,
            AppSettings.ZONE_DISPLAY_MODE,
            -> refreshCoverageSettings()
        }
    }

    private fun applyStableArea() {
        if (destroyed) return
        val params = hudPanel.layoutParams as FrameLayout.LayoutParams
        val area = stableArea
        if (area == null || area.isEmpty) {
            params.leftMargin = dp(8)
            params.bottomMargin = dp(8)
            params.width = dp(300)
            hudPanel.layoutParams = params
            return
        }
        val surfaceHeight = mapView?.mapWindow?.height() ?: root.height
        params.leftMargin = max(dp(8), area.left + dp(8))
        params.bottomMargin = max(dp(8), surfaceHeight - area.bottom + dp(8))
        val availableWidth = max(dp(180), area.width() - dp(16))
        params.width = min(dp(300), availableWidth)
        hudPanel.layoutParams = params
    }

    private fun applyVisibleArea() {
        if (destroyed) return
        val mapWindow = mapView?.mapWindow ?: return
        val area = visibleArea
        if (area == null || area.isEmpty) {
            mapWindow.focusRect = null
            return
        }
        val width = mapWindow.width()
        val height = mapWindow.height()
        if (width <= 0 || height <= 0) return
        val left = max(0, min(width, area.left)).toFloat()
        val top = max(0, min(height, area.top)).toFloat()
        val right = max(left, min(width, area.right).toFloat())
        val bottom = max(top, min(height, area.bottom).toFloat())
        mapWindow.focusRect = ScreenRect(ScreenPoint(left, top), ScreenPoint(right, bottom))
    }

    private fun text(value: String, sp: Int, color: Int, style: Int): TextView =
        TextView(context).apply {
            text = value
            textSize = sp.toFloat()
            setTextColor(color)
            typeface = Typeface.create("sans", style)
        }

    private fun roundedBackground(color: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(7).toFloat()
        setStroke(dp(1), Color.argb(40, 0, 0, 0))
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()

    companion object {
        private const val TAG = "CarMapPresentation"
        private val GREEN = Color.rgb(0, 166, 82)
        private const val HINT_VISIBLE_MS = 3000L
    }
}
