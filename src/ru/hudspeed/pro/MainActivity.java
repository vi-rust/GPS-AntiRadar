package ru.gpsantiradar.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.yandex.mapkit.ScreenPoint;
import com.yandex.mapkit.geometry.Point;
import com.yandex.mapkit.mapview.MapView;

import java.text.DateFormat;
import java.text.NumberFormat;
import java.util.Date;

public final class MainActivity extends Activity {
    private static final int LOCATION_REQUEST = 100;
    private static final int NOTIFICATION_REQUEST = 102;
    private static final int GREEN = Color.rgb(0, 166, 82);
    private static final String SETTINGS = AppSettings.PREFERENCES;
    private static final String RADARBASE_LAST_SUCCESSFUL_DOWNLOAD =
            AppSettings.RADARBASE_LAST_SUCCESSFUL_DOWNLOAD;
    private static final long HINT_ANIMATION_MS = 220L;
    private static final long THEME_REFRESH_MS = 60_000L;

    private TextView speedView;
    private TextView unitView;
    private TextView distanceView;
    private TextView cameraView;
    private TextView mapNoticeView;
    private TextView aboutDatabaseCountView;
    private TextView aboutLastDownloadView;
    private FrameLayout screenView;
    private LinearLayout hudPanel;
    private LinearLayout zoomControlsView;
    private FrameLayout mapOverlay;
    private View zoomDividerView;
    private ImageButton menuButtonView;
    private ImageButton zoomInButtonView;
    private ImageButton zoomOutButtonView;
    private ImageButton positionButtonView;
    private TextView cameraHintView;
    private MapView mapView;
    private SharedCameraMapLayer cameraMapLayer;
    private boolean mapInitialized;
    private boolean mapRecoveryRequired;
    private boolean hasCurrentLocation;
    private boolean darkTheme;
    private boolean databaseEmpty;
    private boolean trackingStopped;
    private boolean settingsListenerRegistered;
    private int hintGeneration;
    private DrivingSnapshot latestSnapshot = DrivingSnapshot.idle();
    private SharedPreferences settingsPreferences;

    private final SharedPreferences.OnSharedPreferenceChangeListener settingsListener =
            new SharedPreferences.OnSharedPreferenceChangeListener() {
                @Override public void onSharedPreferenceChanged(
                        SharedPreferences sharedPreferences, String key) {
                    if (AppSettings.HUD_TRANSPARENCY.equals(key)) {
                        applyHudTransparency(sharedPreferences.getInt(
                                AppSettings.HUD_TRANSPARENCY,
                                AppSettings.DEFAULT_HUD_TRANSPARENCY_PERCENT));
                    } else if (AppSettings.THEME_MODE.equals(key)) {
                        refreshThemeIfNeeded();
                    } else if (AppSettings.OVERSPEED_THRESHOLD.equals(key)
                            && !trackingStopped) {
                        renderHud(latestSnapshot);
                    }
                }
            };

