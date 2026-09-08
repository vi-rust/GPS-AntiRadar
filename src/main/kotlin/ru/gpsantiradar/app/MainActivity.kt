package ru.gpsantiradar.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.yandex.mapkit.ScreenPoint
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.mapview.MapView
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private lateinit var speedView: TextView
    private lateinit var unitView: TextView
    private lateinit var distanceView: TextView
    private lateinit var cameraView: TextView
    private var mapNoticeView: TextView? = null
    private var aboutDatabaseCountView: TextView? = null
    private var aboutLastDownloadView: TextView? = null
    private lateinit var screenView: FrameLayout
    private lateinit var hudPanel: LinearLayout
    private lateinit var zoomControlsView: LinearLayout
    private lateinit var mapOverlay: FrameLayout
    private lateinit var zoomDividerView: View
    private lateinit var menuButtonView: ImageButton
    private lateinit var zoomInButtonView: ImageButton
    private lateinit var zoomOutButtonView: ImageButton
    private lateinit var positionButtonView: ImageButton
    private lateinit var cameraHintView: TextView
    private var mapView: MapView? = null
    private var cameraMapLayer: SharedCameraMapLayer? = null
    private var mapInitialized = false
    private var mapRecoveryRequired = false
    private var hasCurrentLocation = false
    private var darkTheme = false
    private var databaseEmpty = false
    private var trackingStopped = false
    private var settingsListenerRegistered = false
    private var hintGeneration = 0
    private var latestSnapshot = DrivingSnapshot.idle()
    private lateinit var settingsPreferences: SharedPreferences

    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener {
            sharedPreferences,
            key,
        ->
        when (key) {
            AppSettings.HUD_TRANSPARENCY -> applyHudTransparency(
                sharedPreferences.getInt(
                    AppSettings.HUD_TRANSPARENCY,
                    AppSettings.DEFAULT_HUD_TRANSPARENCY_PERCENT,
                ),
            )
            AppSettings.THEME_MODE -> refreshThemeIfNeeded()
            AppSettings.OVERSPEED_THRESHOLD -> if (!trackingStopped) renderHud(latestSnapshot)
            AppSettings.ZONE_TRANSPARENCY,
            AppSettings.ACTIVE_ZONE_TRANSPARENCY,
            AppSettings.ZONE_DISPLAY_MODE,
            -> cameraMapLayer?.refreshCoverageSettings()
        }
    }

    private val themeRefresh = object : Runnable {
        override fun run() {
            refreshThemeIfNeeded()
            val decor = window.decorView
            decor.removeCallbacks(this)
            decor.postDelayed(this, THEME_REFRESH_MS)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            if (TrackingService.ACTION_STOPPED == intent.action) {
                showTrackingStopped()
                return
            }
            val snapshot = DrivingSnapshotIntent.from(intent)
            latestSnapshot = snapshot
            trackingStopped = false
            hasCurrentLocation = snapshot.hasLocation()
            if (snapshot.hasLocation()) {
                ThemeSettings.rememberLocation(
                    this@MainActivity,
                    snapshot.latitude,
                    snapshot.longitude,
                )
                refreshThemeIfNeeded(snapshot.latitude, snapshot.longitude)
            }
            renderHud(snapshot)
            cameraMapLayer?.let { layer ->
                layer.updateActiveCamera(snapshot.cameraId)
                layer.updateCurrentLocation(
                    snapshot.latitude,
                    snapshot.longitude,
                    snapshot.speedKmh,
                    snapshot.headingDegrees,
                )
            }
        }
    }

    private val radarBaseReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshDatabaseCount()
            refreshAboutDatabaseInfo()
            cameraMapLayer?.refreshVisible()
        }
    }

    private val radarBaseUpdateListener = object : RadarBaseUpdater.Listener {
        override fun onRadarBaseUpdate(state: RadarBaseUpdateState) {
            showRadarBaseUpdateState(state)
        }
    }

    override fun onCreate(state: Bundle?) {
        settingsPreferences = getSharedPreferences(SETTINGS, MODE_PRIVATE)
        darkTheme = ThemeSettings.isDark(this)
        setTheme(if (darkTheme) R.style.AppThemeDark else R.style.AppTheme)
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyImmersiveMode()
        mapInitialized = initializeMapKitSafely()
        buildUi()
        refreshDatabaseCount()
        window.decorView.post { startRequested() }
        if (mapInitialized) {
            cameraMapLayer?.loadInitial(true)
            window.decorView.postDelayed({
                getSharedPreferences(SETTINGS, MODE_PRIVATE).edit()
                    .remove(AppSettings.MAPKIT_PENDING).apply()
            }, 5000)
        }
    }

    private fun applyImmersiveMode() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.decorView
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
            return
        }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    override fun onResume() {
        super.onResume()
        refreshThemeIfNeeded()
        applyImmersiveMode()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveMode()
    }

    private fun initializeMapKitSafely(): Boolean {
        val preferences = getSharedPreferences(SETTINGS, MODE_PRIVATE)

        if (!preferences.getBoolean(AppSettings.MAPKIT_KEY_REENTRY, false)) {
            val hadStoredKey = preferences.contains(AppSettings.MAPKIT_KEY)
            preferences.edit().putBoolean(AppSettings.MAPKIT_KEY_REENTRY, true)
                .remove(AppSettings.MAPKIT_KEY)
                .remove(AppSettings.MAPKIT_PENDING).commit()
            mapRecoveryRequired = hadStoredKey
            if (hadStoredKey) return false
        }

        if (!preferences.getBoolean(AppSettings.MAPKIT_MARKER_FIX, false)) {
            preferences.edit().putBoolean(AppSettings.MAPKIT_MARKER_FIX, true)
                .remove(AppSettings.MAPKIT_PENDING).commit()
        }

        if (!preferences.getBoolean(AppSettings.MAPKIT_SAFE_MIGRATION, false)) {
            val hadStoredKey = preferences.contains(AppSettings.MAPKIT_KEY)
            preferences.edit()
                .putBoolean(AppSettings.MAPKIT_SAFE_MIGRATION, true)
                .remove(AppSettings.MAPKIT_KEY)
                .remove(AppSettings.MAPKIT_PENDING)
                .commit()
            mapRecoveryRequired = hadStoredKey
            if (hadStoredKey) return false
        }

        if (preferences.getBoolean(AppSettings.MAPKIT_PENDING, false)) {
            preferences.edit().remove(AppSettings.MAPKIT_KEY)
                .remove(AppSettings.MAPKIT_PENDING).commit()
            mapRecoveryRequired = true
            return false
        }

        val savedKey = preferences.getString(AppSettings.MAPKIT_KEY, "")
        val embeddedKey = BuildConfig.MAPKIT_API_KEY?.trim().orEmpty()
        if (savedKey.isNullOrBlank() && embeddedKey.isEmpty()) return false

        preferences.edit().putBoolean(AppSettings.MAPKIT_PENDING, true).commit()
        val initialized = GpsAntiRadarApplication.ensureMapKit(this)
        if (!initialized) {
            preferences.edit().remove(AppSettings.MAPKIT_KEY)
                .remove(AppSettings.MAPKIT_PENDING).commit()
            mapRecoveryRequired = true
        }
        return initialized
    }

    private fun buildUi() {
        val screen = FrameLayout(this)
        screenView = screen
        screen.setBackgroundColor(screenBackgroundColor())
        if (mapInitialized) {
            try {
                val view = MapView(this)
                mapView = view
                screen.addView(view, FrameLayout.LayoutParams(-1, -1))
                val layer = SharedCameraMapLayer(
                    this,
                    view.mapWindow,
                    object : SharedCameraMapLayer.Host {
                        override fun postToUi(action: Runnable) {
                            runOnUiThread(action)
                        }

                        override fun onMarkerPresentationChanged() {
                            if (::cameraHintView.isInitialized) cameraHintView.visibility = View.GONE
                            hintGeneration++
                        }

                        override fun onCameraTapped(camera: CameraPoint, position: Point) {
                            showCameraHint(camera, position)
                            val generation = ++hintGeneration
                            window.decorView.postDelayed({
                                if (generation == hintGeneration) hideCameraHintSmoothly(generation)
                            }, 3000L)
                        }
                    },
                )
                cameraMapLayer = layer
                layer.setNightMode(darkTheme)
            } catch (_: Throwable) {
                cameraMapLayer?.destroy()
                cameraMapLayer = null
                mapView = null
                mapInitialized = false
                mapRecoveryRequired = true
                getSharedPreferences(SETTINGS, MODE_PRIVATE).edit()
                    .remove(AppSettings.MAPKIT_KEY)
                    .remove(AppSettings.MAPKIT_PENDING).commit()
            }
        }
        if (!mapInitialized) {
            mapNoticeView = text(
                "Для Яндекс-карты нужен ключ MapKit",
                20,
                secondaryTextColor(),
                Typeface.BOLD,
            ).also { notice ->
                notice.gravity = Gravity.CENTER
                screen.addView(notice, FrameLayout.LayoutParams(-1, -1))
            }
        }

        val overlay = FrameLayout(this)
        mapOverlay = overlay
        val sidePadding = dp(12)
        val topPadding = dp(10)
        val bottomPadding = dp(10)
        overlay.setPadding(sidePadding, topPadding, sidePadding, bottomPadding)
        if (Build.VERSION.SDK_INT >= 30) {
            overlay.setOnApplyWindowInsetsListener { view, windowInsets ->
                val cutout = windowInsets.getInsets(WindowInsets.Type.displayCutout())
                view.setPadding(
                    sidePadding + cutout.left,
                    topPadding + cutout.top,
                    sidePadding + cutout.right,
                    bottomPadding + cutout.bottom,
                )
                windowInsets
            }
        } else if (Build.VERSION.SDK_INT >= 28) {
            overlay.setOnApplyWindowInsetsListener { view, windowInsets ->
                val cutout = windowInsets.displayCutout
                val left = cutout?.safeInsetLeft ?: 0
                val top = cutout?.safeInsetTop ?: 0
                val right = cutout?.safeInsetRight ?: 0
                val bottom = cutout?.safeInsetBottom ?: 0
                view.setPadding(
                    sidePadding + left,
                    topPadding + top,
                    sidePadding + right,
                    bottomPadding + bottom,
                )
                windowInsets
            }
        }
        screen.addView(overlay, FrameLayout.LayoutParams(-1, -1))

        hudPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            setPadding(dp(14), dp(10), dp(14), dp(12))
        }
        applyHudTransparency(
            getSharedPreferences(SETTINGS, MODE_PRIVATE).getInt(
                AppSettings.HUD_TRANSPARENCY,
                AppSettings.DEFAULT_HUD_TRANSPARENCY_PERCENT,
            ),
        )
        hudPanel.elevation = dp(4).toFloat()
        val hudParams = FrameLayout.LayoutParams(dp(270), -2, Gravity.BOTTOM or Gravity.START)
            .apply { setMargins(dp(4), 0, 0, dp(4)) }
        overlay.addView(hudPanel, hudParams)

        hudPanel.addView(text("Скорость", 14, GREEN, Typeface.BOLD))
        speedView = text("0", 46, GREEN, Typeface.BOLD).apply { includeFontPadding = false }
        hudPanel.addView(speedView)
        unitView = text("км/ч", 13, secondaryTextColor(), Typeface.NORMAL)
        hudPanel.addView(unitView)
        distanceView = text("—", 24, primaryTextColor(), Typeface.BOLD)
        hudPanel.addView(distanceView)
        cameraView = text("Объектов впереди нет", 13, GREEN, Typeface.BOLD).apply {
            gravity = Gravity.START
        }
        hudPanel.addView(cameraView)

        menuButtonView = iconButton(R.drawable.ic_menu, "Меню").apply {
            setOnClickListener { showAppMenu() }
        }
        val menuParams = FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.END)
            .apply { setMargins(0, dp(6), dp(6), 0) }
        overlay.addView(menuButtonView, menuParams)

        zoomControlsView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = roundedBackground(controlSurfaceColor(), 7)
            elevation = dp(4).toFloat()
        }
        zoomInButtonView = segmentedIconButton(R.drawable.ic_add, "Увеличить карту").apply {
            setOnClickListener { cameraMapLayer?.zoomBy(1f) }
        }
        zoomControlsView.addView(zoomInButtonView, LinearLayout.LayoutParams(dp(44), dp(44)))
        zoomDividerView = View(this).apply { setBackgroundColor(dividerColor()) }
        val dividerParams = LinearLayout.LayoutParams(-1, dp(1)).apply {
            setMargins(dp(7), 0, dp(7), 0)
        }
        zoomControlsView.addView(zoomDividerView, dividerParams)
        zoomOutButtonView = segmentedIconButton(R.drawable.ic_remove, "Уменьшить карту").apply {
            setOnClickListener { cameraMapLayer?.zoomBy(-1f) }
        }
        zoomControlsView.addView(zoomOutButtonView, LinearLayout.LayoutParams(dp(44), dp(44)))
        val zoomParams = FrameLayout.LayoutParams(dp(44), -2, Gravity.END or Gravity.CENTER_VERTICAL)
            .apply { setMargins(0, 0, dp(6), 0) }
        overlay.addView(zoomControlsView, zoomParams)

        positionButtonView = iconButton(R.drawable.ic_my_location, "Моя точка").apply {
            setOnClickListener { centerOnLocation() }
        }
        val positionParams = FrameLayout.LayoutParams(
            dp(44),
            dp(44),
            Gravity.BOTTOM or Gravity.END,
        ).apply { setMargins(0, 0, dp(6), dp(6)) }
        overlay.addView(positionButtonView, positionParams)

        cameraHintView = text("", 14, primaryTextColor(), Typeface.BOLD).apply {
            maxWidth = dp(360)
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = roundedBackground(hintSurfaceColor(), 10)
            elevation = dp(6).toFloat()
            visibility = View.GONE
        }
        overlay.addView(cameraHintView, FrameLayout.LayoutParams(-2, -2))
        setContentView(screen)
    }

    private fun showAppMenu() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(4))
        }

        val update = menuAction(R.drawable.ic_refresh, "Обновить базу объектов")
        content.addView(update, LinearLayout.LayoutParams(-1, dp(54)))

        val overspeedRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        val overspeedIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_speed_limit)
            tintIcon(this)
        }
        overspeedRow.addView(overspeedIcon, LinearLayout.LayoutParams(dp(28), dp(28)))
        val overspeedContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val overspeedContentParams = LinearLayout.LayoutParams(0, -2, 1f).apply {
            setMargins(dp(14), 0, 0, 0)
        }
        overspeedRow.addView(overspeedContent, overspeedContentParams)
        val overspeedLabel = text("", 15, primaryTextColor(), Typeface.NORMAL)
        overspeedContent.addView(overspeedLabel)
        val overspeedThreshold = SeekBar(this)
        overspeedThreshold.max = AppSettings.MAX_OVERSPEED_THRESHOLD_KMH
        val savedOverspeedThreshold = AppSettings.clampOverspeedThreshold(
            getSharedPreferences(SETTINGS, MODE_PRIVATE).getInt(
                AppSettings.OVERSPEED_THRESHOLD,
                AppSettings.DEFAULT_OVERSPEED_THRESHOLD_KMH,
            ),
        )
        overspeedThreshold.progress = savedOverspeedThreshold
        overspeedLabel.text = "Предел превышения скорости: $savedOverspeedThreshold км/ч"
        overspeedThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val value = AppSettings.clampOverspeedThreshold(progress)
                overspeedLabel.text = "Предел превышения скорости: $value км/ч"
                if (fromUser) {
                    getSharedPreferences(SETTINGS, MODE_PRIVATE).edit()
                        .putInt(AppSettings.OVERSPEED_THRESHOLD, value).apply()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        overspeedContent.addView(overspeedThreshold, LinearLayout.LayoutParams(-1, dp(42)))
        content.addView(overspeedRow, LinearLayout.LayoutParams(-1, dp(86)))

        val transparencyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        val transparencyIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_opacity)
            tintIcon(this)
        }
        transparencyRow.addView(transparencyIcon, LinearLayout.LayoutParams(dp(28), dp(28)))
        val transparencyContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val transparencyContentParams = LinearLayout.LayoutParams(0, -2, 1f).apply {
            setMargins(dp(14), 0, 0, 0)
        }
        transparencyRow.addView(transparencyContent, transparencyContentParams)
        val transparencyLabel = text("", 15, primaryTextColor(), Typeface.NORMAL)
        transparencyContent.addView(transparencyLabel)
        val transparency = SeekBar(this)
        transparency.max = (AppSettings.MAX_HUD_TRANSPARENCY_PERCENT -
            AppSettings.MIN_HUD_TRANSPARENCY_PERCENT) / AppSettings.HUD_TRANSPARENCY_STEP_PERCENT
        val savedTransparency = AppSettings.clampHudTransparency(
            getSharedPreferences(SETTINGS, MODE_PRIVATE).getInt(
                AppSettings.HUD_TRANSPARENCY,
                AppSettings.DEFAULT_HUD_TRANSPARENCY_PERCENT,
            ),
        )
        transparency.progress = (savedTransparency - AppSettings.MIN_HUD_TRANSPARENCY_PERCENT) /
            AppSettings.HUD_TRANSPARENCY_STEP_PERCENT
        transparencyLabel.text = "Прозрачность HUD: $savedTransparency%"
        transparency.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val value = AppSettings.clampHudTransparency(
                    AppSettings.MIN_HUD_TRANSPARENCY_PERCENT +
                        progress * AppSettings.HUD_TRANSPARENCY_STEP_PERCENT,
                )
                transparencyLabel.text = "Прозрачность HUD: $value%"
                getSharedPreferences(SETTINGS, MODE_PRIVATE).edit()
                    .putInt(AppSettings.HUD_TRANSPARENCY, value).apply()
                applyHudTransparency(value)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        transparencyContent.addView(transparency, LinearLayout.LayoutParams(-1, dp(42)))
        content.addView(transparencyRow, LinearLayout.LayoutParams(-1, dp(86)))

        content.addView(
            zoneTransparencyRow(
                "Прозрачность зон",
                AppSettings.ZONE_TRANSPARENCY,
                AppSettings.DEFAULT_ZONE_TRANSPARENCY_PERCENT,
            ),
            LinearLayout.LayoutParams(-1, dp(86)),
        )
        content.addView(
            zoneTransparencyRow(
                "Прозрачность активной зоны",
                AppSettings.ACTIVE_ZONE_TRANSPARENCY,
                AppSettings.DEFAULT_ACTIVE_ZONE_TRANSPARENCY_PERCENT,
            ),
            LinearLayout.LayoutParams(-1, dp(86)),
        )
        val zoneDisplayMode = ZoneDisplayMode.fromStored(
            settingsPreferences.getString(AppSettings.ZONE_DISPLAY_MODE, ZoneDisplayMode.ALL.name),
        )
        val zoneDisplay = menuAction(
            R.drawable.ic_zones,
            "Отображение зон: ${zoneDisplayMode.title()}",
        )
        content.addView(zoneDisplay, LinearLayout.LayoutParams(-1, dp(54)))

        val autoRotateRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }
        val autoRotateIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_navigation)
            tintIcon(this)
        }
        autoRotateRow.addView(autoRotateIcon, LinearLayout.LayoutParams(dp(28), dp(28)))
        val autoRotate = Switch(this).apply {
            text = "Автоповорот карты"
            textSize = 15f
            setTextColor(primaryTextColor())
            isChecked = getSharedPreferences(SETTINGS, MODE_PRIVATE).getBoolean(
                AppSettings.AUTO_ROTATE_MAP,
                AppSettings.DEFAULT_AUTO_ROTATE_MAP,
            )
            setOnCheckedChangeListener { _, enabled ->
                getSharedPreferences(SETTINGS, MODE_PRIVATE).edit()
                    .putBoolean(AppSettings.AUTO_ROTATE_MAP, enabled).apply()
            }
        }
        val autoRotateParams = LinearLayout.LayoutParams(0, -1, 1f).apply {
            setMargins(dp(14), 0, 0, 0)
        }
        autoRotateRow.addView(autoRotate, autoRotateParams)
        content.addView(autoRotateRow, LinearLayout.LayoutParams(-1, dp(54)))

        val theme = menuAction(R.drawable.ic_theme, "Тема: ${ThemeSettings.mode(this).title()}")
        content.addView(theme, LinearLayout.LayoutParams(-1, dp(54)))
        val mapKey = menuAction(R.drawable.ic_key, "Ключ MapKit")
        content.addView(mapKey, LinearLayout.LayoutParams(-1, dp(54)))
        val about = menuAction(R.drawable.ic_info, "О программе")
        content.addView(about, LinearLayout.LayoutParams(-1, dp(54)))
        val exit = menuAction(R.drawable.ic_exit, "Выйти")
        content.addView(exit, LinearLayout.LayoutParams(-1, dp(54)))

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(content, FrameLayout.LayoutParams(-1, -2))
        }
        val dialog = AlertDialog.Builder(this, dialogTheme())
            .setView(scroll)
            .setNegativeButton("Закрыть", null)
            .create()
        update.setOnClickListener {
            dialog.dismiss()
            (application as GpsAntiRadarApplication).radarBaseUpdater().requestUpdate()
        }
        mapKey.setOnClickListener {
            dialog.dismiss()
            showMapKeyDialog()
        }
        theme.setOnClickListener {
            dialog.dismiss()
            showThemeDialog()
        }
        zoneDisplay.setOnClickListener {
            dialog.dismiss()
            showZoneDisplayDialog()
        }
        about.setOnClickListener {
            dialog.dismiss()
            showAboutDialog()
        }
        exit.setOnClickListener {
            dialog.dismiss()
            exitApplication()
        }
        dialog.show()
        styleRoundedDialog(dialog)
    }

    private fun zoneTransparencyRow(
        label: String,
        preferenceKey: String,
        defaultValue: Int,
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_opacity)
            tintIcon(this)
        }
        row.addView(icon, LinearLayout.LayoutParams(dp(28), dp(28)))
        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        row.addView(controls, LinearLayout.LayoutParams(0, -2, 1f).apply {
            setMargins(dp(14), 0, 0, 0)
        })
        val valueLabel = text("", 15, primaryTextColor(), Typeface.NORMAL)
        controls.addView(valueLabel)
        val seekBar = SeekBar(this).apply {
            max = (AppSettings.MAX_ZONE_TRANSPARENCY_PERCENT -
                AppSettings.MIN_ZONE_TRANSPARENCY_PERCENT) / AppSettings.ZONE_TRANSPARENCY_STEP_PERCENT
        }
        val savedValue = AppSettings.clampZoneTransparency(
            settingsPreferences.getInt(preferenceKey, defaultValue),
        )
        seekBar.progress = (savedValue - AppSettings.MIN_ZONE_TRANSPARENCY_PERCENT) /
            AppSettings.ZONE_TRANSPARENCY_STEP_PERCENT
        valueLabel.text = "$label: $savedValue%"
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val value = AppSettings.clampZoneTransparency(
                    AppSettings.MIN_ZONE_TRANSPARENCY_PERCENT +
                        progress * AppSettings.ZONE_TRANSPARENCY_STEP_PERCENT,
                )
                valueLabel.text = "$label: $value%"
                if (fromUser) {
                    settingsPreferences.edit().putInt(preferenceKey, value).apply()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        controls.addView(seekBar, LinearLayout.LayoutParams(-1, dp(42)))
        return row
    }

    private fun showZoneDisplayDialog() {
        val current = ZoneDisplayMode.fromStored(
            settingsPreferences.getString(AppSettings.ZONE_DISPLAY_MODE, ZoneDisplayMode.ALL.name),
        )
        val modes = ZoneDisplayMode.entries.toTypedArray()
        val titles = Array(modes.size) { modes[it].title() }
        val dialog = AlertDialog.Builder(this, dialogTheme())
            .setTitle("Отображение зон")
            .setSingleChoiceItems(titles, current.ordinal) { choice, which ->
                settingsPreferences.edit()
                    .putString(AppSettings.ZONE_DISPLAY_MODE, modes[which].name)
                    .apply()
                choice.dismiss()
            }
            .setNegativeButton("Отмена", null)
            .create()
        dialog.show()
        styleRoundedDialog(dialog)
    }

    private fun showThemeDialog() {
        val current = ThemeSettings.mode(this)
        val modes = ThemeMode.entries.toTypedArray()
        val titles = Array(modes.size) { modes[it].title() }
        val dialog = AlertDialog.Builder(this, dialogTheme())
            .setTitle("Тема")
            .setSingleChoiceItems(titles, current.ordinal) { choice, which ->
                val selected = modes[which]
                choice.dismiss()
                if (selected != ThemeSettings.mode(this@MainActivity)) {
                    ThemeSettings.setMode(this@MainActivity, selected)
                    refreshThemeIfNeeded()
                }
            }
            .setNegativeButton("Отмена", null)
            .create()
        dialog.show()
        styleRoundedDialog(dialog)
    }

    private fun showAboutDialog() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(12))
        }
        content.addView(
            text("GPS AntiRadar", 22, primaryTextColor(), Typeface.BOLD),
            LinearLayout.LayoutParams(-1, -2),
        )
        val currentVersion = text(
            "Версия ${BuildConfig.VERSION_NAME}",
            14,
            secondaryTextColor(),
            Typeface.NORMAL,
        )
        content.addView(currentVersion, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(0, dp(2), 0, dp(16))
        })
        content.addView(
            text("База объектов", 17, primaryTextColor(), Typeface.BOLD),
            LinearLayout.LayoutParams(-1, -2),
        )
        aboutDatabaseCountView = text(
            "Объектов в базе: загрузка…",
            14,
            secondaryTextColor(),
            Typeface.NORMAL,
        ).also {
            content.addView(it, LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(0, dp(4), 0, 0)
            })
        }
        aboutLastDownloadView = text(
            "Последняя успешная загрузка: не выполнялась",
            14,
            secondaryTextColor(),
            Typeface.NORMAL,
        ).also {
            content.addView(it, LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(0, dp(2), 0, dp(16))
            })
        }
        refreshAboutDatabaseInfo()

        ReleaseHistory.find(BuildConfig.VERSION_NAME)?.let { currentRelease ->
            content.addView(
                text("Изменения текущей версии", 17, primaryTextColor(), Typeface.BOLD),
                LinearLayout.LayoutParams(-1, -2),
            )
            val currentChanges = text(
                currentRelease.changes,
                14,
                secondaryTextColor(),
                Typeface.NORMAL,
            ).apply { setLineSpacing(dp(2).toFloat(), 1f) }
            content.addView(currentChanges, LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(0, dp(4), 0, dp(16))
            })
        }

        content.addView(
            text("История релизов", 17, primaryTextColor(), Typeface.BOLD),
            LinearLayout.LayoutParams(-1, -2),
        )
        for (release in ReleaseHistory.entries()) {
            if (BuildConfig.VERSION_NAME == release.version) continue
            val divider = View(this).apply { setBackgroundColor(dividerColor()) }
            content.addView(divider, LinearLayout.LayoutParams(-1, dp(1)).apply {
                setMargins(0, dp(14), 0, dp(12))
            })
            content.addView(
                text("Версия ${release.version}", 15, GREEN, Typeface.BOLD),
                LinearLayout.LayoutParams(-1, -2),
            )
            val changes = text(release.changes, 14, secondaryTextColor(), Typeface.NORMAL).apply {
                setLineSpacing(dp(2).toFloat(), 1f)
            }
            content.addView(changes, LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(0, dp(4), 0, 0)
            })
        }

        val scroll = ScrollView(this).apply {
            addView(content, FrameLayout.LayoutParams(-1, -2))
        }
        val dialog = AlertDialog.Builder(this, dialogTheme())
            .setView(scroll)
            .setNegativeButton("Закрыть", null)
            .create()
        dialog.setOnDismissListener {
            aboutDatabaseCountView = null
            aboutLastDownloadView = null
        }
        dialog.show()
        styleRoundedDialog(dialog)
        dialog.window?.let { dialogWindow ->
            val availableWidth = resources.displayMetrics.widthPixels - dp(48)
            val availableHeight = resources.displayMetrics.heightPixels - dp(48)
            dialogWindow.setLayout(min(dp(560), availableWidth), min(dp(520), availableHeight))
        }
    }

    private fun exitApplication() {
        TrackingService.requestStop(this)
        finishAndRemoveTask()
    }

    private fun menuAction(iconResource: Int, label: String): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
            isClickable = true
            isFocusable = true
        }
        val icon = ImageView(this).apply {
            setImageResource(iconResource)
            tintIcon(this)
        }
        row.addView(icon, LinearLayout.LayoutParams(dp(28), dp(28)))
        val title = text(label, 16, primaryTextColor(), Typeface.NORMAL)
        row.addView(title, LinearLayout.LayoutParams(0, -2, 1f).apply {
            setMargins(dp(14), 0, 0, 0)
        })
        return row
    }

    private fun iconButton(iconResource: Int, description: String): ImageButton =
        ImageButton(this).apply {
            setImageResource(iconResource)
            tintIcon(this)
            contentDescription = description
            scaleType = ImageView.ScaleType.CENTER
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = roundedBackground(controlSurfaceColor(), 7)
            elevation = dp(4).toFloat()
        }

    private fun segmentedIconButton(iconResource: Int, description: String): ImageButton =
        ImageButton(this).apply {
            setImageResource(iconResource)
            tintIcon(this)
            contentDescription = description
            scaleType = ImageView.ScaleType.CENTER
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setBackgroundColor(Color.TRANSPARENT)
        }

    private fun showCameraHint(camera: CameraPoint, point: Point) {
        val view = mapView ?: return
        val screen = view.mapWindow.worldToScreen(point) ?: return
        cameraHintView.text = CameraHintFormatter.format(camera)
        val maxWidth = max(dp(180), mapOverlay.width - dp(24))
        cameraHintView.measure(
            View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(mapOverlay.height, View.MeasureSpec.AT_MOST),
        )
        val width = cameraHintView.measuredWidth
        val height = cameraHintView.measuredHeight
        var x = screen.x + dp(18)
        if (x + width > mapOverlay.width - dp(8)) x = screen.x - width - dp(18)
        var y = screen.y - height - dp(14)
        x = max(dp(8).toFloat(), min(x, (mapOverlay.width - width - dp(8)).toFloat()))
        y = max(dp(8).toFloat(), min(y, (mapOverlay.height - height - dp(8)).toFloat()))
        cameraHintView.x = x
        cameraHintView.y = y
        cameraHintView.animate().cancel()
        cameraHintView.alpha = 0f
        cameraHintView.visibility = View.VISIBLE
        cameraHintView.bringToFront()
        cameraHintView.animate().alpha(1f).setDuration(HINT_ANIMATION_MS).start()
    }

    private fun hideCameraHintSmoothly(generation: Int) {
        if (cameraHintView.visibility != View.VISIBLE) return
        cameraHintView.animate().cancel()
        cameraHintView.animate().alpha(0f).setDuration(HINT_ANIMATION_MS)
            .withEndAction {
                if (generation == hintGeneration) cameraHintView.visibility = View.GONE
            }.start()
    }

    private fun showMapKeyDialog() {
        val input = EditText(this).apply {
            setSingleLine(true)
            hint = "API-ключ MapKit Mobile SDK"
            setTextColor(primaryTextColor())
            setHintTextColor(secondaryTextColor())
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = roundedBackground(inputSurfaceColor(), 7)
        }
        val message = if (mapRecoveryRequired) {
            "Предыдущий ключ был отклонён сервером. Вставьте действующий ключ из раздела «MapKit – мобильный SDK». После сохранения полностью закройте и заново откройте приложение."
        } else {
            "Вставьте ключ из раздела «Интерфейсы API → MapKit – мобильный SDK». После сохранения полностью закройте и заново откройте приложение."
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(4))
        }
        content.addView(
            text("$message Ключ хранится только на телефоне.", 14, primaryTextColor(), Typeface.NORMAL),
            LinearLayout.LayoutParams(-1, -2),
        )
        content.addView(input, LinearLayout.LayoutParams(-1, dp(52)).apply {
            setMargins(0, dp(10), 0, 0)
        })
        val dialog = AlertDialog.Builder(this, dialogTheme())
            .setView(content)
            .setNegativeButton("Позже", null)
            .setPositiveButton("Сохранить") { _, _ ->
                val value = input.text.toString().trim()
                if (value.isNotEmpty()) {
                    getSharedPreferences(SETTINGS, MODE_PRIVATE).edit()
                        .putString(AppSettings.MAPKIT_KEY, value)
                        .remove(AppSettings.MAPKIT_PENDING)
                        .putBoolean(AppSettings.MAPKIT_SAFE_MIGRATION, true)
                        .commit()
                    mapRecoveryRequired = false
                    Toast.makeText(
                        this@MainActivity,
                        "Ключ сохранён. Полностью закройте и откройте приложение",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
            .create()
        dialog.show()
        styleRoundedDialog(dialog)
    }

    private fun styleRoundedDialog(dialog: AlertDialog) {
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val parentPanelId = resources.getIdentifier("parentPanel", "id", "android")
        dialog.findViewById<View>(parentPanelId)?.let { parentPanel ->
            parentPanel.background = roundedBackground(dialogSurfaceColor(), 14)
            parentPanel.clipToOutline = true
        }
    }

    private fun refreshThemeIfNeeded(): Boolean = applyResolvedTheme(ThemeSettings.isDark(this))

    private fun refreshThemeIfNeeded(latitude: Double, longitude: Double): Boolean =
        applyResolvedTheme(
            ThemeSettings.isDark(this, System.currentTimeMillis(), latitude, longitude),
        )

    private fun applyResolvedTheme(resolvedDarkTheme: Boolean): Boolean {
        if (resolvedDarkTheme == darkTheme) {
            cameraMapLayer?.setNightMode(darkTheme)
            return false
        }
        darkTheme = resolvedDarkTheme
        setTheme(if (darkTheme) R.style.AppThemeDark else R.style.AppTheme)
        applyThemeToCurrentViews()
        return true
    }

    private fun applyThemeToCurrentViews() {
        screenView.setBackgroundColor(screenBackgroundColor())
        mapNoticeView?.setTextColor(secondaryTextColor())
        unitView.setTextColor(secondaryTextColor())
        distanceView.setTextColor(primaryTextColor())
        zoomControlsView.background = roundedBackground(controlSurfaceColor(), 7)
        zoomDividerView.setBackgroundColor(dividerColor())
        applyControlTheme(menuButtonView, true)
        applyControlTheme(zoomInButtonView, false)
        applyControlTheme(zoomOutButtonView, false)
        applyControlTheme(positionButtonView, true)
        cameraHintView.setTextColor(primaryTextColor())
        cameraHintView.background = roundedBackground(hintSurfaceColor(), 10)
        applyHudTransparency(
            getSharedPreferences(SETTINGS, MODE_PRIVATE).getInt(
                AppSettings.HUD_TRANSPARENCY,
                AppSettings.DEFAULT_HUD_TRANSPARENCY_PERCENT,
            ),
        )
        cameraMapLayer?.setNightMode(darkTheme)
    }

    private fun applyControlTheme(button: ImageButton?, withSurface: Boolean) {
        button ?: return
        tintIcon(button)
        if (withSurface) button.background = roundedBackground(controlSurfaceColor(), 7)
    }

    private fun centerOnLocation() {
        val layer = cameraMapLayer
        if (layer == null) {
            showMapKeyDialog()
            return
        }
        if (!hasCurrentLocation) {
            Toast.makeText(this, "Дождитесь определения координат GPS", Toast.LENGTH_SHORT).show()
            return
        }
        layer.moveToCurrentLocation()
    }

    private fun startRequested() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), LOCATION_REQUEST)
            return
        }
        if (
            Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_REQUEST)
            return
        }
        startTracking()
    }

    private fun startTracking() {
        startForegroundService(
            Intent(this, TrackingService::class.java).setAction(TrackingService.ACTION_START),
        )
    }

    private fun showRadarBaseUpdateState(state: RadarBaseUpdateState) {
        if (state.status == RadarBaseUpdateState.Status.IDLE) return
        if (state.status == RadarBaseUpdateState.Status.UNCHANGED) refreshDatabaseCount()
        val duration = if (
            state.status == RadarBaseUpdateState.Status.STARTED ||
            state.status == RadarBaseUpdateState.Status.ALREADY_RUNNING
        ) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
        Toast.makeText(this, state.message, duration).show()
    }

    private fun refreshDatabaseCount() {
        Thread({
            CameraDatabase(this@MainActivity).use { db ->
                val count = db.count()
                runOnUiThread {
                    databaseEmpty = count == 0
                    if (!trackingStopped) renderHud(latestSnapshot)
                }
            }
        }, "camera-count").start()
    }

    private fun refreshAboutDatabaseInfo() {
        val countTarget = aboutDatabaseCountView
        val lastDownloadTarget = aboutLastDownloadView
        if (countTarget == null && lastDownloadTarget == null) return
        if (lastDownloadTarget != null) {
            val timestamp = getSharedPreferences(SETTINGS, MODE_PRIVATE)
                .getLong(RADARBASE_LAST_SUCCESSFUL_DOWNLOAD, 0L)
            lastDownloadTarget.text =
                "Последняя успешная загрузка: ${formatLastSuccessfulDownload(timestamp)}"
        }
        countTarget ?: return
        countTarget.text = "Объектов в базе: загрузка…"
        Thread({
            CameraDatabase(this@MainActivity).use { db ->
                val count = db.count()
                runOnUiThread {
                    if (aboutDatabaseCountView === countTarget) {
                        countTarget.text = "Объектов в базе: " +
                            NumberFormat.getIntegerInstance().format(count)
                    }
                }
            }
        }, "about-camera-count").start()
    }

    private fun formatLastSuccessfulDownload(timestamp: Long): String {
        if (timestamp <= 0L) return "не выполнялась"
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(timestamp))
    }

    private fun renderHud(snapshot: DrivingSnapshot) {
        val overspeedThresholdKmh = AppSettings.clampOverspeedThreshold(
            settingsPreferences.getInt(
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

    private fun showTrackingStopped() {
        trackingStopped = true
        latestSnapshot = DrivingSnapshot.idle()
        hasCurrentLocation = false
        speedView.text = "0"
        speedView.setTextColor(GREEN)
        distanceView.text = "—"
        cameraView.text = "Антирадар остановлен"
        cameraView.setTextColor(GREEN)
        cameraMapLayer?.updateActiveCamera(-1L)
    }

    private fun text(value: String, sp: Int, color: Int, style: Int): TextView =
        TextView(this).apply {
            text = value
            textSize = sp.toFloat()
            setTextColor(color)
            typeface = Typeface.create("sans", style)
        }

    private fun roundedBackground(color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(1), Color.argb(40, 0, 0, 0))
        }

    private fun applyHudTransparency(transparencyPercent: Int) {
        if (!::hudPanel.isInitialized) return
        val transparency = AppSettings.clampHudTransparency(transparencyPercent)
        val alpha = (255f * (100 - transparency) / 100f).roundToInt()
        val base = if (darkTheme) Color.rgb(28, 30, 32) else Color.WHITE
        hudPanel.background = roundedBackground(
            Color.argb(alpha, Color.red(base), Color.green(base), Color.blue(base)),
            14,
        )
    }

    private fun screenBackgroundColor(): Int =
        if (darkTheme) Color.rgb(18, 18, 18) else Color.rgb(242, 244, 246)

    private fun primaryTextColor(): Int =
        if (darkTheme) Color.rgb(238, 238, 238) else Color.rgb(30, 30, 30)

    private fun secondaryTextColor(): Int =
        if (darkTheme) Color.rgb(185, 190, 195) else Color.rgb(70, 70, 70)

    private fun controlSurfaceColor(): Int = if (darkTheme) {
        Color.argb(250, 32, 35, 38)
    } else {
        Color.argb(250, 255, 255, 255)
    }

    private fun dialogSurfaceColor(): Int = if (darkTheme) Color.rgb(32, 35, 38) else Color.WHITE

    private fun hintSurfaceColor(): Int = if (darkTheme) {
        Color.argb(248, 32, 35, 38)
    } else {
        Color.argb(248, 255, 255, 255)
    }

    private fun inputSurfaceColor(): Int =
        if (darkTheme) Color.rgb(44, 47, 50) else Color.rgb(248, 248, 248)

    private fun dividerColor(): Int =
        if (darkTheme) Color.rgb(78, 82, 86) else Color.rgb(218, 218, 218)

    private fun dialogTheme(): Int = if (darkTheme) {
        android.R.style.Theme_Material_Dialog_Alert
    } else {
        android.R.style.Theme_Material_Light_Dialog_Alert
    }

    private fun tintIcon(icon: ImageView) {
        if (darkTheme) icon.setColorFilter(Color.rgb(235, 235, 235)) else icon.clearColorFilter()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (
            requestCode == LOCATION_REQUEST && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            startRequested()
        } else if (requestCode == NOTIFICATION_REQUEST) {
            startTracking()
        } else if (requestCode == LOCATION_REQUEST) {
            Toast.makeText(
                this,
                "Для работы нужен доступ к геопозиции",
                Toast.LENGTH_LONG,
            ).show()
            if (!shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName"),
                    ),
                )
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(TrackingService.ACTION_UPDATE)
            addAction(TrackingService.ACTION_STOPPED)
        }
        val radarBaseFilter = IntentFilter(RadarBaseUpdater.ACTION_DATABASE_UPDATED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(radarBaseReceiver, radarBaseFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
            registerReceiver(radarBaseReceiver, radarBaseFilter)
        }
        (application as GpsAntiRadarApplication).radarBaseUpdater()
            .addListener(radarBaseUpdateListener, true)
        settingsPreferences.registerOnSharedPreferenceChangeListener(settingsListener)
        settingsListenerRegistered = true
        refreshDatabaseCount()
        cameraMapLayer?.refreshVisible()
        mapView?.let { view ->
            (application as GpsAntiRadarApplication).acquireMapKit()
            view.onStart()
        }
        val decor = window.decorView
        decor.removeCallbacks(themeRefresh)
        decor.postDelayed(themeRefresh, THEME_REFRESH_MS)
    }

    override fun onStop() {
        window.decorView.removeCallbacks(themeRefresh)
        mapView?.let { view ->
            view.onStop()
            (application as GpsAntiRadarApplication).releaseMapKit()
        }
        (application as GpsAntiRadarApplication).radarBaseUpdater()
            .removeListener(radarBaseUpdateListener)
        if (settingsListenerRegistered) {
            settingsPreferences.unregisterOnSharedPreferenceChangeListener(settingsListener)
            settingsListenerRegistered = false
        }
        unregisterReceiver(receiver)
        unregisterReceiver(radarBaseReceiver)
        super.onStop()
    }

    override fun onDestroy() {
        cameraMapLayer?.destroy()
        cameraMapLayer = null
        super.onDestroy()
    }

    companion object {
        private const val LOCATION_REQUEST = 100
        private const val NOTIFICATION_REQUEST = 102
        private val GREEN = Color.rgb(0, 166, 82)
        private const val SETTINGS = AppSettings.PREFERENCES
        private const val RADARBASE_LAST_SUCCESSFUL_DOWNLOAD =
            AppSettings.RADARBASE_LAST_SUCCESSFUL_DOWNLOAD
        private const val HINT_ANIMATION_MS = 220L
        private const val THEME_REFRESH_MS = 60_000L
    }
}
