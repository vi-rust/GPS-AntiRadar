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
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.yandex.mapkit.ScreenPoint
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.mapview.MapView
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale
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
    private var hasCurrentLocation = false
    private var darkTheme = false
    private var databaseEmpty = false
    private var trackingStopped = false
    private var settingsListenerRegistered = false
    private var hintGeneration = 0
    private var uiScaleFactor = 1f
    private var mapScaleFactor = 1f
    private var settingsMenuDialogCount = 0
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
            AppSettings.ZONE_OBJECT_SCOPE,
            -> cameraMapLayer?.refreshCoverageSettings()
            AppSettings.LOCATION_ARROW_SCALE -> cameraMapLayer?.refreshLocationMarkerStyle()
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
                layer.updateActiveCameras(snapshot.activeCameraIds)
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
        uiScaleFactor = selectedUiScalePercent() / 100f
        mapScaleFactor = selectedMapScalePercent() / 100f
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
        if (preferences.getBoolean(AppSettings.MAPKIT_PENDING, false)) {
            preferences.edit().remove(AppSettings.MAPKIT_PENDING).commit()
            return false
        }
        if (BuildConfig.MAPKIT_API_KEY.isBlank()) return false

        preferences.edit().putBoolean(AppSettings.MAPKIT_PENDING, true).commit()
        val initialized = GpsAntiRadarApplication.ensureMapKit(this)
        if (!initialized) {
            preferences.edit().remove(AppSettings.MAPKIT_PENDING).commit()
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
                view.mapWindow.setScaleFactor(mapScaleFactor)
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
                            if (camera.userDefined) {
                                showUserCameraHintDialog(camera)
                                return
                            }
                            showCameraHint(camera, position)
                            val generation = ++hintGeneration
                            window.decorView.postDelayed({
                                if (generation == hintGeneration) hideCameraHintSmoothly(generation)
                            }, 3000L)
                        }

                        override fun onMapLongPressed(position: Point) {
                            showQuickAddUserObjectDialog(position)
                        }

                        override fun userCameraDraggingEnabled(): Boolean = true

                        override fun onUserCameraMoved(camera: CameraPoint, position: Point) {
                            saveMovedUserObject(camera, position)
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
                getSharedPreferences(SETTINGS, MODE_PRIVATE).edit()
                    .remove(AppSettings.MAPKIT_PENDING).commit()
            }
        }
        if (!mapInitialized) {
            mapNoticeView = text(
                "Не удалось инициализировать Яндекс-карту",
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
        val hudOverlay = FrameLayout(this).apply {
            setPadding(dp(4), dp(4), 0, 0)
        }
        if (Build.VERSION.SDK_INT >= 30) {
            hudOverlay.setOnApplyWindowInsetsListener { view, windowInsets ->
                val cutout = windowInsets.getInsets(WindowInsets.Type.displayCutout())
                view.setPadding(dp(4) + cutout.left, dp(4) + cutout.top, 0, 0)
                windowInsets
            }
        } else if (Build.VERSION.SDK_INT >= 28) {
            hudOverlay.setOnApplyWindowInsetsListener { view, windowInsets ->
                val cutout = windowInsets.displayCutout
                view.setPadding(
                    dp(4) + (cutout?.safeInsetLeft ?: 0),
                    dp(4) + (cutout?.safeInsetTop ?: 0),
                    0,
                    0,
                )
                windowInsets
            }
        }
        screen.addView(hudOverlay, FrameLayout.LayoutParams(-1, -1))

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
        val hudParams = FrameLayout.LayoutParams(dp(203), -2, Gravity.TOP or Gravity.START)
        hudOverlay.addView(hudPanel, hudParams)

        hudPanel.addView(text("Скорость", 20, GREEN, Typeface.BOLD))
        speedView = text("0", 66, GREEN, Typeface.BOLD).apply { includeFontPadding = false }
        hudPanel.addView(speedView)
        unitView = text("км/ч", 19, secondaryTextColor(), Typeface.NORMAL)
        hudPanel.addView(unitView)
        distanceView = text("—", 35, primaryTextColor(), Typeface.BOLD)
        hudPanel.addView(distanceView)
        cameraView = text("Объектов впереди нет", 19, GREEN, Typeface.BOLD).apply {
            gravity = Gravity.START
        }
        hudPanel.addView(cameraView)

        screen.addView(overlay, FrameLayout.LayoutParams(-1, -1))

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
        val content = menuContent()
        val alerts = menuAction(R.drawable.ic_speed_limit, "Оповещения")
        val map = menuAction(R.drawable.ic_navigation, "Карта")
        val interfaceSettings = menuAction(R.drawable.ic_theme, "Интерфейс")
        val applicationSettings = menuAction(R.drawable.ic_info, "Приложение")
        val exit = menuAction(R.drawable.ic_exit, "Выйти")
        listOf(alerts, map, interfaceSettings, applicationSettings, exit).forEach {
            content.addView(it, LinearLayout.LayoutParams(-1, dp(54)))
        }
        val dialog = showMenuDialog("Меню", content)
        alerts.setOnClickListener { showAlertsMenu(); dialog.dismiss() }
        map.setOnClickListener { showMapMenu(); dialog.dismiss() }
        interfaceSettings.setOnClickListener { showInterfaceMenu(); dialog.dismiss() }
        applicationSettings.setOnClickListener { showApplicationMenu(); dialog.dismiss() }
        exit.setOnClickListener { exitApplication(); dialog.dismiss() }
    }

    private fun showQuickAddUserObjectDialog(position: Point) {
        val objectTypes = RadarBaseTypes.allTypes()
        val titles = objectTypes.map(RadarBaseTypes::name)
        val dialog = AlertDialog.Builder(this, dialogTheme())
            .setTitle("Тип нового объекта")
            .setAdapter(
                scaledTextAdapter(titles, android.R.layout.simple_list_item_1),
            ) { _, index -> addUserObjectAt(position, objectTypes[index]) }
            .setNegativeButton("Отмена", null)
            .create()
        dialog.show()
        styleRoundedDialog(dialog)
    }

    private fun addUserObjectAt(position: Point, type: Int) {
        val point = UserCameraDefaults.create(
            position.latitude,
            position.longitude,
            type,
            latestSnapshot.headingDegrees,
        )
        Thread({
            val saved = runCatching {
                CameraDatabase(this@MainActivity).use { it.addUserObject(point) }
            }.isSuccess
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (saved) refreshAfterUserObjectChange()
                Toast.makeText(
                    this,
                    if (saved) "Объект добавлен" else "Не удалось сохранить объект",
                    if (saved) Toast.LENGTH_SHORT else Toast.LENGTH_LONG,
                ).show()
            }
        }, "user-object-add").start()
    }

    private fun showUserCameraHintDialog(camera: CameraPoint) {
        val dialog = AlertDialog.Builder(this, dialogTheme())
            .setMessage(CameraHintFormatter.format(camera))
            .setPositiveButton("Редактировать") { _, _ -> showUserObjectEditor(camera) }
            .setNeutralButton("Удалить") { _, _ -> confirmDeleteUserObject(camera) }
            .setNegativeButton("Закрыть", null)
            .create()
        dialog.show()
        styleRoundedDialog(dialog)
    }

    private fun showUserObjectEditor(existing: CameraPoint) {
        val objectTypes = RadarBaseTypes.allTypes()
        val directionTypes = intArrayOf(0, 1, 2, 3, 4)
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        form.addView(
            text(
                "Координаты: ${String.format(Locale.US, "%.6f, %.6f", existing.latitude, existing.longitude)}",
                14,
                secondaryTextColor(),
                Typeface.NORMAL,
            ),
        )
        form.addView(text("Тип объекта", 14, primaryTextColor(), Typeface.BOLD).apply {
            setPadding(0, dp(14), 0, dp(4))
        })
        val typeSpinner = Spinner(this).apply {
            adapter = scaledTextAdapter(
                objectTypes.map(RadarBaseTypes::name),
                android.R.layout.simple_spinner_item,
                android.R.layout.simple_spinner_dropdown_item,
            )
            setSelection(max(0, objectTypes.indexOf(existing.type)))
        }
        form.addView(typeSpinner, LinearLayout.LayoutParams(-1, dp(48)))
        form.addView(text("Направление контроля", 14, primaryTextColor(), Typeface.BOLD).apply {
            setPadding(0, dp(10), 0, dp(4))
        })
        val directionSpinner = Spinner(this).apply {
            adapter = scaledTextAdapter(
                listOf(
                    "Все направления",
                    "Навстречу потоку",
                    "Два встречных направления",
                    "В спину потоку",
                    "В лицо и в спину потоку",
                ),
                android.R.layout.simple_spinner_item,
                android.R.layout.simple_spinner_dropdown_item,
            )
            setSelection(max(0, directionTypes.indexOf(existing.dirType)))
        }
        form.addView(directionSpinner, LinearLayout.LayoutParams(-1, dp(48)))
        val directionControl = steppedSeekControl(
            "Направление", 0, 359, 1, existing.direction.roundToInt(),
        ) { "$it°" }
        form.addView(directionControl.first, LinearLayout.LayoutParams(-1, dp(76)))
        val distanceControl = steppedSeekControl(
            "Дистанция зоны", 50, 1500, 50, existing.distanceMeters,
        )
        form.addView(distanceControl.first, LinearLayout.LayoutParams(-1, dp(76)))
        val reverseDistanceControl = steppedSeekControl(
            "Обратная дистанция", 0, 1500, 50, existing.reverseDistanceMeters,
        )
        form.addView(reverseDistanceControl.first, LinearLayout.LayoutParams(-1, dp(76)))
        val angleControl = steppedSeekControl(
            "Угол сектора", 0, 180, 5, existing.angleDegrees.roundToInt(),
        )
        form.addView(angleControl.first, LinearLayout.LayoutParams(-1, dp(76)))
        val speedControl = steppedSeekControl(
            "Ограничение скорости", 0, 300, 1, existing.currentSpeedLimit(),
        ) { if (it == 0) "нет" else "$it км/ч" }
        form.addView(speedControl.first, LinearLayout.LayoutParams(-1, dp(76)))

        val scroll = ScrollView(this).apply { addView(form, FrameLayout.LayoutParams(-1, -2)) }
        val dialog = AlertDialog.Builder(this, dialogTheme())
            .setTitle("Редактировать объект")
            .setView(scroll)
            .setPositiveButton("Сохранить", null)
            .setNegativeButton("Отмена", null)
            .create()
        dialog.setOnShowListener {
            styleRoundedDialog(dialog)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { saveButton ->
                val direction = directionControl.second.progress
                val speed = speedControl.second.progress
                saveButton.isEnabled = false
                val dirType = directionTypes[directionSpinner.selectedItemPosition]
                val point = existing.detachedCopy().apply {
                    type = objectTypes[typeSpinner.selectedItemPosition]
                    this.dirType = dirType
                    this.direction = direction.toFloat()
                    distanceMeters = 50 + distanceControl.second.progress * 50
                    reverseDistanceMeters = reverseDistanceControl.second.progress * 50
                    angleDegrees = (angleControl.second.progress * 5).toFloat()
                    speedRules = if (speed > 0) {
                        SpeedControlRules.encode(speed, false, -1, -1, 0, 0, SpeedControlRules.CAR)
                    } else {
                        ""
                    }
                }
                Thread({
                    val saved = runCatching {
                        CameraDatabase(this@MainActivity).use { it.updateUserObject(point) }
                    }.getOrDefault(false)
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        if (saved) {
                            dialog.dismiss()
                            refreshAfterUserObjectChange()
                            Toast.makeText(
                                this,
                                "Изменения сохранены",
                                Toast.LENGTH_SHORT,
                            ).show()
                        } else {
                            saveButton.isEnabled = true
                            Toast.makeText(this, "Не удалось сохранить объект", Toast.LENGTH_LONG).show()
                        }
                    }
                }, "user-object-update").start()
            }
        }
        dialog.show()
    }

    private fun saveMovedUserObject(camera: CameraPoint, position: Point) {
        val moved = camera.detachedCopy().apply {
            latitude = position.latitude
            longitude = position.longitude
        }
        Thread({
            val saved = runCatching {
                CameraDatabase(this@MainActivity).use { it.updateUserObject(moved) }
            }.getOrDefault(false)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                refreshAfterUserObjectChange()
                Toast.makeText(
                    this,
                    if (saved) "Новое положение сохранено" else "Не удалось переместить объект",
                    if (saved) Toast.LENGTH_SHORT else Toast.LENGTH_LONG,
                ).show()
            }
        }, "user-object-move").start()
    }

    private fun steppedSeekControl(
        label: String,
        minimum: Int,
        maximum: Int,
        step: Int,
        initial: Int,
        formatValue: (Int) -> String = { it.toString() },
    ): Pair<LinearLayout, SeekBar> {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, 0)
        }
        val valueLabel = text("", 14, primaryTextColor(), Typeface.NORMAL)
        val seekBar = SeekBar(this).apply {
            max = (maximum - minimum) / step
            progress = ((initial.coerceIn(minimum, maximum) - minimum) / step)
        }
        fun updateLabel() {
            valueLabel.text = "$label: ${formatValue(minimum + seekBar.progress * step)}"
        }
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) = updateLabel()
            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
        updateLabel()
        row.addView(valueLabel, LinearLayout.LayoutParams(-1, -2))
        row.addView(sliderWithStepButtons(seekBar, label), LinearLayout.LayoutParams(-1, dp(48)))
        return row to seekBar
    }

    private fun sliderWithStepButtons(
        seekBar: SeekBar,
        label: String,
        afterStep: (SeekBar) -> Unit = {},
    ): LinearLayout {
        fun step(delta: Int) {
            val previous = seekBar.progress
            seekBar.progress = (previous + delta).coerceIn(0, seekBar.max)
            if (seekBar.progress != previous) afterStep(seekBar)
        }
        val minusButton = sliderStepButton("−", "Уменьшить: $label") { step(-1) }
        val plusButton = sliderStepButton("+", "Увеличить: $label") { step(1) }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(minusButton, LinearLayout.LayoutParams(dp(40), dp(40)))
            addView(seekBar, LinearLayout.LayoutParams(0, dp(48), 1f))
            addView(plusButton, LinearLayout.LayoutParams(dp(40), dp(40)))
        }
    }

    private fun sliderStepButton(
        caption: String,
        description: String,
        action: () -> Unit,
    ): Button = Button(this).apply {
        text = caption
        contentDescription = description
        textSize = scaledSp(22)
        setTextColor(primaryTextColor())
        background = roundedBackground(inputSurfaceColor(), 8)
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(0, 0, 0, 0)
        setOnClickListener { action() }
    }

    private fun confirmDeleteUserObject(point: CameraPoint) {
        val dialog = AlertDialog.Builder(this, dialogTheme())
            .setTitle("Удалить объект?")
            .setMessage(point.typeName())
            .setPositiveButton("Удалить") { _, _ ->
                Thread({
                    val deleted = runCatching {
                        CameraDatabase(this@MainActivity).use { it.deleteUserObject(point.id) }
                    }.getOrDefault(false)
                    runOnUiThread {
                        if (deleted) refreshAfterUserObjectChange()
                        Toast.makeText(
                            this,
                            if (deleted) "Объект удалён" else "Не удалось удалить объект",
                            if (deleted) Toast.LENGTH_SHORT else Toast.LENGTH_LONG,
                        ).show()
                    }
                }, "user-object-delete").start()
            }
            .setNegativeButton("Отмена", null)
            .create()
        dialog.show()
        styleRoundedDialog(dialog)
    }

    private fun refreshAfterUserObjectChange() {
        refreshDatabaseCount()
        refreshAboutDatabaseInfo()
        cameraMapLayer?.refreshVisible()
    }

    private fun showAlertsMenu() {
        val content = menuContent()
        content.addView(overspeedThresholdRow(), LinearLayout.LayoutParams(-1, dp(86)))
        showMenuDialog("Оповещения", content, backToRoot = true)
    }

    private fun showMapMenu() {
        val content = menuContent()
        content.addView(mapScaleRow(), LinearLayout.LayoutParams(-1, dp(86)))
        content.addView(locationArrowScaleRow(), LinearLayout.LayoutParams(-1, dp(86)))
        content.addView(autoRotateRow(), LinearLayout.LayoutParams(-1, dp(54)))
        val zoneDisplayMode = ZoneDisplayMode.fromStored(
            settingsPreferences.getString(AppSettings.ZONE_DISPLAY_MODE, ZoneDisplayMode.ALL.name),
        )
        val zoneObjectScope = ZoneObjectScope.fromStored(
            settingsPreferences.getString(
                AppSettings.ZONE_OBJECT_SCOPE,
                ZoneObjectScope.CAMERAS_ONLY.name,
            ),
        )
        val zoneDisplay = menuAction(
            R.drawable.ic_zones,
            "Отображение зон: ${zoneDisplayMode.title()} · ${zoneObjectScope.title()}",
        )
        content.addView(zoneDisplay, LinearLayout.LayoutParams(-1, dp(54)))
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
        val dialog = showMenuDialog("Карта", content, backToRoot = true)
        zoneDisplay.setOnClickListener { showZoneDisplayDialog(); dialog.dismiss() }
    }

    private fun showInterfaceMenu() {
        val content = menuContent()
        content.addView(hudTransparencyRow(), LinearLayout.LayoutParams(-1, dp(86)))
        content.addView(uiScaleRow(), LinearLayout.LayoutParams(-1, dp(86)))
        val theme = menuAction(R.drawable.ic_theme, "Тема: ${ThemeSettings.mode(this).title()}")
        content.addView(theme, LinearLayout.LayoutParams(-1, dp(54)))
        val dialog = showMenuDialog("Интерфейс", content, backToRoot = true)
        theme.setOnClickListener {
            showThemeDialog()
            dialog.dismiss()
        }
    }

    private fun showApplicationMenu() {
        val content = menuContent()
        val update = menuAction(R.drawable.ic_refresh, "Обновить базу объектов")
        val about = menuAction(R.drawable.ic_info, "О программе")
        listOf(update, about).forEach {
            content.addView(it, LinearLayout.LayoutParams(-1, dp(54)))
        }
        val dialog = showMenuDialog("Приложение", content, backToRoot = true)
        update.setOnClickListener {
            dialog.dismiss()
            (application as GpsAntiRadarApplication).radarBaseUpdater().requestUpdate()
        }
        about.setOnClickListener { showAboutDialog(); dialog.dismiss() }
    }

    private fun menuContent(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8), dp(6), dp(8), dp(4))
    }

    private fun showMenuDialog(
        title: String,
        content: LinearLayout,
        backToRoot: Boolean = false,
    ): AlertDialog {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(content, FrameLayout.LayoutParams(-1, -2))
        }
        return AlertDialog.Builder(this, dialogTheme())
            .setTitle(title)
            .setView(scroll)
            .setNegativeButton(if (backToRoot) "Назад" else "Закрыть") { _, _ ->
                if (backToRoot) showAppMenu()
            }
            .create()
            .also { dialog ->
                trackSettingsMenuDialog(dialog)
                dialog.show()
                styleRoundedDialog(dialog)
            }
    }

    private fun trackSettingsMenuDialog(
        dialog: AlertDialog,
        afterDismiss: () -> Unit = {},
    ) {
        settingsMenuDialogCount++
        dialog.setOnDismissListener {
            afterDismiss()
            settingsMenuDialogCount = max(0, settingsMenuDialogCount - 1)
            if (settingsMenuDialogCount == 0) applyScaleSettingsIfChanged()
        }
    }

    private fun overspeedThresholdRow(): LinearLayout {
        val row = valueRow(R.drawable.ic_speed_limit)
        val controls = row.getChildAt(1) as LinearLayout
        val label = text("", 15, primaryTextColor(), Typeface.NORMAL)
        controls.addView(label)
        val seekBar = SeekBar(this).apply { max = AppSettings.MAX_OVERSPEED_THRESHOLD_KMH }
        val saved = AppSettings.clampOverspeedThreshold(
            settingsPreferences.getInt(
                AppSettings.OVERSPEED_THRESHOLD,
                AppSettings.DEFAULT_OVERSPEED_THRESHOLD_KMH,
            ),
        )
        seekBar.progress = saved
        label.text = "Предел превышения скорости: $saved км/ч"
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val value = AppSettings.clampOverspeedThreshold(progress)
                label.text = "Предел превышения скорости: $value км/ч"
                if (fromUser) settingsPreferences.edit()
                    .putInt(AppSettings.OVERSPEED_THRESHOLD, value).apply()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        controls.addView(
            sliderWithStepButtons(seekBar, "Предел превышения скорости") { stepped ->
                val value = AppSettings.clampOverspeedThreshold(stepped.progress)
                settingsPreferences.edit().putInt(AppSettings.OVERSPEED_THRESHOLD, value).apply()
            },
            LinearLayout.LayoutParams(-1, dp(48)),
        )
        return row
    }

    private fun locationArrowScaleRow(): LinearLayout {
        val row = valueRow(R.drawable.ic_navigation)
        val controls = row.getChildAt(1) as LinearLayout
        val label = text("", 15, primaryTextColor(), Typeface.NORMAL)
        controls.addView(label)
        val seekBar = SeekBar(this).apply {
            max = (AppSettings.MAX_LOCATION_ARROW_SCALE_TENTHS -
                AppSettings.MIN_LOCATION_ARROW_SCALE_TENTHS) /
                AppSettings.LOCATION_ARROW_SCALE_STEP_TENTHS
        }
        val saved = AppSettings.clampLocationArrowScale(
            settingsPreferences.getInt(
                AppSettings.LOCATION_ARROW_SCALE,
                AppSettings.DEFAULT_LOCATION_ARROW_SCALE_TENTHS,
            ),
        )
        seekBar.progress = (saved - AppSettings.MIN_LOCATION_ARROW_SCALE_TENTHS) /
            AppSettings.LOCATION_ARROW_SCALE_STEP_TENTHS
        label.text = "Размер стрелки: ${formatArrowScale(saved)}"
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val value = AppSettings.clampLocationArrowScale(
                    AppSettings.MIN_LOCATION_ARROW_SCALE_TENTHS +
                        progress * AppSettings.LOCATION_ARROW_SCALE_STEP_TENTHS,
                )
                label.text = "Размер стрелки: ${formatArrowScale(value)}"
                if (fromUser) settingsPreferences.edit()
                    .putInt(AppSettings.LOCATION_ARROW_SCALE, value).apply()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        controls.addView(
            sliderWithStepButtons(seekBar, "Размер стрелки") { stepped ->
                val value = AppSettings.clampLocationArrowScale(
                    AppSettings.MIN_LOCATION_ARROW_SCALE_TENTHS +
                        stepped.progress * AppSettings.LOCATION_ARROW_SCALE_STEP_TENTHS,
                )
                settingsPreferences.edit().putInt(AppSettings.LOCATION_ARROW_SCALE, value).apply()
            },
            LinearLayout.LayoutParams(-1, dp(48)),
        )
        return row
    }

    private fun hudTransparencyRow(): LinearLayout {
        val row = valueRow(R.drawable.ic_opacity)
        val controls = row.getChildAt(1) as LinearLayout
        val label = text("", 15, primaryTextColor(), Typeface.NORMAL)
        controls.addView(label)
        val seekBar = SeekBar(this).apply {
            max = (AppSettings.MAX_HUD_TRANSPARENCY_PERCENT -
                AppSettings.MIN_HUD_TRANSPARENCY_PERCENT) / AppSettings.HUD_TRANSPARENCY_STEP_PERCENT
        }
        val saved = AppSettings.clampHudTransparency(
            settingsPreferences.getInt(
                AppSettings.HUD_TRANSPARENCY,
                AppSettings.DEFAULT_HUD_TRANSPARENCY_PERCENT,
            ),
        )
        seekBar.progress = (saved - AppSettings.MIN_HUD_TRANSPARENCY_PERCENT) /
            AppSettings.HUD_TRANSPARENCY_STEP_PERCENT
        label.text = "Прозрачность HUD: $saved%"
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val value = AppSettings.clampHudTransparency(
                    AppSettings.MIN_HUD_TRANSPARENCY_PERCENT +
                        progress * AppSettings.HUD_TRANSPARENCY_STEP_PERCENT,
                )
                label.text = "Прозрачность HUD: $value%"
                settingsPreferences.edit().putInt(AppSettings.HUD_TRANSPARENCY, value).apply()
                applyHudTransparency(value)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        controls.addView(
            sliderWithStepButtons(seekBar, "Прозрачность HUD"),
            LinearLayout.LayoutParams(-1, dp(48)),
        )
        return row
    }

    private fun autoRotateRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_navigation)
            tintIcon(this)
        }
        row.addView(icon, LinearLayout.LayoutParams(dp(28), dp(28)))
        val toggle = Switch(this).apply {
            text = "Автоповорот карты"
            textSize = scaledSp(15)
            setTextColor(primaryTextColor())
            isChecked = settingsPreferences.getBoolean(
                AppSettings.AUTO_ROTATE_MAP,
                AppSettings.DEFAULT_AUTO_ROTATE_MAP,
            )
            setOnCheckedChangeListener { _, enabled ->
                settingsPreferences.edit().putBoolean(AppSettings.AUTO_ROTATE_MAP, enabled).apply()
            }
        }
        row.addView(toggle, LinearLayout.LayoutParams(0, -1, 1f).apply {
            setMargins(dp(14), 0, 0, 0)
        })
        return row
    }

    private fun valueRow(iconResource: Int): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        val icon = ImageView(this).apply {
            setImageResource(iconResource)
            tintIcon(this)
        }
        row.addView(icon, LinearLayout.LayoutParams(dp(28), dp(28)))
        row.addView(
            LinearLayout(this).apply { orientation = LinearLayout.VERTICAL },
            LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(14), 0, 0, 0) },
        )
        return row
    }

    private fun formatArrowScale(value: Int): String = String.format(
        java.util.Locale.forLanguageTag("ru-RU"),
        "%.1f",
        AppSettings.locationArrowScale(value),
    )

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
        controls.addView(
            sliderWithStepButtons(seekBar, label) { stepped ->
                val value = AppSettings.clampZoneTransparency(
                    AppSettings.MIN_ZONE_TRANSPARENCY_PERCENT +
                        stepped.progress * AppSettings.ZONE_TRANSPARENCY_STEP_PERCENT,
                )
                settingsPreferences.edit().putInt(preferenceKey, value).apply()
            },
            LinearLayout.LayoutParams(-1, dp(48)),
        )
        return row
    }

    private fun showZoneDisplayDialog() {
        val currentMode = ZoneDisplayMode.fromStored(
            settingsPreferences.getString(AppSettings.ZONE_DISPLAY_MODE, ZoneDisplayMode.ALL.name),
        )
        val currentScope = ZoneObjectScope.fromStored(
            settingsPreferences.getString(
                AppSettings.ZONE_OBJECT_SCOPE,
                ZoneObjectScope.CAMERAS_ONLY.name,
            ),
        )
        val content = menuContent()
        content.addView(text("Режим отображения", 16, primaryTextColor(), Typeface.BOLD).apply {
            setPadding(dp(12), dp(8), dp(12), dp(2))
        })
        val modeGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        ZoneDisplayMode.entries.forEach { mode ->
            modeGroup.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = mode.title()
                tag = mode
                isChecked = mode == currentMode
                textSize = scaledSp(15)
                setTextColor(primaryTextColor())
                setPadding(dp(12), 0, dp(12), 0)
            }, RadioGroup.LayoutParams(-1, dp(46)))
        }
        content.addView(modeGroup, LinearLayout.LayoutParams(-1, -2))
        content.addView(text("Типы объектов", 16, primaryTextColor(), Typeface.BOLD).apply {
            setPadding(dp(12), dp(12), dp(12), dp(2))
        })
        val scopeGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        ZoneObjectScope.entries.forEach { scope ->
            scopeGroup.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = scope.title()
                tag = scope
                isChecked = scope == currentScope
                textSize = scaledSp(15)
                setTextColor(primaryTextColor())
                setPadding(dp(12), 0, dp(12), 0)
            }, RadioGroup.LayoutParams(-1, dp(46)))
        }
        content.addView(scopeGroup, LinearLayout.LayoutParams(-1, -2))
        modeGroup.setOnCheckedChangeListener { group, checkedId ->
            val mode = group.findViewById<RadioButton>(checkedId)?.tag as? ZoneDisplayMode
                ?: return@setOnCheckedChangeListener
            settingsPreferences.edit().putString(AppSettings.ZONE_DISPLAY_MODE, mode.name).apply()
        }
        scopeGroup.setOnCheckedChangeListener { group, checkedId ->
            val scope = group.findViewById<RadioButton>(checkedId)?.tag as? ZoneObjectScope
                ?: return@setOnCheckedChangeListener
            settingsPreferences.edit().putString(AppSettings.ZONE_OBJECT_SCOPE, scope.name).apply()
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(content, FrameLayout.LayoutParams(-1, -2))
        }
        val dialog = AlertDialog.Builder(this, dialogTheme())
            .setTitle("Отображение зон")
            .setView(scroll)
            .setNegativeButton("Назад") { _, _ -> showMapMenu() }
            .create()
        trackSettingsMenuDialog(dialog)
        dialog.show()
        styleRoundedDialog(dialog)
    }

    private fun showThemeDialog() {
        val current = ThemeSettings.mode(this)
        val modes = ThemeMode.entries.toTypedArray()
        val titles = List(modes.size) { modes[it].title() }
        val dialog = AlertDialog.Builder(this, dialogTheme())
            .setTitle("Тема")
            .setSingleChoiceItems(
                scaledTextAdapter(
                    titles,
                    android.R.layout.simple_list_item_single_choice,
                ),
                current.ordinal,
            ) { choice, which ->
                val selected = modes[which]
                if (selected != ThemeSettings.mode(this@MainActivity)) {
                    ThemeSettings.setMode(this@MainActivity, selected)
                    refreshThemeIfNeeded()
                }
                choice.dismiss()
            }
            .setNegativeButton("Отмена", null)
            .create()
        trackSettingsMenuDialog(dialog)
        dialog.show()
        styleRoundedDialog(dialog)
    }

    private fun uiScaleRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_car_zoom_in)
            tintIcon(this)
        }
        row.addView(icon, LinearLayout.LayoutParams(dp(28), dp(28)))
        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        row.addView(controls, LinearLayout.LayoutParams(0, -2, 1f).apply {
            setMargins(dp(14), 0, 0, 0)
        })
        val selected = selectedUiScalePercent()
        val valueLabel = text("Масштаб интерфейса: $selected%", 15, primaryTextColor(), Typeface.NORMAL)
        controls.addView(valueLabel)
        val seekBar = SeekBar(this).apply {
            max = (AppSettings.MAX_UI_SCALE_PERCENT - AppSettings.MIN_UI_SCALE_PERCENT) /
                AppSettings.UI_SCALE_STEP_PERCENT
            progress = (selected - AppSettings.MIN_UI_SCALE_PERCENT) /
                AppSettings.UI_SCALE_STEP_PERCENT
        }
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val value = AppSettings.MIN_UI_SCALE_PERCENT +
                    progress * AppSettings.UI_SCALE_STEP_PERCENT
                valueLabel.text = "Масштаб интерфейса: $value%"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                val value = AppSettings.MIN_UI_SCALE_PERCENT +
                    seekBar.progress * AppSettings.UI_SCALE_STEP_PERCENT
                settingsPreferences.edit()
                    .putInt(AppSettings.UI_SCALE_PERCENT, value)
                    .commit()
            }
        })
        controls.addView(
            sliderWithStepButtons(seekBar, "Масштаб интерфейса") { stepped ->
                val value = AppSettings.MIN_UI_SCALE_PERCENT +
                    stepped.progress * AppSettings.UI_SCALE_STEP_PERCENT
                settingsPreferences.edit().putInt(AppSettings.UI_SCALE_PERCENT, value).commit()
            },
            LinearLayout.LayoutParams(-1, dp(48)),
        )
        return row
    }

    private fun mapScaleRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_car_zoom_in)
            tintIcon(this)
        }
        row.addView(icon, LinearLayout.LayoutParams(dp(28), dp(28)))
        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        row.addView(controls, LinearLayout.LayoutParams(0, -2, 1f).apply {
            setMargins(dp(14), 0, 0, 0)
        })
        val selected = selectedMapScalePercent()
        val valueLabel = text("Масштаб карты: $selected%", 15, primaryTextColor(), Typeface.NORMAL)
        controls.addView(valueLabel)
        val seekBar = SeekBar(this).apply {
            max = (AppSettings.MAX_MAP_SCALE_PERCENT - AppSettings.MIN_MAP_SCALE_PERCENT) /
                AppSettings.MAP_SCALE_STEP_PERCENT
            progress = (selected - AppSettings.MIN_MAP_SCALE_PERCENT) /
                AppSettings.MAP_SCALE_STEP_PERCENT
        }
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val value = AppSettings.MIN_MAP_SCALE_PERCENT +
                    progress * AppSettings.MAP_SCALE_STEP_PERCENT
                valueLabel.text = "Масштаб карты: $value%"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                val value = AppSettings.MIN_MAP_SCALE_PERCENT +
                    seekBar.progress * AppSettings.MAP_SCALE_STEP_PERCENT
                settingsPreferences.edit()
                    .putInt(AppSettings.MAP_SCALE_PERCENT, value)
                    .commit()
            }
        })
        controls.addView(
            sliderWithStepButtons(seekBar, "Масштаб карты") { stepped ->
                val value = AppSettings.MIN_MAP_SCALE_PERCENT +
                    stepped.progress * AppSettings.MAP_SCALE_STEP_PERCENT
                settingsPreferences.edit().putInt(AppSettings.MAP_SCALE_PERCENT, value).commit()
            },
            LinearLayout.LayoutParams(-1, dp(48)),
        )
        return row
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
        trackSettingsMenuDialog(dialog) {
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
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = roundedBackground(controlSurfaceColor(), 7)
            elevation = dp(4).toFloat()
        }

    private fun segmentedIconButton(iconResource: Int, description: String): ImageButton =
        ImageButton(this).apply {
            setImageResource(iconResource)
            tintIcon(this)
            contentDescription = description
            scaleType = ImageView.ScaleType.FIT_CENTER
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

    private fun styleRoundedDialog(dialog: AlertDialog) {
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val parentPanelId = resources.getIdentifier("parentPanel", "id", "android")
        dialog.findViewById<View>(parentPanelId)?.let { parentPanel ->
            parentPanel.background = roundedBackground(dialogSurfaceColor(), 14)
            parentPanel.clipToOutline = true
        }
        val alertTitleId = resources.getIdentifier("alertTitle", "id", "android")
        dialog.findViewById<TextView>(alertTitleId)?.textSize = scaledSp(20)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.textSize = scaledSp(14)
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.textSize = scaledSp(14)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.textSize = scaledSp(14)
        val messageId = resources.getIdentifier("message", "id", "android")
        dialog.findViewById<TextView>(messageId)?.textSize = scaledSp(16)
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
            Toast.makeText(this, "Яндекс-карта недоступна", Toast.LENGTH_SHORT).show()
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
        cameraView.text = if (databaseEmpty && !presentation.hasObject) {
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
        cameraMapLayer?.updateActiveCameras(longArrayOf())
    }

    private fun text(value: String, sp: Int, color: Int, style: Int): TextView =
        TextView(this).apply {
            text = value
            textSize = scaledSp(sp)
            setTextColor(color)
            typeface = Typeface.create("sans", style)
        }

    private fun scaledTextAdapter(
        values: List<String>,
        itemLayout: Int,
        dropdownLayout: Int = itemLayout,
        textSp: Int = 16,
    ): ArrayAdapter<String> = object : ArrayAdapter<String>(this, itemLayout, values) {
        init {
            setDropDownViewResource(dropdownLayout)
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            scaleText(super.getView(position, convertView, parent))

        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
            scaleText(super.getDropDownView(position, convertView, parent))

        private fun scaleText(view: View): View = view.apply {
            findViewById<TextView>(android.R.id.text1)?.textSize = scaledSp(textSp)
        }
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

    private fun selectedUiScalePercent(): Int =
        AppSettings.normalizeUiScalePercent(
            settingsPreferences.getInt(
                AppSettings.UI_SCALE_PERCENT,
                AppSettings.DEFAULT_UI_SCALE_PERCENT,
            ),
        )

    private fun selectedMapScalePercent(): Int =
        AppSettings.normalizeMapScalePercent(
            settingsPreferences.getInt(
                AppSettings.MAP_SCALE_PERCENT,
                AppSettings.DEFAULT_MAP_SCALE_PERCENT,
            ),
        )

    private fun applyScaleSettingsIfChanged() {
        val appliedUiScalePercent = (uiScaleFactor * 100f).roundToInt()
        val appliedMapScalePercent = (mapScaleFactor * 100f).roundToInt()
        val scaleChanged = selectedUiScalePercent() != appliedUiScalePercent ||
            selectedMapScalePercent() != appliedMapScalePercent
        if (!isFinishing && !isDestroyed && scaleChanged) {
            recreate()
        }
    }

    private fun scaledSp(value: Int): Float = value * uiScaleFactor

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density * uiScaleFactor).roundToInt()

    private fun dp(value: Float): Float =
        value * resources.displayMetrics.density * uiScaleFactor

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