    private final Runnable themeRefresh = new Runnable() {
        @Override public void run() {
            refreshThemeIfNeeded();
            View decor = getWindow().getDecorView();
            decor.removeCallbacks(this);
            decor.postDelayed(this, THEME_REFRESH_MS);
        }
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (TrackingService.ACTION_STOPPED.equals(intent.getAction())) {
                showTrackingStopped();
                return;
            }
            DrivingSnapshot snapshot = DrivingSnapshotIntent.from(intent);
            latestSnapshot = snapshot;
            trackingStopped = false;
            hasCurrentLocation = snapshot.hasLocation();
            if (snapshot.hasLocation()) {
                ThemeSettings.rememberLocation(MainActivity.this,
                        snapshot.latitude, snapshot.longitude);
                refreshThemeIfNeeded(snapshot.latitude, snapshot.longitude);
            }
            renderHud(snapshot);
            if (cameraMapLayer != null) {
                cameraMapLayer.updateActiveCamera(snapshot.cameraId);
                cameraMapLayer.updateCurrentLocation(
                        snapshot.latitude, snapshot.longitude, snapshot.speedKmh,
                        snapshot.headingDegrees);
            }
        }
    };

    private final BroadcastReceiver radarBaseReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            refreshDatabaseCount();
            refreshAboutDatabaseInfo();
            if (cameraMapLayer != null) cameraMapLayer.refreshVisible();
        }
    };

    private final RadarBaseUpdater.Listener radarBaseUpdateListener =
            new RadarBaseUpdater.Listener() {
                @Override public void onRadarBaseUpdate(RadarBaseUpdateState state) {
                    showRadarBaseUpdateState(state);
                }
            };

    @Override protected void onCreate(Bundle state) {
        settingsPreferences = getSharedPreferences(SETTINGS, MODE_PRIVATE);
        darkTheme = ThemeSettings.isDark(this);
        setTheme(darkTheme ? R.style.AppThemeDark : R.style.AppTheme);
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        applyImmersiveMode();
        mapInitialized = initializeMapKitSafely();
        buildUi();
        refreshDatabaseCount();
        getWindow().getDecorView().post(new Runnable() {
            @Override public void run() { startRequested(); }
        });
        if (mapInitialized) {
            cameraMapLayer.loadInitial(true);
            getWindow().getDecorView().postDelayed(new Runnable() {
                @Override public void run() {
                    getSharedPreferences(SETTINGS, MODE_PRIVATE).edit()
                            .remove(AppSettings.MAPKIT_PENDING).apply();
                }
            }, 5000);
        }
    }

    private void applyImmersiveMode() {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            // Some Android 16 builds dereference PhoneWindow.mDecor here.
            getWindow().getDecorView();
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
            return;
        }
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override protected void onResume() {
        super.onResume();
        refreshThemeIfNeeded();
        applyImmersiveMode();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyImmersiveMode();
    }

    private boolean initializeMapKitSafely() {
        SharedPreferences preferences = getSharedPreferences(SETTINGS, MODE_PRIVATE);

        // Version 3.2 could report an initialized SDK even when the server rejected
        // the entered value. Ask once for a fresh MapKit Mobile SDK key.
        if (!preferences.getBoolean(AppSettings.MAPKIT_KEY_REENTRY, false)) {
            boolean hadStoredKey = preferences.contains(AppSettings.MAPKIT_KEY);
            preferences.edit().putBoolean(AppSettings.MAPKIT_KEY_REENTRY, true)
                    .remove(AppSettings.MAPKIT_KEY)
                    .remove(AppSettings.MAPKIT_PENDING).commit();
            mapRecoveryRequired = hadStoredKey;
            if (hadStoredKey) return false;
        }

        // A pending flag from 3.1 can be caused by the old all-markers-at-once crash,
        // not by the API key. Keep the key when upgrading to the viewport renderer.
        if (!preferences.getBoolean(AppSettings.MAPKIT_MARKER_FIX, false)) {
            preferences.edit().putBoolean(AppSettings.MAPKIT_MARKER_FIX, true)
                    .remove(AppSettings.MAPKIT_PENDING).commit();
        }

        // Version 3 initialized MapKit before an Activity existed and could leave the app
        // in a startup crash loop. Discard that stored key once when upgrading.
        if (!preferences.getBoolean(AppSettings.MAPKIT_SAFE_MIGRATION, false)) {
            boolean hadStoredKey = preferences.contains(AppSettings.MAPKIT_KEY);
            preferences.edit()
                    .putBoolean(AppSettings.MAPKIT_SAFE_MIGRATION, true)
                    .remove(AppSettings.MAPKIT_KEY)
                    .remove(AppSettings.MAPKIT_PENDING)
                    .commit();
            mapRecoveryRequired = hadStoredKey;
            if (hadStoredKey) return false;
        }

        if (preferences.getBoolean(AppSettings.MAPKIT_PENDING, false)) {
            preferences.edit().remove(AppSettings.MAPKIT_KEY)
                    .remove(AppSettings.MAPKIT_PENDING).commit();
            mapRecoveryRequired = true;
            return false;
        }

        String savedKey = preferences.getString(AppSettings.MAPKIT_KEY, "");
        String embeddedKey = BuildConfig.MAPKIT_API_KEY == null
                ? "" : BuildConfig.MAPKIT_API_KEY.trim();
        if ((savedKey == null || savedKey.trim().isEmpty()) && embeddedKey.isEmpty()) {
            return false;
        }

        preferences.edit().putBoolean(AppSettings.MAPKIT_PENDING, true).commit();
        boolean initialized = GpsAntiRadarApplication.ensureMapKit(this);
        if (!initialized) {
            preferences.edit().remove(AppSettings.MAPKIT_KEY)
                    .remove(AppSettings.MAPKIT_PENDING).commit();
            mapRecoveryRequired = true;
        }
        return initialized;
    }

    private void buildUi() {
        FrameLayout screen = new FrameLayout(this);
        screenView = screen;
        screen.setBackgroundColor(screenBackgroundColor());
        if (mapInitialized) {
            try {
                mapView = new MapView(this);
                screen.addView(mapView, new FrameLayout.LayoutParams(-1, -1));
                cameraMapLayer = new SharedCameraMapLayer(
                        this, mapView.getMapWindow(), new SharedCameraMapLayer.Host() {
                    @Override public void postToUi(Runnable action) {
                        runOnUiThread(action);
                    }

                    @Override public void onMarkerPresentationChanged() {
                        if (cameraHintView != null) cameraHintView.setVisibility(View.GONE);
                        hintGeneration++;
                    }

                    @Override public void onCameraTapped(CameraPoint camera, Point position) {
                        showCameraHint(camera, position);
                        final int generation = ++hintGeneration;
                        getWindow().getDecorView().postDelayed(new Runnable() {
                            @Override public void run() {
                                if (generation != hintGeneration) return;
                                hideCameraHintSmoothly(generation);
                            }
                        }, 3000L);
                    }
                });
                cameraMapLayer.setNightMode(darkTheme);
            } catch (Throwable error) {
                if (cameraMapLayer != null) cameraMapLayer.destroy();
                cameraMapLayer = null;
                mapView = null;
                mapInitialized = false;
                mapRecoveryRequired = true;
                getSharedPreferences(SETTINGS, MODE_PRIVATE).edit()
                        .remove(AppSettings.MAPKIT_KEY)
                        .remove(AppSettings.MAPKIT_PENDING).commit();
            }
        }
        if (!mapInitialized) {
            mapNoticeView = text("Для Яндекс-карты нужен ключ MapKit", 20,
                    secondaryTextColor(), Typeface.BOLD);
            mapNoticeView.setGravity(Gravity.CENTER);
            screen.addView(mapNoticeView, new FrameLayout.LayoutParams(-1, -1));
        }

        FrameLayout overlay = new FrameLayout(this);
        mapOverlay = overlay;
        final int sidePadding = dp(12);
        final int topPadding = dp(10);
        final int bottomPadding = dp(10);
        overlay.setPadding(sidePadding, topPadding, sidePadding, bottomPadding);
        if (Build.VERSION.SDK_INT >= 30) {
            overlay.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                @Override public WindowInsets onApplyWindowInsets(View view, WindowInsets windowInsets) {
                    Insets cutout = windowInsets.getInsets(WindowInsets.Type.displayCutout());
                    view.setPadding(sidePadding + cutout.left, topPadding + cutout.top,
                            sidePadding + cutout.right, bottomPadding + cutout.bottom);
                    return windowInsets;
                }
            });
        } else if (Build.VERSION.SDK_INT >= 28) {
            overlay.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                @Override public WindowInsets onApplyWindowInsets(View view, WindowInsets windowInsets) {
                    android.view.DisplayCutout cutout = windowInsets.getDisplayCutout();
                    int left = cutout == null ? 0 : cutout.getSafeInsetLeft();
                    int top = cutout == null ? 0 : cutout.getSafeInsetTop();
                    int right = cutout == null ? 0 : cutout.getSafeInsetRight();
                    int bottom = cutout == null ? 0 : cutout.getSafeInsetBottom();
                    view.setPadding(sidePadding + left, topPadding + top,
                            sidePadding + right, bottomPadding + bottom);
                    return windowInsets;
                }
            });
        }
        screen.addView(overlay, new FrameLayout.LayoutParams(-1, -1));

        hudPanel = new LinearLayout(this);
        hudPanel.setOrientation(LinearLayout.VERTICAL);
        hudPanel.setGravity(Gravity.START);
        hudPanel.setPadding(dp(14), dp(10), dp(14), dp(12));
        applyHudTransparency(getSharedPreferences(SETTINGS, MODE_PRIVATE)
                .getInt(AppSettings.HUD_TRANSPARENCY,
                        AppSettings.DEFAULT_HUD_TRANSPARENCY_PERCENT));
        hudPanel.setElevation(dp(4));
        FrameLayout.LayoutParams hudParams = new FrameLayout.LayoutParams(dp(270), -2,
                Gravity.BOTTOM | Gravity.START);
        hudParams.setMargins(dp(4), 0, 0, dp(4));
        overlay.addView(hudPanel, hudParams);

        TextView title = text("Скорость", 14, GREEN, Typeface.BOLD);
        hudPanel.addView(title);
        speedView = text("0", 46, GREEN, Typeface.BOLD);
        speedView.setIncludeFontPadding(false);
        hudPanel.addView(speedView);
        unitView = text("км/ч", 13, secondaryTextColor(), Typeface.NORMAL);
        hudPanel.addView(unitView);
        distanceView = text("—", 24, primaryTextColor(), Typeface.BOLD);
        hudPanel.addView(distanceView);
        cameraView = text("Объектов впереди нет", 13, GREEN, Typeface.BOLD);
        cameraView.setGravity(Gravity.START);
        hudPanel.addView(cameraView);
        ImageButton menuButton = iconButton(ru.gpsantiradar.app.R.drawable.ic_menu, "Меню");
        menuButtonView = menuButton;
        menuButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showAppMenu(); }
        });
        FrameLayout.LayoutParams menuParams = new FrameLayout.LayoutParams(dp(44), dp(44),
                Gravity.TOP | Gravity.END);
        menuParams.setMargins(0, dp(6), dp(6), 0);
        overlay.addView(menuButton, menuParams);

        LinearLayout zoomControls = new LinearLayout(this);
        zoomControlsView = zoomControls;
        zoomControls.setOrientation(LinearLayout.VERTICAL);
        zoomControls.setGravity(Gravity.CENTER);
        zoomControls.setBackground(roundedBackground(controlSurfaceColor(), 7));
        zoomControls.setElevation(dp(4));
        ImageButton zoomIn = segmentedIconButton(
                ru.gpsantiradar.app.R.drawable.ic_add, "Увеличить карту");
        zoomInButtonView = zoomIn;
        zoomIn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (cameraMapLayer != null) cameraMapLayer.zoomBy(1f);
            }
        });
        zoomControls.addView(zoomIn, new LinearLayout.LayoutParams(dp(44), dp(44)));
        View zoomDivider = new View(this);
        zoomDividerView = zoomDivider;
        zoomDivider.setBackgroundColor(dividerColor());
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(-1, dp(1));
        dividerParams.setMargins(dp(7), 0, dp(7), 0);
        zoomControls.addView(zoomDivider, dividerParams);
        ImageButton zoomOut = segmentedIconButton(
                ru.gpsantiradar.app.R.drawable.ic_remove, "Уменьшить карту");
        zoomOutButtonView = zoomOut;
        zoomOut.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (cameraMapLayer != null) cameraMapLayer.zoomBy(-1f);
            }
        });
        zoomControls.addView(zoomOut, new LinearLayout.LayoutParams(dp(44), dp(44)));
        FrameLayout.LayoutParams zoomParams = new FrameLayout.LayoutParams(dp(44), -2,
                Gravity.END | Gravity.CENTER_VERTICAL);
        zoomParams.setMargins(0, 0, dp(6), 0);
        overlay.addView(zoomControls, zoomParams);

        ImageButton positionButton = iconButton(
                ru.gpsantiradar.app.R.drawable.ic_my_location, "Моя точка");
        positionButtonView = positionButton;
        positionButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { centerOnLocation(); }
        });
        FrameLayout.LayoutParams positionParams = new FrameLayout.LayoutParams(dp(44), dp(44),
                Gravity.BOTTOM | Gravity.END);
        positionParams.setMargins(0, 0, dp(6), dp(6));
        overlay.addView(positionButton, positionParams);

        cameraHintView = text("", 14, primaryTextColor(), Typeface.BOLD);
        cameraHintView.setMaxWidth(dp(360));
        cameraHintView.setPadding(dp(10), dp(7), dp(10), dp(7));
        cameraHintView.setBackground(roundedBackground(hintSurfaceColor(), 10));
        cameraHintView.setElevation(dp(6));
        cameraHintView.setVisibility(View.GONE);
        overlay.addView(cameraHintView, new FrameLayout.LayoutParams(-2, -2));

        setContentView(screen);
    }

    private void showAppMenu() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(8), dp(6), dp(8), dp(4));

        final LinearLayout update = menuAction(
                ru.gpsantiradar.app.R.drawable.ic_refresh, "Обновить базу объектов");
        content.addView(update, new LinearLayout.LayoutParams(-1, dp(54)));

        LinearLayout overspeedRow = new LinearLayout(this);
        overspeedRow.setOrientation(LinearLayout.HORIZONTAL);
        overspeedRow.setGravity(Gravity.CENTER_VERTICAL);
        overspeedRow.setPadding(dp(12), dp(6), dp(12), dp(6));
        ImageView overspeedIcon = new ImageView(this);
        overspeedIcon.setImageResource(ru.gpsantiradar.app.R.drawable.ic_speed_limit);
        tintIcon(overspeedIcon);
        overspeedRow.addView(overspeedIcon, new LinearLayout.LayoutParams(dp(28), dp(28)));
        LinearLayout overspeedContent = new LinearLayout(this);
        overspeedContent.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams overspeedContentParams =
                new LinearLayout.LayoutParams(0, -2, 1f);
        overspeedContentParams.setMargins(dp(14), 0, 0, 0);
        overspeedRow.addView(overspeedContent, overspeedContentParams);
        final TextView overspeedLabel = text("", 15,
                primaryTextColor(), Typeface.NORMAL);
        overspeedContent.addView(overspeedLabel);
        SeekBar overspeedThreshold = new SeekBar(this);
        overspeedThreshold.setMax(AppSettings.MAX_OVERSPEED_THRESHOLD_KMH);
        int savedOverspeedThreshold = AppSettings.clampOverspeedThreshold(
                getSharedPreferences(SETTINGS, MODE_PRIVATE).getInt(
                        AppSettings.OVERSPEED_THRESHOLD,
                        AppSettings.DEFAULT_OVERSPEED_THRESHOLD_KMH));
        overspeedThreshold.setProgress(savedOverspeedThreshold);
        overspeedLabel.setText("Предел превышения скорости: "
                + savedOverspeedThreshold + " км/ч");
        overspeedThreshold.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress,
                                                     boolean fromUser) {
                int value = AppSettings.clampOverspeedThreshold(progress);
                overspeedLabel.setText("Предел превышения скорости: " + value + " км/ч");
                if (fromUser) {
                    getSharedPreferences(SETTINGS, MODE_PRIVATE).edit()
                            .putInt(AppSettings.OVERSPEED_THRESHOLD, value).apply();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        overspeedContent.addView(overspeedThreshold,
                new LinearLayout.LayoutParams(-1, dp(42)));
        content.addView(overspeedRow, new LinearLayout.LayoutParams(-1, dp(86)));

        LinearLayout transparencyRow = new LinearLayout(this);
        transparencyRow.setOrientation(LinearLayout.HORIZONTAL);
        transparencyRow.setGravity(Gravity.CENTER_VERTICAL);
        transparencyRow.setPadding(dp(12), dp(6), dp(12), dp(6));
        ImageView transparencyIcon = new ImageView(this);
        transparencyIcon.setImageResource(ru.gpsantiradar.app.R.drawable.ic_opacity);
        tintIcon(transparencyIcon);
        transparencyRow.addView(transparencyIcon, new LinearLayout.LayoutParams(dp(28), dp(28)));
        LinearLayout transparencyContent = new LinearLayout(this);
        transparencyContent.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams transparencyContentParams =
                new LinearLayout.LayoutParams(0, -2, 1f);
        transparencyContentParams.setMargins(dp(14), 0, 0, 0);
        transparencyRow.addView(transparencyContent, transparencyContentParams);
        final TextView transparencyLabel = text("", 15, primaryTextColor(), Typeface.NORMAL);
        transparencyContent.addView(transparencyLabel);
        SeekBar transparency = new SeekBar(this);
        transparency.setMax((AppSettings.MAX_HUD_TRANSPARENCY_PERCENT
                - AppSettings.MIN_HUD_TRANSPARENCY_PERCENT)
                / AppSettings.HUD_TRANSPARENCY_STEP_PERCENT);
        int savedTransparency = AppSettings.clampHudTransparency(
                getSharedPreferences(SETTINGS, MODE_PRIVATE).getInt(
                        AppSettings.HUD_TRANSPARENCY,
                        AppSettings.DEFAULT_HUD_TRANSPARENCY_PERCENT));
        transparency.setProgress((savedTransparency
                - AppSettings.MIN_HUD_TRANSPARENCY_PERCENT)
                / AppSettings.HUD_TRANSPARENCY_STEP_PERCENT);
        transparencyLabel.setText("Прозрачность HUD: "
                + savedTransparency + "%");
        transparency.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int value = AppSettings.clampHudTransparency(
                        AppSettings.MIN_HUD_TRANSPARENCY_PERCENT
                                + progress * AppSettings.HUD_TRANSPARENCY_STEP_PERCENT);
                transparencyLabel.setText("Прозрачность HUD: " + value + "%");
                getSharedPreferences(SETTINGS, MODE_PRIVATE).edit()
                        .putInt(AppSettings.HUD_TRANSPARENCY, value).apply();
                applyHudTransparency(value);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        transparencyContent.addView(transparency, new LinearLayout.LayoutParams(-1, dp(42)));
        content.addView(transparencyRow, new LinearLayout.LayoutParams(-1, dp(86)));

        LinearLayout autoRotateRow = new LinearLayout(this);
        autoRotateRow.setOrientation(LinearLayout.HORIZONTAL);
        autoRotateRow.setGravity(Gravity.CENTER_VERTICAL);
        autoRotateRow.setPadding(dp(12), dp(4), dp(12), dp(4));
        ImageView autoRotateIcon = new ImageView(this);
        autoRotateIcon.setImageResource(ru.gpsantiradar.app.R.drawable.ic_navigation);
        tintIcon(autoRotateIcon);
        autoRotateRow.addView(autoRotateIcon,
                new LinearLayout.LayoutParams(dp(28), dp(28)));
        Switch autoRotate = new Switch(this);
        autoRotate.setText("Автоповорот карты");
        autoRotate.setTextSize(15);
        autoRotate.setTextColor(primaryTextColor());
        autoRotate.setChecked(getSharedPreferences(SETTINGS, MODE_PRIVATE).getBoolean(
                AppSettings.AUTO_ROTATE_MAP, AppSettings.DEFAULT_AUTO_ROTATE_MAP));
        autoRotate.setOnCheckedChangeListener((button, enabled) ->
                getSharedPreferences(SETTINGS, MODE_PRIVATE).edit()
                        .putBoolean(AppSettings.AUTO_ROTATE_MAP, enabled).apply());
        LinearLayout.LayoutParams autoRotateParams =
                new LinearLayout.LayoutParams(0, -1, 1f);
        autoRotateParams.setMargins(dp(14), 0, 0, 0);
        autoRotateRow.addView(autoRotate, autoRotateParams);
        content.addView(autoRotateRow, new LinearLayout.LayoutParams(-1, dp(54)));

        final LinearLayout theme = menuAction(
                ru.gpsantiradar.app.R.drawable.ic_theme,
                "Тема: " + ThemeSettings.mode(this).title());
        content.addView(theme, new LinearLayout.LayoutParams(-1, dp(54)));

        final LinearLayout mapKey = menuAction(
                ru.gpsantiradar.app.R.drawable.ic_key, "Ключ MapKit");
        content.addView(mapKey, new LinearLayout.LayoutParams(-1, dp(54)));

        final LinearLayout about = menuAction(
                ru.gpsantiradar.app.R.drawable.ic_info, "О программе");
        content.addView(about, new LinearLayout.LayoutParams(-1, dp(54)));

        final LinearLayout exit = menuAction(
                ru.gpsantiradar.app.R.drawable.ic_exit, "Выйти");
        content.addView(exit, new LinearLayout.LayoutParams(-1, dp(54)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));

        final AlertDialog dialog = new AlertDialog.Builder(this,
                dialogTheme())
                .setView(scroll)
                .setNegativeButton("Закрыть", null)
                .create();
        update.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                dialog.dismiss();
                ((GpsAntiRadarApplication) getApplication())
                        .radarBaseUpdater().requestUpdate();
            }
        });
        mapKey.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                dialog.dismiss();
                showMapKeyDialog();
            }
        });
        theme.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                dialog.dismiss();
                showThemeDialog();
            }
        });
        about.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                dialog.dismiss();
                showAboutDialog();
            }
        });
        exit.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                dialog.dismiss();
                exitApplication();
            }
        });
        dialog.show();
        styleRoundedDialog(dialog);
    }

    private void showThemeDialog() {
        ThemeMode current = ThemeSettings.mode(this);
        ThemeMode[] modes = ThemeMode.values();
        String[] titles = new String[modes.length];
        for (int index = 0; index < modes.length; index++) {
            titles[index] = modes[index].title();
        }
        AlertDialog dialog = new AlertDialog.Builder(this, dialogTheme())
                .setTitle("Тема")
                .setSingleChoiceItems(titles, current.ordinal(), (choice, which) -> {
                    ThemeMode selected = modes[which];
                    choice.dismiss();
                    if (selected == ThemeSettings.mode(MainActivity.this)) return;
                    ThemeSettings.setMode(MainActivity.this, selected);
                    refreshThemeIfNeeded();
                })
                .setNegativeButton("Отмена", null)
                .create();
        dialog.show();
        styleRoundedDialog(dialog);
    }

    private void showAboutDialog() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(16), dp(20), dp(12));

        TextView appName = text("GPS AntiRadar", 22, primaryTextColor(), Typeface.BOLD);
        content.addView(appName, new LinearLayout.LayoutParams(-1, -2));

        TextView currentVersion = text("Версия " + BuildConfig.VERSION_NAME, 14,
                secondaryTextColor(), Typeface.NORMAL);
        LinearLayout.LayoutParams currentVersionParams =
                new LinearLayout.LayoutParams(-1, -2);
        currentVersionParams.setMargins(0, dp(2), 0, dp(16));
        content.addView(currentVersion, currentVersionParams);

        TextView databaseTitle = text("База объектов", 17,
                primaryTextColor(), Typeface.BOLD);
        content.addView(databaseTitle, new LinearLayout.LayoutParams(-1, -2));

        aboutDatabaseCountView = text("Объектов в базе: загрузка…", 14,
                secondaryTextColor(), Typeface.NORMAL);
        LinearLayout.LayoutParams databaseCountParams =
                new LinearLayout.LayoutParams(-1, -2);
        databaseCountParams.setMargins(0, dp(4), 0, 0);
        content.addView(aboutDatabaseCountView, databaseCountParams);

        aboutLastDownloadView = text("Последняя успешная загрузка: не выполнялась", 14,
                secondaryTextColor(), Typeface.NORMAL);
        LinearLayout.LayoutParams lastDownloadParams =
                new LinearLayout.LayoutParams(-1, -2);
        lastDownloadParams.setMargins(0, dp(2), 0, dp(16));
        content.addView(aboutLastDownloadView, lastDownloadParams);
        refreshAboutDatabaseInfo();

        ReleaseHistory.Entry currentRelease = ReleaseHistory.find(BuildConfig.VERSION_NAME);
        if (currentRelease != null) {
            TextView currentChangesTitle = text("Изменения текущей версии", 17,
                    primaryTextColor(), Typeface.BOLD);
            content.addView(currentChangesTitle, new LinearLayout.LayoutParams(-1, -2));

            TextView currentChanges = text(currentRelease.changes, 14,
                    secondaryTextColor(), Typeface.NORMAL);
            currentChanges.setLineSpacing(dp(2), 1f);
            LinearLayout.LayoutParams currentChangesParams =
                    new LinearLayout.LayoutParams(-1, -2);
            currentChangesParams.setMargins(0, dp(4), 0, dp(16));
            content.addView(currentChanges, currentChangesParams);
        }

        TextView historyTitle = text("История релизов", 17,
                primaryTextColor(), Typeface.BOLD);
        content.addView(historyTitle, new LinearLayout.LayoutParams(-1, -2));

        for (ReleaseHistory.Entry release : ReleaseHistory.entries()) {
            if (BuildConfig.VERSION_NAME.equals(release.version)) continue;
            View divider = new View(this);
            divider.setBackgroundColor(dividerColor());
            LinearLayout.LayoutParams dividerParams =
                    new LinearLayout.LayoutParams(-1, dp(1));
            dividerParams.setMargins(0, dp(14), 0, dp(12));
            content.addView(divider, dividerParams);

            TextView version = text("Версия " + release.version, 15,
                    GREEN, Typeface.BOLD);
            content.addView(version, new LinearLayout.LayoutParams(-1, -2));

            TextView changes = text(release.changes, 14,
                    secondaryTextColor(), Typeface.NORMAL);
            changes.setLineSpacing(dp(2), 1f);
            LinearLayout.LayoutParams changesParams =
                    new LinearLayout.LayoutParams(-1, -2);
            changesParams.setMargins(0, dp(4), 0, 0);
            content.addView(changes, changesParams);
        }

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));

        AlertDialog dialog = new AlertDialog.Builder(this,
                dialogTheme())
                .setView(scroll)
                .setNegativeButton("Закрыть", null)
                .create();
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface ignored) {
                aboutDatabaseCountView = null;
                aboutLastDownloadView = null;
            }
        });
        dialog.show();
        styleRoundedDialog(dialog);
        if (dialog.getWindow() != null) {
            int availableWidth = getResources().getDisplayMetrics().widthPixels - dp(48);
            int availableHeight = getResources().getDisplayMetrics().heightPixels - dp(48);
            dialog.getWindow().setLayout(Math.min(dp(560), availableWidth),
                    Math.min(dp(520), availableHeight));
        }
    }

    private void exitApplication() {
        TrackingService.requestStop(this);
        finishAndRemoveTask();
    }

    private LinearLayout menuAction(int iconResource, String label) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(6), dp(12), dp(6));
        row.setClickable(true);
        row.setFocusable(true);
        ImageView icon = new ImageView(this);
        icon.setImageResource(iconResource);
        tintIcon(icon);
        row.addView(icon, new LinearLayout.LayoutParams(dp(28), dp(28)));
        TextView title = text(label, 16, primaryTextColor(), Typeface.NORMAL);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -2, 1f);
        titleParams.setMargins(dp(14), 0, 0, 0);
        row.addView(title, titleParams);
        return row;
    }

    private ImageButton iconButton(int iconResource, String description) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(iconResource);
        tintIcon(button);
        button.setContentDescription(description);
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setPadding(dp(10), dp(10), dp(10), dp(10));
        button.setBackground(roundedBackground(controlSurfaceColor(), 7));
        button.setElevation(dp(4));
        return button;
    }

    private ImageButton segmentedIconButton(int iconResource, String description) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(iconResource);
        tintIcon(button);
        button.setContentDescription(description);
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setPadding(dp(10), dp(10), dp(10), dp(10));
        button.setBackgroundColor(Color.TRANSPARENT);
        return button;
    }

    private void showCameraHint(CameraPoint camera, Point point) {
        if (cameraHintView == null || mapView == null || mapOverlay == null) return;
        ScreenPoint screen = mapView.getMapWindow().worldToScreen(point);
        if (screen == null) return;
        cameraHintView.setText(CameraHintFormatter.format(camera));
        int maxWidth = Math.max(dp(180), mapOverlay.getWidth() - dp(24));
        cameraHintView.measure(
                View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(mapOverlay.getHeight(), View.MeasureSpec.AT_MOST));
        int width = cameraHintView.getMeasuredWidth();
        int height = cameraHintView.getMeasuredHeight();
        float x = screen.getX() + dp(18);
        if (x + width > mapOverlay.getWidth() - dp(8)) {
            x = screen.getX() - width - dp(18);
        }
        float y = screen.getY() - height - dp(14);
        x = Math.max(dp(8), Math.min(x, mapOverlay.getWidth() - width - dp(8)));
        y = Math.max(dp(8), Math.min(y, mapOverlay.getHeight() - height - dp(8)));
        cameraHintView.setX(x);
        cameraHintView.setY(y);
        cameraHintView.animate().cancel();
        cameraHintView.setAlpha(0f);
        cameraHintView.setVisibility(View.VISIBLE);
        cameraHintView.bringToFront();
        cameraHintView.animate().alpha(1f).setDuration(HINT_ANIMATION_MS).start();
    }

    private void hideCameraHintSmoothly(final int generation) {
        if (cameraHintView == null || cameraHintView.getVisibility() != View.VISIBLE) return;
        cameraHintView.animate().cancel();
        cameraHintView.animate().alpha(0f).setDuration(HINT_ANIMATION_MS)
                .withEndAction(new Runnable() {
                    @Override public void run() {
                        if (generation == hintGeneration && cameraHintView != null) {
                            cameraHintView.setVisibility(View.GONE);
                        }
                    }
                }).start();
    }

    private void showMapKeyDialog() {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("API-ключ MapKit Mobile SDK");
        input.setTextColor(primaryTextColor());
        input.setHintTextColor(secondaryTextColor());
        input.setPadding(dp(16), dp(8), dp(16), dp(8));
        input.setBackground(roundedBackground(inputSurfaceColor(), 7));
        String message = mapRecoveryRequired
                ? "Предыдущий ключ был отклонён сервером. Вставьте действующий ключ из раздела «MapKit – мобильный SDK». После сохранения полностью закройте и заново откройте приложение."
                : "Вставьте ключ из раздела «Интерфейсы API → MapKit – мобильный SDK». После сохранения полностью закройте и заново откройте приложение.";
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(12), dp(18), dp(4));
        TextView explanation = text(message + " Ключ хранится только на телефоне.",
                14, primaryTextColor(), Typeface.NORMAL);
        content.addView(explanation, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(-1, dp(52));
        inputParams.setMargins(0, dp(10), 0, 0);
        content.addView(input, inputParams);
        final AlertDialog dialog = new AlertDialog.Builder(this,
                dialogTheme())
                .setView(content)
                .setNegativeButton("Позже", null)
                .setPositiveButton("Сохранить", (buttonDialog, which) -> {
                    String value = input.getText().toString().trim();
                    if (value.isEmpty()) return;
                    getSharedPreferences(SETTINGS, MODE_PRIVATE).edit()
                            .putString(AppSettings.MAPKIT_KEY, value)
                            .remove(AppSettings.MAPKIT_PENDING)
                            .putBoolean(AppSettings.MAPKIT_SAFE_MIGRATION, true)
                            .commit();
                    mapRecoveryRequired = false;
                    Toast.makeText(MainActivity.this,
                            "Ключ сохранён. Полностью закройте и откройте приложение",
                            Toast.LENGTH_LONG).show();
                })
                .create();
        dialog.show();
        styleRoundedDialog(dialog);
    }

    private void styleRoundedDialog(AlertDialog dialog) {
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        int parentPanelId = getResources().getIdentifier("parentPanel", "id", "android");
        View parentPanel = dialog.findViewById(parentPanelId);
        if (parentPanel != null) {
            parentPanel.setBackground(roundedBackground(dialogSurfaceColor(), 14));
            parentPanel.setClipToOutline(true);
        }
    }

    private boolean refreshThemeIfNeeded() {
        return applyResolvedTheme(ThemeSettings.isDark(this));
    }

    private boolean refreshThemeIfNeeded(double latitude, double longitude) {
        return applyResolvedTheme(ThemeSettings.isDark(
                this, System.currentTimeMillis(), latitude, longitude));
    }

    private boolean applyResolvedTheme(boolean resolvedDarkTheme) {
        if (resolvedDarkTheme == darkTheme) {
            if (cameraMapLayer != null) cameraMapLayer.setNightMode(darkTheme);
            return false;
        }
        darkTheme = resolvedDarkTheme;
        setTheme(darkTheme ? R.style.AppThemeDark : R.style.AppTheme);
        applyThemeToCurrentViews();
        return true;
    }

    private void applyThemeToCurrentViews() {
        if (screenView != null) screenView.setBackgroundColor(screenBackgroundColor());
        if (mapNoticeView != null) mapNoticeView.setTextColor(secondaryTextColor());
        if (unitView != null) unitView.setTextColor(secondaryTextColor());
        if (distanceView != null) distanceView.setTextColor(primaryTextColor());
        if (zoomControlsView != null) {
            zoomControlsView.setBackground(roundedBackground(controlSurfaceColor(), 7));
        }
        if (zoomDividerView != null) zoomDividerView.setBackgroundColor(dividerColor());
        applyControlTheme(menuButtonView, true);
        applyControlTheme(zoomInButtonView, false);
        applyControlTheme(zoomOutButtonView, false);
        applyControlTheme(positionButtonView, true);
        if (cameraHintView != null) {
            cameraHintView.setTextColor(primaryTextColor());
            cameraHintView.setBackground(roundedBackground(hintSurfaceColor(), 10));
        }
        applyHudTransparency(getSharedPreferences(SETTINGS, MODE_PRIVATE)
                .getInt(AppSettings.HUD_TRANSPARENCY,
                        AppSettings.DEFAULT_HUD_TRANSPARENCY_PERCENT));
        if (cameraMapLayer != null) cameraMapLayer.setNightMode(darkTheme);
    }

    private void applyControlTheme(ImageButton button, boolean withSurface) {
        if (button == null) return;
        tintIcon(button);
        if (withSurface) {
            button.setBackground(roundedBackground(controlSurfaceColor(), 7));
        }
    }

    private void centerOnLocation() {
        if (cameraMapLayer == null) {
            showMapKeyDialog();
            return;
        }
        if (!hasCurrentLocation) {
            Toast.makeText(this, "Дождитесь определения координат GPS",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        cameraMapLayer.moveToCurrentLocation();
    }

    private void startRequested() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, LOCATION_REQUEST);
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_REQUEST);
            return;
        }
        startTracking();
    }

    private void startTracking() {
        Intent intent = new Intent(this, TrackingService.class).setAction(TrackingService.ACTION_START);
        startForegroundService(intent);
    }

    private void showRadarBaseUpdateState(RadarBaseUpdateState state) {
        if (state.status == RadarBaseUpdateState.Status.IDLE) return;
        if (state.status == RadarBaseUpdateState.Status.UNCHANGED) {
            refreshDatabaseCount();
        }
        int duration = state.status == RadarBaseUpdateState.Status.STARTED
                || state.status == RadarBaseUpdateState.Status.ALREADY_RUNNING
                ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG;
        Toast.makeText(this, state.message, duration).show();
    }

    private void refreshDatabaseCount() {
        new Thread(new Runnable() {
            @Override public void run() {
                try (CameraDatabase db = new CameraDatabase(MainActivity.this)) {
                    final int count = db.count();
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            databaseEmpty = count == 0;
                            if (!trackingStopped) renderHud(latestSnapshot);
                        }
                    });
                }
            }
        }, "camera-count").start();
    }

    private void refreshAboutDatabaseInfo() {
        final TextView countTarget = aboutDatabaseCountView;
        final TextView lastDownloadTarget = aboutLastDownloadView;
        if (countTarget == null && lastDownloadTarget == null) return;

        if (lastDownloadTarget != null) {
            long timestamp = getSharedPreferences(SETTINGS, MODE_PRIVATE)
                    .getLong(RADARBASE_LAST_SUCCESSFUL_DOWNLOAD, 0L);
            lastDownloadTarget.setText("Последняя успешная загрузка: "
                    + formatLastSuccessfulDownload(timestamp));
        }
        if (countTarget == null) return;
        countTarget.setText("Объектов в базе: загрузка…");
        new Thread(new Runnable() {
            @Override public void run() {
                try (CameraDatabase db = new CameraDatabase(MainActivity.this)) {
                    final int count = db.count();
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (aboutDatabaseCountView == countTarget) {
                                countTarget.setText("Объектов в базе: "
                                        + NumberFormat.getIntegerInstance().format(count));
                            }
                        }
                    });
                }
            }
        }, "about-camera-count").start();
    }

    private String formatLastSuccessfulDownload(long timestamp) {
        if (timestamp <= 0L) return "не выполнялась";
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(new Date(timestamp));
    }

    private void renderHud(DrivingSnapshot snapshot) {
        int overspeedThresholdKmh = AppSettings.clampOverspeedThreshold(
                settingsPreferences.getInt(AppSettings.OVERSPEED_THRESHOLD,
                        AppSettings.DEFAULT_OVERSPEED_THRESHOLD_KMH));
        DrivingHudPresentation presentation = DrivingHudPresentation.from(
                snapshot, overspeedThresholdKmh);
        speedView.setText(presentation.speedText);
        speedView.setTextColor(presentation.speedColor);
        distanceView.setText(presentation.distanceText);
        cameraView.setText(databaseEmpty && !presentation.hasActiveObject
                ? "База объектов пуста — обновите RadarBase"
                : presentation.cameraText);
        cameraView.setTextColor(presentation.speedColor);
    }

    private void showTrackingStopped() {
        trackingStopped = true;
        latestSnapshot = DrivingSnapshot.idle();
        hasCurrentLocation = false;
        speedView.setText("0");
        speedView.setTextColor(GREEN);
        distanceView.setText("—");
        cameraView.setText("Антирадар остановлен");
        cameraView.setTextColor(GREEN);
        if (cameraMapLayer != null) cameraMapLayer.updateActiveCamera(-1L);
    }

    private TextView text(String value, int sp, int color, int style) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans", style));
        return view;
    }

    private GradientDrawable roundedBackground(int color, int radiusDp) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radiusDp));
        background.setStroke(dp(1), Color.argb(40, 0, 0, 0));
        return background;
    }

    private void applyHudTransparency(int transparencyPercent) {
        if (hudPanel == null) return;
        int transparency = AppSettings.clampHudTransparency(transparencyPercent);
        int alpha = Math.round(255f * (100 - transparency) / 100f);
        int base = darkTheme ? Color.rgb(28, 30, 32) : Color.WHITE;
        hudPanel.setBackground(roundedBackground(Color.argb(alpha,
                Color.red(base), Color.green(base), Color.blue(base)), 14));
    }

    private int screenBackgroundColor() {
        return darkTheme ? Color.rgb(18, 18, 18) : Color.rgb(242, 244, 246);
    }

    private int primaryTextColor() {
        return darkTheme ? Color.rgb(238, 238, 238) : Color.rgb(30, 30, 30);
    }

    private int secondaryTextColor() {
        return darkTheme ? Color.rgb(185, 190, 195) : Color.rgb(70, 70, 70);
    }

    private int controlSurfaceColor() {
        return darkTheme ? Color.argb(250, 32, 35, 38)
                : Color.argb(250, 255, 255, 255);
    }

    private int dialogSurfaceColor() {
        return darkTheme ? Color.rgb(32, 35, 38) : Color.WHITE;
    }

    private int hintSurfaceColor() {
        return darkTheme ? Color.argb(248, 32, 35, 38)
                : Color.argb(248, 255, 255, 255);
    }

    private int inputSurfaceColor() {
        return darkTheme ? Color.rgb(44, 47, 50) : Color.rgb(248, 248, 248);
    }

    private int dividerColor() {
        return darkTheme ? Color.rgb(78, 82, 86) : Color.rgb(218, 218, 218);
    }

    private int dialogTheme() {
        return darkTheme ? android.R.style.Theme_Material_Dialog_Alert
                : android.R.style.Theme_Material_Light_Dialog_Alert;
    }

    private void tintIcon(ImageView icon) {
        if (darkTheme) {
            icon.setColorFilter(Color.rgb(235, 235, 235));
        } else {
            icon.clearColorFilter();
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == LOCATION_REQUEST && results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED) {
            startRequested();
        } else if (requestCode == NOTIFICATION_REQUEST) {
            startTracking();
        } else if (requestCode == LOCATION_REQUEST) {
            Toast.makeText(this, "Для работы нужен доступ к геопозиции", Toast.LENGTH_LONG).show();
            if (!shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter();
        filter.addAction(TrackingService.ACTION_UPDATE);
        filter.addAction(TrackingService.ACTION_STOPPED);
        IntentFilter radarBaseFilter =
                new IntentFilter(RadarBaseUpdater.ACTION_DATABASE_UPDATED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            registerReceiver(radarBaseReceiver, radarBaseFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
            registerReceiver(radarBaseReceiver, radarBaseFilter);
        }
        ((GpsAntiRadarApplication) getApplication()).radarBaseUpdater()
                .addListener(radarBaseUpdateListener, true);
        settingsPreferences.registerOnSharedPreferenceChangeListener(settingsListener);
        settingsListenerRegistered = true;
        refreshDatabaseCount();
        if (cameraMapLayer != null) cameraMapLayer.refreshVisible();
        if (mapView != null) {
            ((GpsAntiRadarApplication) getApplication()).acquireMapKit();
            mapView.onStart();
        }
        View decor = getWindow().getDecorView();
        decor.removeCallbacks(themeRefresh);
        decor.postDelayed(themeRefresh, THEME_REFRESH_MS);
    }

    @Override protected void onStop() {
        getWindow().getDecorView().removeCallbacks(themeRefresh);
        if (mapView != null) {
            mapView.onStop();
            ((GpsAntiRadarApplication) getApplication()).releaseMapKit();
        }
        ((GpsAntiRadarApplication) getApplication()).radarBaseUpdater()
                .removeListener(radarBaseUpdateListener);
        if (settingsListenerRegistered) {
            settingsPreferences.unregisterOnSharedPreferenceChangeListener(settingsListener);
            settingsListenerRegistered = false;
        }
        unregisterReceiver(receiver);
        unregisterReceiver(radarBaseReceiver);
        super.onStop();
    }

    @Override protected void onDestroy() {
        if (cameraMapLayer != null) {
            cameraMapLayer.destroy();
            cameraMapLayer = null;
        }
        super.onDestroy();
    }

}
