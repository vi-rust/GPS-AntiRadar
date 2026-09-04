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
    private static final String MAPKIT_PENDING = "mapkit_startup_pending";
    private static final String MAPKIT_SAFE_MIGRATION = "mapkit_safe_startup_v4";
    private static final String MAPKIT_MARKER_FIX = "mapkit_marker_fix_v5";
    private static final String MAPKIT_KEY_REENTRY = "mapkit_key_reentry_v6";
    private static final String RADARBASE_LAST_SUCCESSFUL_DOWNLOAD =
            AppSettings.RADARBASE_LAST_SUCCESSFUL_DOWNLOAD;
    private static final long HINT_ANIMATION_MS = 220L;

    private TextView speedView;
    private TextView distanceView;
    private TextView cameraView;
    private TextView aboutDatabaseCountView;
    private TextView aboutLastDownloadView;
    private LinearLayout hudPanel;
    private FrameLayout mapOverlay;
    private TextView cameraHintView;
    private MapView mapView;
    private SharedCameraMapLayer cameraMapLayer;
    private boolean mapInitialized;
    private boolean mapRecoveryRequired;
    private boolean hasCurrentLocation;
    private int hintGeneration;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            DrivingSnapshot snapshot = DrivingSnapshotIntent.from(intent);
            DrivingHudPresentation presentation = DrivingHudPresentation.from(snapshot);
            hasCurrentLocation = snapshot.hasLocation();
            speedView.setText(presentation.speedText);
            distanceView.setText(presentation.distanceText);
            cameraView.setText(presentation.cameraText);
            cameraView.setTextColor(presentation.speedColor);
            if (cameraMapLayer != null) {
                cameraMapLayer.updateCurrentLocation(
                        snapshot.latitude, snapshot.longitude, snapshot.speedKmh);
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
                            .remove(MAPKIT_PENDING).apply();
                }
            }, 5000);
        }
    }

    private void applyImmersiveMode() {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
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
        if (!preferences.getBoolean(MAPKIT_KEY_REENTRY, false)) {
            boolean hadStoredKey = preferences.contains(AppSettings.MAPKIT_KEY);
            preferences.edit().putBoolean(MAPKIT_KEY_REENTRY, true)
                    .remove(AppSettings.MAPKIT_KEY).remove(MAPKIT_PENDING).commit();
            mapRecoveryRequired = hadStoredKey;
            if (hadStoredKey) return false;
        }

        // A pending flag from 3.1 can be caused by the old all-markers-at-once crash,
        // not by the API key. Keep the key when upgrading to the viewport renderer.
        if (!preferences.getBoolean(MAPKIT_MARKER_FIX, false)) {
            preferences.edit().putBoolean(MAPKIT_MARKER_FIX, true)
                    .remove(MAPKIT_PENDING).commit();
        }

        // Version 3 initialized MapKit before an Activity existed and could leave the app
        // in a startup crash loop. Discard that stored key once when upgrading.
        if (!preferences.getBoolean(MAPKIT_SAFE_MIGRATION, false)) {
            boolean hadStoredKey = preferences.contains(AppSettings.MAPKIT_KEY);
            preferences.edit()
                    .putBoolean(MAPKIT_SAFE_MIGRATION, true)
                    .remove(AppSettings.MAPKIT_KEY)
                    .remove(MAPKIT_PENDING)
                    .commit();
            mapRecoveryRequired = hadStoredKey;
            if (hadStoredKey) return false;
        }

        if (preferences.getBoolean(MAPKIT_PENDING, false)) {
            preferences.edit().remove(AppSettings.MAPKIT_KEY)
                    .remove(MAPKIT_PENDING).commit();
            mapRecoveryRequired = true;
            return false;
        }

        String savedKey = preferences.getString(AppSettings.MAPKIT_KEY, "");
        String embeddedKey = BuildConfig.MAPKIT_API_KEY == null
                ? "" : BuildConfig.MAPKIT_API_KEY.trim();
        if ((savedKey == null || savedKey.trim().isEmpty()) && embeddedKey.isEmpty()) {
            return false;
        }

        preferences.edit().putBoolean(MAPKIT_PENDING, true).commit();
        boolean initialized = GpsAntiRadarApplication.ensureMapKit(this);
        if (!initialized) {
            preferences.edit().remove(AppSettings.MAPKIT_KEY)
                    .remove(MAPKIT_PENDING).commit();
            mapRecoveryRequired = true;
        }
        return initialized;
    }

    private void buildUi() {
        FrameLayout screen = new FrameLayout(this);
        screen.setBackgroundColor(Color.rgb(242, 244, 246));
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
            } catch (Throwable error) {
                if (cameraMapLayer != null) cameraMapLayer.destroy();
                cameraMapLayer = null;
                mapView = null;
                mapInitialized = false;
                mapRecoveryRequired = true;
                getSharedPreferences(SETTINGS, MODE_PRIVATE).edit()
                        .remove(AppSettings.MAPKIT_KEY).remove(MAPKIT_PENDING).commit();
            }
        }
        if (!mapInitialized) {
            TextView mapNotice = text("Для Яндекс-карты нужен ключ MapKit", 20,
                    Color.DKGRAY, Typeface.BOLD);
            mapNotice.setGravity(Gravity.CENTER);
            screen.addView(mapNotice, new FrameLayout.LayoutParams(-1, -1));
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
        TextView unit = text("км/ч", 13, Color.DKGRAY, Typeface.NORMAL);
        hudPanel.addView(unit);
        distanceView = text("—", 24, Color.rgb(30, 30, 30), Typeface.BOLD);
        hudPanel.addView(distanceView);
        cameraView = text("Объектов впереди нет", 13, GREEN, Typeface.BOLD);
        cameraView.setGravity(Gravity.START);
        hudPanel.addView(cameraView);
        ImageButton menuButton = iconButton(ru.gpsantiradar.app.R.drawable.ic_menu, "Меню");
        menuButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showAppMenu(); }
        });
        FrameLayout.LayoutParams menuParams = new FrameLayout.LayoutParams(dp(44), dp(44),
                Gravity.TOP | Gravity.END);
        menuParams.setMargins(0, dp(6), dp(6), 0);
        overlay.addView(menuButton, menuParams);

        LinearLayout zoomControls = new LinearLayout(this);
        zoomControls.setOrientation(LinearLayout.VERTICAL);
        zoomControls.setGravity(Gravity.CENTER);
        zoomControls.setBackground(roundedBackground(Color.argb(250, 255, 255, 255), 7));
        zoomControls.setElevation(dp(4));
        ImageButton zoomIn = segmentedIconButton(
                ru.gpsantiradar.app.R.drawable.ic_add, "Увеличить карту");
        zoomIn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (cameraMapLayer != null) cameraMapLayer.zoomBy(1f);
            }
        });
        zoomControls.addView(zoomIn, new LinearLayout.LayoutParams(dp(44), dp(44)));
        View zoomDivider = new View(this);
        zoomDivider.setBackgroundColor(Color.rgb(218, 218, 218));
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(-1, dp(1));
        dividerParams.setMargins(dp(7), 0, dp(7), 0);
        zoomControls.addView(zoomDivider, dividerParams);
        ImageButton zoomOut = segmentedIconButton(
                ru.gpsantiradar.app.R.drawable.ic_remove, "Уменьшить карту");
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
        positionButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { centerOnLocation(); }
        });
        FrameLayout.LayoutParams positionParams = new FrameLayout.LayoutParams(dp(44), dp(44),
                Gravity.BOTTOM | Gravity.END);
        positionParams.setMargins(0, 0, dp(6), dp(6));
        overlay.addView(positionButton, positionParams);

        cameraHintView = text("", 14, Color.BLACK, Typeface.BOLD);
        cameraHintView.setMaxWidth(dp(360));
        cameraHintView.setPadding(dp(10), dp(7), dp(10), dp(7));
        cameraHintView.setBackground(roundedBackground(Color.argb(248, 255, 255, 255), 10));
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

        LinearLayout distanceRow = new LinearLayout(this);
        distanceRow.setOrientation(LinearLayout.HORIZONTAL);
        distanceRow.setGravity(Gravity.CENTER_VERTICAL);
        distanceRow.setPadding(dp(12), dp(6), dp(12), dp(6));
        ImageView distanceIcon = new ImageView(this);
        distanceIcon.setImageResource(ru.gpsantiradar.app.R.drawable.ic_distance);
        distanceRow.addView(distanceIcon, new LinearLayout.LayoutParams(dp(28), dp(28)));
        LinearLayout distanceContent = new LinearLayout(this);
        distanceContent.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams distanceContentParams = new LinearLayout.LayoutParams(0, -2, 1f);
        distanceContentParams.setMargins(dp(14), 0, 0, 0);
        distanceRow.addView(distanceContent, distanceContentParams);
        final TextView distanceLabel = text("", 15, Color.rgb(35, 35, 35), Typeface.NORMAL);
        distanceContent.addView(distanceLabel);
        SeekBar range = new SeekBar(this);
        range.setMax((AppSettings.MAX_ALERT_DISTANCE_METERS
                - AppSettings.MIN_ALERT_DISTANCE_METERS)
                / AppSettings.ALERT_DISTANCE_STEP_METERS);
        int savedDistance = AppSettings.clampAlertDistance(
                getSharedPreferences(SETTINGS, MODE_PRIVATE).getInt(
                        AppSettings.ALERT_DISTANCE,
                        AppSettings.DEFAULT_ALERT_DISTANCE_METERS));
        range.setProgress((savedDistance - AppSettings.MIN_ALERT_DISTANCE_METERS)
                / AppSettings.ALERT_DISTANCE_STEP_METERS);
        distanceLabel.setText("Расстояние оповещения: "
                + savedDistance + " м");
        range.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int distance = AppSettings.clampAlertDistance(
                        AppSettings.MIN_ALERT_DISTANCE_METERS
                                + progress * AppSettings.ALERT_DISTANCE_STEP_METERS);
                distanceLabel.setText("Расстояние оповещения: " + distance + " м");
                getSharedPreferences(SETTINGS, MODE_PRIVATE).edit()
                        .putInt(AppSettings.ALERT_DISTANCE, distance).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        distanceContent.addView(range, new LinearLayout.LayoutParams(-1, dp(42)));
        content.addView(distanceRow, new LinearLayout.LayoutParams(-1, dp(86)));

        LinearLayout overspeedRow = new LinearLayout(this);
        overspeedRow.setOrientation(LinearLayout.HORIZONTAL);
        overspeedRow.setGravity(Gravity.CENTER_VERTICAL);
        overspeedRow.setPadding(dp(12), dp(6), dp(12), dp(6));
        ImageView overspeedIcon = new ImageView(this);
        overspeedIcon.setImageResource(ru.gpsantiradar.app.R.drawable.ic_speed_limit);
        overspeedRow.addView(overspeedIcon, new LinearLayout.LayoutParams(dp(28), dp(28)));
        LinearLayout overspeedContent = new LinearLayout(this);
        overspeedContent.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams overspeedContentParams =
                new LinearLayout.LayoutParams(0, -2, 1f);
        overspeedContentParams.setMargins(dp(14), 0, 0, 0);
        overspeedRow.addView(overspeedContent, overspeedContentParams);
        final TextView overspeedLabel = text("", 15,
                Color.rgb(35, 35, 35), Typeface.NORMAL);
        overspeedContent.addView(overspeedLabel);
        SeekBar overspeedThreshold = new SeekBar(this);
        overspeedThreshold.setMax(AppSettings.MAX_OVERSPEED_THRESHOLD_KMH);
        int savedOverspeedThreshold = AppSettings.clampOverspeedThreshold(
                getSharedPreferences(SETTINGS, MODE_PRIVATE).getInt(
                        AppSettings.OVERSPEED_THRESHOLD,
                        AppSettings.DEFAULT_OVERSPEED_THRESHOLD_KMH));
        overspeedThreshold.setProgress(savedOverspeedThreshold);
        overspeedLabel.setText("Предел превышения для beep: "
                + savedOverspeedThreshold + " км/ч");
        overspeedThreshold.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress,
                                                     boolean fromUser) {
                int value = AppSettings.clampOverspeedThreshold(progress);
                overspeedLabel.setText("Предел превышения для beep: " + value + " км/ч");
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
        transparencyRow.addView(transparencyIcon, new LinearLayout.LayoutParams(dp(28), dp(28)));
        LinearLayout transparencyContent = new LinearLayout(this);
        transparencyContent.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams transparencyContentParams =
                new LinearLayout.LayoutParams(0, -2, 1f);
        transparencyContentParams.setMargins(dp(14), 0, 0, 0);
        transparencyRow.addView(transparencyContent, transparencyContentParams);
        final TextView transparencyLabel = text("", 15, Color.rgb(35, 35, 35), Typeface.NORMAL);
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
        transparencyLabel.setText("Прозрачность плашки: "
                + savedTransparency + "%");
        transparency.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int value = AppSettings.clampHudTransparency(
                        AppSettings.MIN_HUD_TRANSPARENCY_PERCENT
                                + progress * AppSettings.HUD_TRANSPARENCY_STEP_PERCENT);
                transparencyLabel.setText("Прозрачность плашки: " + value + "%");
                getSharedPreferences(SETTINGS, MODE_PRIVATE).edit()
                        .putInt(AppSettings.HUD_TRANSPARENCY, value).apply();
                applyHudTransparency(value);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        transparencyContent.addView(transparency, new LinearLayout.LayoutParams(-1, dp(42)));
        content.addView(transparencyRow, new LinearLayout.LayoutParams(-1, dp(86)));

        final LinearLayout mapKey = menuAction(
                ru.gpsantiradar.app.R.drawable.ic_key, "Сменить ключ MapKit");
        content.addView(mapKey, new LinearLayout.LayoutParams(-1, dp(54)));

        final LinearLayout about = menuAction(
                ru.gpsantiradar.app.R.drawable.ic_info, "О программе");
        content.addView(about, new LinearLayout.LayoutParams(-1, dp(54)));

        final LinearLayout exit = menuAction(
                ru.gpsantiradar.app.R.drawable.ic_exit, "\u0412\u044b\u0439\u0442\u0438");
        content.addView(exit, new LinearLayout.LayoutParams(-1, dp(54)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));

        final AlertDialog dialog = new AlertDialog.Builder(this,
                android.R.style.Theme_Material_Light_Dialog_Alert)
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

    private void showAboutDialog() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(16), dp(20), dp(12));

        TextView appName = text("GPS AntiRadar", 22, Color.rgb(30, 30, 30), Typeface.BOLD);
        content.addView(appName, new LinearLayout.LayoutParams(-1, -2));

        TextView currentVersion = text("Версия " + BuildConfig.VERSION_NAME, 14,
                Color.DKGRAY, Typeface.NORMAL);
        LinearLayout.LayoutParams currentVersionParams =
                new LinearLayout.LayoutParams(-1, -2);
        currentVersionParams.setMargins(0, dp(2), 0, dp(16));
        content.addView(currentVersion, currentVersionParams);

        TextView databaseTitle = text("База объектов", 17,
                Color.rgb(30, 30, 30), Typeface.BOLD);
        content.addView(databaseTitle, new LinearLayout.LayoutParams(-1, -2));

        aboutDatabaseCountView = text("Объектов в базе: загрузка…", 14,
                Color.rgb(45, 45, 45), Typeface.NORMAL);
        LinearLayout.LayoutParams databaseCountParams =
                new LinearLayout.LayoutParams(-1, -2);
        databaseCountParams.setMargins(0, dp(4), 0, 0);
        content.addView(aboutDatabaseCountView, databaseCountParams);

        aboutLastDownloadView = text("Последняя успешная загрузка: не выполнялась", 14,
                Color.rgb(45, 45, 45), Typeface.NORMAL);
        LinearLayout.LayoutParams lastDownloadParams =
                new LinearLayout.LayoutParams(-1, -2);
        lastDownloadParams.setMargins(0, dp(2), 0, dp(16));
        content.addView(aboutLastDownloadView, lastDownloadParams);
        refreshAboutDatabaseInfo();

        ReleaseHistory.Entry currentRelease = ReleaseHistory.find(BuildConfig.VERSION_NAME);
        if (currentRelease != null) {
            TextView currentChangesTitle = text("Изменения текущей версии", 17,
                    Color.rgb(30, 30, 30), Typeface.BOLD);
            content.addView(currentChangesTitle, new LinearLayout.LayoutParams(-1, -2));

            TextView currentChanges = text(currentRelease.changes, 14,
                    Color.rgb(45, 45, 45), Typeface.NORMAL);
            currentChanges.setLineSpacing(dp(2), 1f);
            LinearLayout.LayoutParams currentChangesParams =
                    new LinearLayout.LayoutParams(-1, -2);
            currentChangesParams.setMargins(0, dp(4), 0, dp(16));
            content.addView(currentChanges, currentChangesParams);
        }

        TextView historyTitle = text("История релизов", 17,
                Color.rgb(30, 30, 30), Typeface.BOLD);
        content.addView(historyTitle, new LinearLayout.LayoutParams(-1, -2));

        for (ReleaseHistory.Entry release : ReleaseHistory.entries()) {
            if (BuildConfig.VERSION_NAME.equals(release.version)) continue;
            View divider = new View(this);
            divider.setBackgroundColor(Color.rgb(225, 228, 231));
            LinearLayout.LayoutParams dividerParams =
                    new LinearLayout.LayoutParams(-1, dp(1));
            dividerParams.setMargins(0, dp(14), 0, dp(12));
            content.addView(divider, dividerParams);

            TextView version = text("Версия " + release.version, 15,
                    GREEN, Typeface.BOLD);
            content.addView(version, new LinearLayout.LayoutParams(-1, -2));

            TextView changes = text(release.changes, 14,
                    Color.rgb(45, 45, 45), Typeface.NORMAL);
            changes.setLineSpacing(dp(2), 1f);
            LinearLayout.LayoutParams changesParams =
                    new LinearLayout.LayoutParams(-1, -2);
            changesParams.setMargins(0, dp(4), 0, 0);
            content.addView(changes, changesParams);
        }

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));

        AlertDialog dialog = new AlertDialog.Builder(this,
                android.R.style.Theme_Material_Light_Dialog_Alert)
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
        stopService(new Intent(this, TrackingService.class));
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
        row.addView(icon, new LinearLayout.LayoutParams(dp(28), dp(28)));
        TextView title = text(label, 16, Color.rgb(35, 35, 35), Typeface.NORMAL);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -2, 1f);
        titleParams.setMargins(dp(14), 0, 0, 0);
        row.addView(title, titleParams);
        return row;
    }

    private ImageButton iconButton(int iconResource, String description) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(iconResource);
        button.setContentDescription(description);
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setPadding(dp(10), dp(10), dp(10), dp(10));
        button.setBackground(roundedBackground(Color.argb(250, 255, 255, 255), 7));
        button.setElevation(dp(4));
        return button;
    }

    private ImageButton segmentedIconButton(int iconResource, String description) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(iconResource);
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
        cameraHintView.setText(cameraHint(camera));
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
        input.setTextColor(Color.rgb(35, 35, 35));
        input.setHintTextColor(Color.rgb(105, 105, 105));
        input.setPadding(dp(16), dp(8), dp(16), dp(8));
        input.setBackground(roundedBackground(Color.rgb(248, 248, 248), 7));
        String message = mapRecoveryRequired
                ? "Предыдущий ключ был отклонён сервером. Вставьте действующий ключ из раздела «MapKit – мобильный SDK». После сохранения полностью закройте и заново откройте приложение."
                : "Вставьте ключ из раздела «Интерфейсы API → MapKit – мобильный SDK». После сохранения полностью закройте и заново откройте приложение.";
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(12), dp(18), dp(4));
        TextView explanation = text(message + " Ключ хранится только на телефоне.",
                14, Color.rgb(35, 35, 35), Typeface.NORMAL);
        content.addView(explanation, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(-1, dp(52));
        inputParams.setMargins(0, dp(10), 0, 0);
        content.addView(input, inputParams);
        final AlertDialog dialog = new AlertDialog.Builder(this,
                android.R.style.Theme_Material_Light_Dialog_Alert)
                .setView(content)
                .setNegativeButton("Позже", null)
                .setPositiveButton("Сохранить", (buttonDialog, which) -> {
                    String value = input.getText().toString().trim();
                    if (value.isEmpty()) return;
                    getSharedPreferences(SETTINGS, MODE_PRIVATE).edit()
                            .putString(AppSettings.MAPKIT_KEY, value)
                            .remove(MAPKIT_PENDING)
                            .putBoolean(MAPKIT_SAFE_MIGRATION, true)
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
            parentPanel.setBackground(roundedBackground(Color.WHITE, 14));
            parentPanel.setClipToOutline(true);
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
                            if (count == 0) {
                                cameraView.setText("Обновите базу RadarBase");
                                cameraView.setTextColor(Color.DKGRAY);
                            }
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

    private String cameraHint(CameraPoint camera) {
        StringBuilder result = new StringBuilder(camera.typeName());
        int speedLimit = camera.currentSpeedLimit();
        if (speedLimit > 0) {
            result.append(" · ").append(speedLimit).append(" км/ч");
        }
        result.append(camera.isCameraOrControl() ? "\nЗона контроля: " : "\nОповещение: ")
                .append(camera.distanceMeters).append(" м");
        result.append("\nНаправленность: ").append(camera.directionName());
        if (camera.isCameraOrControl() && camera.dirType == 0) {
            result.append("\nФорма зоны: круг");
        }
        return result.toString();
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
        hudPanel.setBackground(roundedBackground(Color.argb(alpha, 255, 255, 255), 14));
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
        IntentFilter filter = new IntentFilter(TrackingService.ACTION_UPDATE);
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
        refreshDatabaseCount();
        if (cameraMapLayer != null) cameraMapLayer.refreshVisible();
        if (mapView != null) {
            ((GpsAntiRadarApplication) getApplication()).acquireMapKit();
            mapView.onStart();
        }
    }

    @Override protected void onStop() {
        if (mapView != null) {
            mapView.onStop();
            ((GpsAntiRadarApplication) getApplication()).releaseMapKit();
        }
        ((GpsAntiRadarApplication) getApplication()).radarBaseUpdater()
                .removeListener(radarBaseUpdateListener);
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
