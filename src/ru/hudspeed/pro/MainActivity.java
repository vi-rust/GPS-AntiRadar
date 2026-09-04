package ru.gpsantiradar.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
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

import com.yandex.mapkit.Animation;
import com.yandex.mapkit.MapKitFactory;
import com.yandex.mapkit.ScreenPoint;
import com.yandex.mapkit.geometry.LinearRing;
import com.yandex.mapkit.geometry.Point;
import com.yandex.mapkit.geometry.Polygon;
import com.yandex.mapkit.map.CameraPosition;
import com.yandex.mapkit.map.CameraListener;
import com.yandex.mapkit.map.CameraUpdateReason;
import com.yandex.mapkit.map.IconStyle;
import com.yandex.mapkit.map.MapObject;
import com.yandex.mapkit.map.MapObjectCollection;
import com.yandex.mapkit.map.MapObjectTapListener;
import com.yandex.mapkit.map.PlacemarkMapObject;
import com.yandex.mapkit.map.PolygonMapObject;
import com.yandex.mapkit.map.RotationType;
import com.yandex.mapkit.map.VisibleRegion;
import com.yandex.mapkit.mapview.MapView;
import com.yandex.runtime.image.ImageProvider;

import java.io.InputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

public final class MainActivity extends Activity {
    private static final int OBSERVATION_MARKER = -1;
    private static final int LOCATION_REQUEST = 100;
    private static final int NOTIFICATION_REQUEST = 102;
    private static final int GREEN = Color.rgb(0, 166, 82);
    private static final String SETTINGS = "settings";
    private static final String MAPKIT_KEY = "yandex_mapkit_key";
    private static final String MAPKIT_PENDING = "mapkit_startup_pending";
    private static final String MAPKIT_SAFE_MIGRATION = "mapkit_safe_startup_v4";
    private static final String MAPKIT_MARKER_FIX = "mapkit_marker_fix_v5";
    private static final String MAPKIT_KEY_REENTRY = "mapkit_key_reentry_v6";
    private static final String RADARBASE_URL =
            "https://radarbase.info/export/cache/RU/main_extended.json";
    private static final String RADARBASE_ETAG = "radarbase_etag";
    private static final String RADARBASE_MODIFIED = "radarbase_modified";
    private static final String ACTION_RADARBASE_UPDATED =
            "ru.gpsantiradar.app.RADARBASE_UPDATED";
    private static final String RADARBASE_COORDINATE_FIX = "radarbase_coordinate_fix_v1";
    private static final String RADARBASE_IMPORT_FORMAT = "radarbase_import_format";
    private static final int CURRENT_RADARBASE_IMPORT_FORMAT = 2;
    private static final String HUD_TRANSPARENCY = "hud_transparency";
    private static final int MAX_VISIBLE_MARKERS = 5000;
    private static final float COVERAGE_MIN_ZOOM = 13f;
    private static final long HINT_ANIMATION_MS = 220L;
    private static final long FOLLOW_PAUSE_MS = 7000L;

    private TextView speedView;
    private TextView distanceView;
    private TextView cameraView;
    private TextView databaseView;
    private LinearLayout hudPanel;
    private FrameLayout mapOverlay;
    private TextView cameraHintView;
    private MapView mapView;
    private boolean mapInitialized;
    private boolean mapRecoveryRequired;
    private boolean mapCenteredOnGps;
    private long followPausedUntil;
    private double lastLatitude = Double.NaN;
    private double lastLongitude = Double.NaN;
    private PlacemarkMapObject locationPlacemark;
    private MapObjectCollection cameraMarkerCollection;
    private MapObjectCollection cameraCoverageCollection;
    private boolean cameraCoverageVisible;
    private int hintGeneration;
    private int cameraLoadGeneration;
    private final Map<Integer, ImageProvider> markerIcons = new HashMap<>();
    private final Map<Integer, Integer> markerResources = new HashMap<>();
    private final Map<Integer, ImageProvider> clusterIcons = new HashMap<>();
    private final Map<Long, CameraPoint> renderedCameras = new HashMap<>();
    private final Map<Long, List<PolygonMapObject>> renderedCameraCoverage = new HashMap<>();
    private final Map<String, PlacemarkMapObject> renderedMarkerObjects = new HashMap<>();
    private final Map<String, MapMarkerLayout.Entity> renderedMarkerEntities = new HashMap<>();

    private final MapObjectTapListener placemarkTapListener = new MapObjectTapListener() {
        @Override public boolean onMapObjectTap(MapObject mapObject, Point point) {
            Object data = mapObject.getUserData();
            if (data instanceof CameraPoint && mapObject instanceof PlacemarkMapObject) {
                CameraPoint camera = (CameraPoint) data;
                showCameraHint(camera, point);
                final int generation = ++hintGeneration;
                getWindow().getDecorView().postDelayed(new Runnable() {
                    @Override public void run() {
                        if (generation != hintGeneration) return;
                        hideCameraHintSmoothly(generation);
                    }
                }, 3000L);
                return true;
            }
            return false;
        }
    };

    private final CameraListener cameraListener = new CameraListener() {
        @Override public void onCameraPositionChanged(com.yandex.mapkit.map.Map map,
                                                       CameraPosition cameraPosition,
                                                       CameraUpdateReason reason,
                                                       boolean finished) {
            if (reason == CameraUpdateReason.GESTURES) pauseLocationFollowing();
            if (finished) loadVisibleCameraMarkers();
        }
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            float speed = intent.getFloatExtra(TrackingService.EXTRA_SPEED, 0);
            int distance = intent.getIntExtra(TrackingService.EXTRA_DISTANCE, -1);
            int limit = intent.getIntExtra(TrackingService.EXTRA_LIMIT, 0);
            int alertDistance = intent.getIntExtra(TrackingService.EXTRA_ALERT_DISTANCE, 0);
            String camera = intent.getStringExtra(TrackingService.EXTRA_CAMERA);
            lastLatitude = intent.getDoubleExtra(TrackingService.EXTRA_LATITUDE, Double.NaN);
            lastLongitude = intent.getDoubleExtra(TrackingService.EXTRA_LONGITUDE, Double.NaN);
            speedView.setText(Integer.toString(Math.round(speed)));
            if (distance >= 0) {
                distanceView.setText(formatDistance(distance));
                cameraView.setText(camera + (limit > 0 ? "  ·  " + limit + " км/ч" : ""));
                cameraView.setTextColor(distance <= alertDistance
                        ? Color.rgb(255, 190, 55) : GREEN);
            } else {
                distanceView.setText("—");
                cameraView.setText("Объектов впереди не найдено");
                cameraView.setTextColor(GREEN);
            }
            updateLocationMarker();
            centerOnLocationFromGps(speed);
        }
    };

    private final BroadcastReceiver radarBaseReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            refreshDatabaseCount();
            loadVisibleCameraMarkers();
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        prepareRadarBaseMigration();
        mapInitialized = initializeMapKitSafely();
        buildUi();
        refreshDatabaseCount();
        getWindow().getDecorView().post(new Runnable() {
            @Override public void run() { startRequested(); }
        });
        if (((GpsAntiRadarApplication) getApplication()).claimRadarBaseStartupUpdate()) {
            getWindow().getDecorView().post(new Runnable() {
                @Override public void run() { updateRadarBase(); }
            });
        }
        if (mapInitialized) {
            loadCameraMarkers(true);
            getWindow().getDecorView().postDelayed(new Runnable() {
                @Override public void run() {
                    getSharedPreferences(SETTINGS, MODE_PRIVATE).edit()
                            .remove(MAPKIT_PENDING).apply();
                }
            }, 5000);
        }
    }

    private boolean initializeMapKitSafely() {
        SharedPreferences preferences = getSharedPreferences(SETTINGS, MODE_PRIVATE);

        // Version 3.2 could report an initialized SDK even when the server rejected
        // the entered value. Ask once for a fresh MapKit Mobile SDK key.
        if (!preferences.getBoolean(MAPKIT_KEY_REENTRY, false)) {
            boolean hadStoredKey = preferences.contains(MAPKIT_KEY);
            preferences.edit().putBoolean(MAPKIT_KEY_REENTRY, true)
                    .remove(MAPKIT_KEY).remove(MAPKIT_PENDING).commit();
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
            boolean hadStoredKey = preferences.contains(MAPKIT_KEY);
            preferences.edit()
                    .putBoolean(MAPKIT_SAFE_MIGRATION, true)
                    .remove(MAPKIT_KEY)
                    .remove(MAPKIT_PENDING)
                    .commit();
            mapRecoveryRequired = hadStoredKey;
            if (hadStoredKey) return false;
        }

        if (preferences.getBoolean(MAPKIT_PENDING, false)) {
            preferences.edit().remove(MAPKIT_KEY).remove(MAPKIT_PENDING).commit();
            mapRecoveryRequired = true;
            return false;
        }

        String savedKey = preferences.getString(MAPKIT_KEY, "");
        String embeddedKey = BuildConfig.MAPKIT_API_KEY == null
                ? "" : BuildConfig.MAPKIT_API_KEY.trim();
        if ((savedKey == null || savedKey.trim().isEmpty()) && embeddedKey.isEmpty()) {
            return false;
        }

        preferences.edit().putBoolean(MAPKIT_PENDING, true).commit();
        boolean initialized = GpsAntiRadarApplication.ensureMapKit(this);
        if (!initialized) {
            preferences.edit().remove(MAPKIT_KEY).remove(MAPKIT_PENDING).commit();
            mapRecoveryRequired = true;
        }
        return initialized;
    }

    private boolean prepareRadarBaseMigration() {
        SharedPreferences preferences = getSharedPreferences(SETTINGS, MODE_PRIVATE);
        boolean coordinatesReady = preferences.getBoolean(RADARBASE_COORDINATE_FIX, false);
        boolean importReady = preferences.getInt(RADARBASE_IMPORT_FORMAT, 0)
                >= CURRENT_RADARBASE_IMPORT_FORMAT;
        if (coordinatesReady && importReady) return false;
        preferences.edit().remove(RADARBASE_ETAG).remove(RADARBASE_MODIFIED).commit();
        return true;
    }

    private void buildUi() {
        FrameLayout screen = new FrameLayout(this);
        screen.setBackgroundColor(Color.rgb(242, 244, 246));
        if (mapInitialized) {
            try {
                mapView = new MapView(this);
                screen.addView(mapView, new FrameLayout.LayoutParams(-1, -1));
                mapView.getMapWindow().getMap()
                        .addCameraListener(new WeakReference<>(cameraListener));
            } catch (Throwable error) {
                mapView = null;
                mapInitialized = false;
                mapRecoveryRequired = true;
                getSharedPreferences(SETTINGS, MODE_PRIVATE).edit()
                        .remove(MAPKIT_KEY).remove(MAPKIT_PENDING).commit();
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
                    Insets bars = windowInsets.getInsets(WindowInsets.Type.systemBars());
                    view.setPadding(sidePadding + bars.left, topPadding + bars.top,
                            sidePadding + bars.right, bottomPadding + bars.bottom);
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
                .getInt(HUD_TRANSPARENCY, 10));
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
        cameraView = text("Объектов впереди не найдено", 13, GREEN, Typeface.BOLD);
        cameraView.setGravity(Gravity.START);
        hudPanel.addView(cameraView);
        databaseView = text("База: 0 объектов", 11, Color.DKGRAY, Typeface.NORMAL);
        databaseView.setPadding(0, dp(9), 0, 0);
        hudPanel.addView(databaseView);

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
            @Override public void onClick(View v) { zoomMap(1f); }
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
            @Override public void onClick(View v) { zoomMap(-1f); }
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
        range.setMax(17);
        int savedDistance = getSharedPreferences(SETTINGS, MODE_PRIVATE)
                .getInt("alert_distance", 800);
        range.setProgress(Math.max(0, Math.min(17, (savedDistance - 300) / 100)));
        distanceLabel.setText("Расстояние оповещения: "
                + (300 + range.getProgress() * 100) + " м");
        range.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int distance = 300 + progress * 100;
                distanceLabel.setText("Расстояние оповещения: " + distance + " м");
                getSharedPreferences(SETTINGS, MODE_PRIVATE).edit()
                        .putInt("alert_distance", distance).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        distanceContent.addView(range, new LinearLayout.LayoutParams(-1, dp(42)));
        content.addView(distanceRow, new LinearLayout.LayoutParams(-1, dp(86)));

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
        transparency.setMax(16);
        int savedTransparency = getSharedPreferences(SETTINGS, MODE_PRIVATE)
                .getInt(HUD_TRANSPARENCY, 10);
        transparency.setProgress(Math.max(0, Math.min(16, savedTransparency / 5)));
        transparencyLabel.setText("Прозрачность плашки: "
                + (transparency.getProgress() * 5) + "%");
        transparency.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int value = progress * 5;
                transparencyLabel.setText("Прозрачность плашки: " + value + "%");
                getSharedPreferences(SETTINGS, MODE_PRIVATE).edit()
                        .putInt(HUD_TRANSPARENCY, value).apply();
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
                updateRadarBase();
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

        TextView historyTitle = text("История релизов", 17,
                Color.rgb(30, 30, 30), Typeface.BOLD);
        content.addView(historyTitle, new LinearLayout.LayoutParams(-1, -2));

        for (ReleaseHistory.Entry release : ReleaseHistory.entries()) {
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
                            .putString(MAPKIT_KEY, value)
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

    private void loadCameraMarkers(final boolean moveToData) {
        if (mapView == null) return;
        new Thread(new Runnable() {
            @Override public void run() {
                try (CameraDatabase db = new CameraDatabase(MainActivity.this)) {
                    final double[] bounds = db.bounds();
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (mapView == null) return;
                            if (moveToData && bounds != null) {
                                double latitude = (bounds[0] + bounds[1]) / 2.0;
                                double longitude = (bounds[2] + bounds[3]) / 2.0;
                                mapView.getMapWindow().getMap().move(new CameraPosition(
                                        new Point(latitude, longitude), 7.5f, 0f, 0f));
                            }
                            loadVisibleCameraMarkers();
                        }
                    });
                }
            }
        }, "map-camera-load").start();
    }

    private void loadVisibleCameraMarkers() {
        if (mapView == null) return;
        final VisibleRegion region;
        try {
            region = mapView.getMapWindow().getMap().getVisibleRegion();
        } catch (RuntimeException error) {
            return;
        }
        double south = Math.min(Math.min(region.getTopLeft().getLatitude(),
                        region.getTopRight().getLatitude()),
                Math.min(region.getBottomLeft().getLatitude(),
                        region.getBottomRight().getLatitude()));
        double north = Math.max(Math.max(region.getTopLeft().getLatitude(),
                        region.getTopRight().getLatitude()),
                Math.max(region.getBottomLeft().getLatitude(),
                        region.getBottomRight().getLatitude()));
        double west = Math.min(Math.min(region.getTopLeft().getLongitude(),
                        region.getBottomLeft().getLongitude()),
                Math.min(region.getTopRight().getLongitude(),
                        region.getBottomRight().getLongitude()));
        double east = Math.max(Math.max(region.getTopLeft().getLongitude(),
                        region.getBottomLeft().getLongitude()),
                Math.max(region.getTopRight().getLongitude(),
                        region.getBottomRight().getLongitude()));

        double latPadding = Math.max(0.02, (north - south) * 0.20);
        double lonPadding = Math.max(0.02, (east - west) * 0.20);
        final double querySouth = Math.max(-90.0, south - latPadding);
        final double queryNorth = Math.min(90.0, north + latPadding);
        final double queryWest = Math.max(-180.0, west - lonPadding);
        final double queryEast = Math.min(180.0, east + lonPadding);
        final int generation = ++cameraLoadGeneration;

        new Thread(new Runnable() {
            @Override public void run() {
                try (CameraDatabase db = new CameraDatabase(MainActivity.this)) {
                    final List<CameraPoint> points = db.withinBounds(querySouth, queryNorth,
                            queryWest, queryEast, MAX_VISIBLE_MARKERS);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (generation == cameraLoadGeneration) renderCameraMarkers(points);
                        }
                    });
                }
            }
        }, "visible-camera-load").start();
    }

    private void renderCameraMarkers(List<CameraPoint> points) {
        if (mapView == null) return;
        com.yandex.mapkit.map.Map map = mapView.getMapWindow().getMap();
        ensureCameraCollections(map);
        CameraMarkerDiff.Result coverageDiff = CameraMarkerDiff.between(renderedCameras, points);
        List<MapMarkerLayout.Entity> entities =
                MapMarkerLayout.create(points, map.getCameraPosition().getZoom());
        MapMarkerEntityDiff.Result markerDiff =
                MapMarkerEntityDiff.between(renderedMarkerEntities, entities);
        boolean showCoverage = map.getCameraPosition().getZoom() >= COVERAGE_MIN_ZOOM;
        boolean coverageModeChanged = showCoverage != cameraCoverageVisible;
        boolean markerChanges = !markerDiff.removeKeys.isEmpty()
                || !markerDiff.update.isEmpty() || !markerDiff.add.isEmpty();
        if (!markerChanges && !coverageDiff.hasMarkerChanges() && !coverageModeChanged) return;

        if (markerChanges) {
            if (cameraHintView != null) cameraHintView.setVisibility(View.GONE);
            hintGeneration++;
        }
        for (String key : markerDiff.removeKeys) {
            PlacemarkMapObject marker = renderedMarkerObjects.remove(key);
            if (marker != null) cameraMarkerCollection.remove(marker);
        }
        for (MapMarkerLayout.Entity entity : markerDiff.update) {
            PlacemarkMapObject marker = renderedMarkerObjects.get(entity.key);
            MapMarkerLayout.Entity previous = renderedMarkerEntities.get(entity.key);
            if (marker != null && previous != null) {
                updateMarkerEntity(marker, previous, entity);
            }
        }
        for (MapMarkerLayout.Entity entity : markerDiff.add) {
            renderedMarkerObjects.put(entity.key, addMarkerEntity(entity));
        }
        renderedMarkerEntities.clear();
        renderedMarkerEntities.putAll(markerDiff.desired);

        for (Long id : coverageDiff.removeIds) {
            removeCameraCoverage(id);
        }

        for (CameraPoint camera : coverageDiff.addOrReplace) {
            if (showCoverage && !coverageModeChanged) {
                renderedCameraCoverage.put(camera.id, addCameraCoverage(camera));
            }
        }
        renderedCameras.clear();
        renderedCameras.putAll(coverageDiff.desired);

        if (coverageModeChanged) {
            cameraCoverageCollection.clear();
            renderedCameraCoverage.clear();
            if (showCoverage) {
                for (CameraPoint camera : renderedCameras.values()) {
                    renderedCameraCoverage.put(camera.id, addCameraCoverage(camera));
                }
            }
            cameraCoverageVisible = showCoverage;
        }
    }

    private void ensureCameraCollections(com.yandex.mapkit.map.Map map) {
        if (cameraCoverageCollection == null) {
            cameraCoverageCollection = map.getMapObjects().addCollection();
        }
        if (cameraMarkerCollection == null) {
            cameraMarkerCollection = map.getMapObjects().addCollection();
        }
    }

    private PlacemarkMapObject addMarkerEntity(MapMarkerLayout.Entity entity) {
        Point point = new Point(entity.latitude, entity.longitude);
        if (entity.cluster) {
            return cameraMarkerCollection.addPlacemark(point,
                    clusterIcon(entity.memberIds.size()), clusterMarkerStyle());
        }
        CameraPoint camera = entity.camera;
        PlacemarkMapObject marker = cameraMarkerCollection.addPlacemark(point,
                iconForCamera(camera), individualMarkerStyle());
        if (camera.isCameraOrControl()) marker.setDirection(shootingBearing(camera));
        marker.setUserData(camera);
        marker.addTapListener(new WeakReference<>(placemarkTapListener));
        return marker;
    }

    private void updateMarkerEntity(PlacemarkMapObject marker,
                                    MapMarkerLayout.Entity previous,
                                    MapMarkerLayout.Entity current) {
        if (Double.compare(previous.latitude, current.latitude) != 0
                || Double.compare(previous.longitude, current.longitude) != 0) {
            marker.setGeometry(new Point(current.latitude, current.longitude));
        }
        if (current.cluster) {
            if (previous.memberIds.size() != current.memberIds.size()) {
                marker.setIcon(clusterIcon(current.memberIds.size()));
            }
            return;
        }

        CameraPoint oldCamera = previous.camera;
        CameraPoint newCamera = current.camera;
        if (oldCamera.type != newCamera.type) marker.setIcon(iconForCamera(newCamera));
        float oldDirection = oldCamera.isCameraOrControl() ? shootingBearing(oldCamera) : 0f;
        float newDirection = newCamera.isCameraOrControl() ? shootingBearing(newCamera) : 0f;
        if (Float.compare(oldDirection, newDirection) != 0) {
            marker.setDirection(newDirection);
        }
        marker.setUserData(newCamera);
    }

    private IconStyle individualMarkerStyle() {
        return new IconStyle()
                .setAnchor(new android.graphics.PointF(0.5f, 0.5f))
                .setRotationType(RotationType.ROTATE)
                .setFlat(true)
                .setScale(1.0f)
                .setZIndex(10f);
    }

    private IconStyle clusterMarkerStyle() {
        return new IconStyle()
                .setAnchor(new android.graphics.PointF(0.5f, 0.5f))
                .setRotationType(RotationType.NO_ROTATION)
                .setFlat(false)
                .setScale(1.0f)
                .setZIndex(20f);
    }

    private ImageProvider clusterIcon(int count) {
        ImageProvider cached = clusterIcons.get(count);
        if (cached != null) return cached;
        ImageProvider result = createClusterIcon(count);
        clusterIcons.put(count, result);
        return result;
    }

    private void removeCameraCoverage(long id) {
        List<PolygonMapObject> coverage = renderedCameraCoverage.remove(id);
        if (coverage == null) return;
        for (PolygonMapObject polygon : coverage) cameraCoverageCollection.remove(polygon);
    }

    private List<PolygonMapObject> addCameraCoverage(CameraPoint camera) {
        List<PolygonMapObject> result = new ArrayList<>();
        if (!camera.isCameraOrControl()) return result;
        int baseColor = markerColor(camera.isObservation()
                ? OBSERVATION_MARKER : camera.type);
        int fill = Color.argb(52, Color.red(baseColor), Color.green(baseColor),
                Color.blue(baseColor));
        int stroke = Color.argb(145, Color.red(baseColor), Color.green(baseColor),
                Color.blue(baseColor));
        Point origin = new Point(camera.latitude, camera.longitude);
        if (camera.dirType == 0) {
            addCoverageCircle(origin, camera.distanceMeters, fill, stroke, result);
            return result;
        }
        float halfAngle = Math.max(1f, camera.angleDegrees / 2f);
        addCoverageSector(origin, primaryCoverageBearing(camera), camera.distanceMeters,
                halfAngle, fill, stroke, result);
        if (camera.hasReverseZone()) {
            int reverseFill = Color.argb(30, Color.red(baseColor), Color.green(baseColor),
                    Color.blue(baseColor));
            addCoverageSector(origin, camera.direction, camera.reverseDistanceMeters,
                    halfAngle, reverseFill, stroke, result);
        }
        return result;
    }

    private void addCoverageCircle(Point origin, double radiusMeters, int fill, int stroke,
                                   List<PolygonMapObject> result) {
        if (radiusMeters <= 0) return;
        List<Point> boundary = new ArrayList<>();
        for (int bearing = 0; bearing <= 360; bearing += 10) {
            boundary.add(destination(origin, bearing, radiusMeters));
        }
        Polygon polygon = new Polygon(new LinearRing(boundary), Collections.emptyList());
        PolygonMapObject circle = cameraCoverageCollection.addPolygon(polygon);
        circle.setFillColor(fill);
        circle.setStrokeColor(stroke);
        circle.setStrokeWidth(1.2f);
        circle.setGeodesic(true);
        circle.setZIndex(2f);
        result.add(circle);
    }

    private void addCoverageSector(Point origin, float bearing, double rangeMeters,
                                   float halfAngle, int fill, int stroke,
                                   List<PolygonMapObject> result) {
        if (rangeMeters <= 0) return;
        List<Point> boundary = new ArrayList<>();
        boundary.add(origin);
        float step = Math.max(1.5f, halfAngle / 5f);
        for (float offset = -halfAngle; offset <= halfAngle; offset += step) {
            boundary.add(destination(origin, bearing + offset, rangeMeters));
        }
        boundary.add(destination(origin, bearing + halfAngle, rangeMeters));
        boundary.add(origin);
        Polygon polygon = new Polygon(new LinearRing(boundary), Collections.emptyList());
        PolygonMapObject sector = cameraCoverageCollection.addPolygon(polygon);
        sector.setFillColor(fill);
        sector.setStrokeColor(stroke);
        sector.setStrokeWidth(1.2f);
        sector.setGeodesic(true);
        sector.setZIndex(2f);
        result.add(sector);
    }

    private Point destination(Point start, double bearingDegrees, double distanceMeters) {
        double radius = 6371000.0;
        double angularDistance = distanceMeters / radius;
        double bearing = Math.toRadians(bearingDegrees);
        double latitude = Math.toRadians(start.getLatitude());
        double longitude = Math.toRadians(start.getLongitude());
        double destinationLatitude = Math.asin(Math.sin(latitude) * Math.cos(angularDistance)
                + Math.cos(latitude) * Math.sin(angularDistance) * Math.cos(bearing));
        double destinationLongitude = longitude + Math.atan2(
                Math.sin(bearing) * Math.sin(angularDistance) * Math.cos(latitude),
                Math.cos(angularDistance) - Math.sin(latitude) * Math.sin(destinationLatitude));
        return new Point(Math.toDegrees(destinationLatitude), Math.toDegrees(destinationLongitude));
    }

    private float shootingBearing(CameraPoint camera) {
        float result = camera.direction;
        if (camera.dirType == 1 || camera.dirType == 2 || camera.dirType == 4) {
            result += 180f;
        }
        result %= 360f;
        return result < 0f ? result + 360f : result;
    }

    private float primaryCoverageBearing(CameraPoint camera) {
        float result = (camera.direction + 180f) % 360f;
        return result < 0f ? result + 360f : result;
    }

    private ImageProvider iconForCamera(CameraPoint camera) {
        int resourceId = cameraIconResource(camera.type);
        ImageProvider cached = markerIcons.get(resourceId);
        if (cached != null) return cached;
        ImageProvider result = ImageProvider.fromBitmap(createCameraBitmap(resourceId));
        markerIcons.put(resourceId, result);
        return result;
    }

    private int cameraIconResource(int type) {
        Integer cached = markerResources.get(type);
        if (cached != null) return cached;
        int resourceId = getResources().getIdentifier(
                "cam_type_" + type, "drawable", getPackageName());
        if (resourceId == 0) resourceId = ru.gpsantiradar.app.R.drawable.cam_type_0;
        markerResources.put(type, resourceId);
        return resourceId;
    }

    private Bitmap createCameraBitmap(int resourceId) {
        int size = dp(42);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Drawable drawable = getDrawable(resourceId);
        if (drawable == null) drawable = getDrawable(ru.gpsantiradar.app.R.drawable.cam_type_0);
        if (drawable != null) {
            drawable.setBounds(0, 0, size, size);
            drawable.draw(canvas);
        }
        return bitmap;
    }

    private int markerColor(int type) {
        switch (type) {
            case OBSERVATION_MARKER: return Color.rgb(70, 125, 165);
            case 16: return Color.rgb(115, 115, 115);
            case 3: case 10: case 18: case 103: return Color.rgb(220, 55, 48);
            case 5: case 104: case 105: case 108: return Color.rgb(195, 65, 155);
            case 41: case 42: case 43: return Color.rgb(236, 160, 20);
            case 107: return Color.rgb(35, 115, 220);
            case 17: case 171: case 172: return Color.rgb(145, 75, 190);
            default: return Color.rgb(238, 103, 28);
        }
    }

    private ImageProvider createClusterIcon(int count) {
        int size = dp(42);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.argb(245, 255, 255, 255));
        canvas.drawCircle(size / 2f, size / 2f, size * 0.48f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(3));
        paint.setColor(GREEN);
        canvas.drawCircle(size / 2f, size / 2f, size * 0.42f, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.rgb(35, 35, 35));
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextSize(dp(count > 999 ? 10 : 13));
        Paint.FontMetrics fm = paint.getFontMetrics();
        float baseline = size / 2f - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(Integer.toString(count), size / 2f, baseline, paint);
        return ImageProvider.fromBitmap(bitmap);
    }

    private void updateLocationMarker() {
        if (mapView == null || Double.isNaN(lastLatitude) || Double.isNaN(lastLongitude)) return;
        Point point = new Point(lastLatitude, lastLongitude);
        if (locationPlacemark == null || !locationPlacemark.isValid()) {
            locationPlacemark = mapView.getMapWindow().getMap().getMapObjects().addPlacemark();
            locationPlacemark.setIcon(ImageProvider.fromBitmap(createLocationBitmap()));
            locationPlacemark.setZIndex(100f);
        }
        locationPlacemark.setGeometry(point);
    }

    private Bitmap createLocationBitmap() {
        int size = dp(28);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.WHITE);
        canvas.drawCircle(size / 2f, size / 2f, size * 0.47f, paint);
        paint.setColor(Color.rgb(35, 130, 255));
        canvas.drawCircle(size / 2f, size / 2f, size * 0.34f, paint);
        return bitmap;
    }

    private void centerOnLocation() {
        if (mapView == null) {
            showMapKeyDialog();
            return;
        }
        if (Double.isNaN(lastLatitude) || Double.isNaN(lastLongitude)) {
            Toast.makeText(this, "Дождитесь определения координат GPS",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        followPausedUntil = 0L;
        mapView.getMapWindow().getMap().move(new CameraPosition(
                        new Point(lastLatitude, lastLongitude), 15f, 0f, 0f),
                new Animation(Animation.Type.SMOOTH, 0.55f));
        mapCenteredOnGps = true;
    }

    private void centerOnLocationFromGps(float speedKmh) {
        if (mapView == null || Double.isNaN(lastLatitude) || Double.isNaN(lastLongitude)) return;
        if (SystemClock.elapsedRealtime() < followPausedUntil) return;
        if (mapCenteredOnGps && speedKmh <= 0f) return;
        com.yandex.mapkit.map.Map map = mapView.getMapWindow().getMap();
        CameraPosition current = map.getCameraPosition();
        float zoom = mapCenteredOnGps ? current.getZoom() : 15f;
        map.move(new CameraPosition(new Point(lastLatitude, lastLongitude), zoom,
                        current.getAzimuth(), current.getTilt()),
                new Animation(Animation.Type.SMOOTH, 0.45f));
        mapCenteredOnGps = true;
    }

    private void pauseLocationFollowing() {
        followPausedUntil = SystemClock.elapsedRealtime() + FOLLOW_PAUSE_MS;
    }

    private void zoomMap(float delta) {
        if (mapView == null) return;
        pauseLocationFollowing();
        com.yandex.mapkit.map.Map map = mapView.getMapWindow().getMap();
        CameraPosition current = map.getCameraPosition();
        float zoom = Math.max(2f, Math.min(21f, current.getZoom() + delta));
        map.move(new CameraPosition(current.getTarget(), zoom,
                        current.getAzimuth(), current.getTilt()),
                new Animation(Animation.Type.SMOOTH, 0.35f));
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

    private void updateRadarBase() {
        databaseView.setText("Загрузка RadarBase…");
        new Thread(new Runnable() {
            @Override public void run() {
                HttpURLConnection connection = null;
                try {
                    connection = (HttpURLConnection) new URL(RADARBASE_URL).openConnection();
                    connection.setConnectTimeout(15000);
                    connection.setReadTimeout(120000);
                    connection.setRequestProperty("Accept", "application/json");
                    connection.setRequestProperty("Accept-Encoding", "gzip");
                    SharedPreferences preferences = getSharedPreferences(SETTINGS, MODE_PRIVATE);
                    String etag = preferences.getString(RADARBASE_ETAG, "");
                    String modified = preferences.getString(RADARBASE_MODIFIED, "");
                    if (etag != null && !etag.isEmpty()) connection.setRequestProperty("If-None-Match", etag);
                    if (modified != null && !modified.isEmpty()) {
                        connection.setRequestProperty("If-Modified-Since", modified);
                    }
                    int status = connection.getResponseCode();
                    if (status == HttpURLConnection.HTTP_NOT_MODIFIED) {
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                refreshDatabaseCount();
                                Toast.makeText(MainActivity.this, "База RadarBase уже актуальна",
                                        Toast.LENGTH_LONG).show();
                            }
                        });
                        return;
                    }
                    if (status != HttpURLConnection.HTTP_OK) {
                        throw new IOException("Сервер RadarBase: HTTP " + status);
                    }
                    InputStream raw = connection.getInputStream();
                    InputStream input = "gzip".equalsIgnoreCase(connection.getContentEncoding())
                            ? new GZIPInputStream(raw) : raw;
                    RadarBaseParser.Result result;
                    try (InputStream source = input;
                         CameraDatabase db = new CameraDatabase(MainActivity.this)) {
                        result = db.importRadarBase(source);
                    }
                    SharedPreferences.Editor editor = preferences.edit();
                    String newEtag = connection.getHeaderField("ETag");
                    String newModified = connection.getHeaderField("Last-Modified");
                    if (newEtag != null) editor.putString(RADARBASE_ETAG, newEtag);
                    if (newModified != null) editor.putString(RADARBASE_MODIFIED, newModified);
                    if (result.coordinatesCorrected) {
                        editor.putBoolean(RADARBASE_COORDINATE_FIX, true);
                    }
                    editor.putInt(RADARBASE_IMPORT_FORMAT, CURRENT_RADARBASE_IMPORT_FORMAT);
                    editor.apply();
                    showRadarBaseImportSuccess(result);
                } catch (Exception error) {
                    showRadarBaseImportError(error);
                } finally {
                    if (connection != null) connection.disconnect();
                }
            }
        }, "radarbase-download").start();
    }

    private void showRadarBaseImportSuccess(final RadarBaseParser.Result result) {
        getSharedPreferences(SETTINGS, MODE_PRIVATE).edit()
                .putBoolean(RADARBASE_COORDINATE_FIX, true)
                .putInt(RADARBASE_IMPORT_FORMAT, CURRENT_RADARBASE_IMPORT_FORMAT)
                .apply();
        getApplicationContext().sendBroadcast(
                new Intent(ACTION_RADARBASE_UPDATED).setPackage(getPackageName()));
        runOnUiThread(new Runnable() {
            @Override public void run() {
                databaseView.setText("База: " + result.count + " объектов");
                String suffix = result.coordinatesCorrected ? " · координаты восстановлены"
                        : " · без поправки координат";
                Toast.makeText(MainActivity.this, "Импортировано: " + result.count + suffix,
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    private void showRadarBaseImportError(final Exception error) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                databaseView.setText("Ошибка обновления");
                String detail = error.getMessage();
                Toast.makeText(MainActivity.this, detail == null || detail.trim().isEmpty()
                                ? "Не удалось загрузить JSON RadarBase" : detail,
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    private void refreshDatabaseCount() {
        new Thread(new Runnable() {
            @Override public void run() {
                try (CameraDatabase db = new CameraDatabase(MainActivity.this)) {
                    final int count = db.count();
                    final String exportDate = db.exportDate();
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            databaseView.setText("База: " + count + " объектов");
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
        int transparency = Math.max(0, Math.min(80, transparencyPercent));
        int alpha = Math.round(255f * (100 - transparency) / 100f);
        hudPanel.setBackground(roundedBackground(Color.argb(alpha, 255, 255, 255), 14));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private String formatDistance(int meters) {
        return meters >= 1000 ? String.format(Locale.US, "%.1f км", meters / 1000.0)
                : meters + " м";
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
        IntentFilter radarBaseFilter = new IntentFilter(ACTION_RADARBASE_UPDATED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            registerReceiver(radarBaseReceiver, radarBaseFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
            registerReceiver(radarBaseReceiver, radarBaseFilter);
        }
        refreshDatabaseCount();
        loadVisibleCameraMarkers();
        if (mapView != null) {
            MapKitFactory.getInstance().onStart();
            mapView.onStart();
        }
    }

    @Override protected void onStop() {
        if (mapView != null) {
            mapView.onStop();
            MapKitFactory.getInstance().onStop();
        }
        unregisterReceiver(receiver);
        unregisterReceiver(radarBaseReceiver);
        super.onStop();
    }

}
