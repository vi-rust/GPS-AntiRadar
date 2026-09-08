package ru.gpsantiradar.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.car.app.CarContext;

import com.yandex.mapkit.ScreenPoint;
import com.yandex.mapkit.ScreenRect;
import com.yandex.mapkit.geometry.Point;
import com.yandex.mapkit.map.MapWindow;
import com.yandex.mapkit.mapview.MapView;

public final class CarMapPresentation {
    private static final String TAG = "CarMapPresentation";
    private static final int GREEN = Color.rgb(0, 166, 82);
    private static final long HINT_VISIBLE_MS = 3000L;

    private final Context context;
    private final GpsAntiRadarApplication application;
    private final SharedPreferences preferences;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final FrameLayout root;
    private final LinearLayout hudPanel;
    private final TextView speedView;
    private final TextView unitView;
    private final TextView distanceView;
    private final TextView cameraView;
    private final TextView hintView;
    private MapView mapView;
    private SharedCameraMapLayer mapLayer;
    private CarMapGestureController gestureController;
    private Rect stableArea;
    private Rect visibleArea;
    private boolean mapKitAcquired;
    private boolean mapStarted;
    private boolean destroyed;
    private boolean darkTheme;
    private boolean themeApplied;
    private boolean settingsRegistered;
    private boolean databaseEmpty;
    private boolean trackingStopped;
    private int hintGeneration;
    private DrivingSnapshot latestSnapshot = DrivingSnapshot.idle();
    private final SharedPreferences.OnSharedPreferenceChangeListener settingsListener =
            new SharedPreferences.OnSharedPreferenceChangeListener() {
                @Override public void onSharedPreferenceChanged(
                        SharedPreferences sharedPreferences, String key) {
                    if (destroyed) return;
                    mainHandler.post(() -> applySettingChange(key));
                }
            };
    private final Runnable themeRefresh = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            applyTheme(ThemeSettings.isDark(context));
            if (!destroyed) root.postDelayed(this, 60_000L);
        }
    };

    public CarMapPresentation(Context context, GpsAntiRadarApplication application,
            CarContext carContext) {
        this.context = context;
        this.application = application;
        preferences = context.getSharedPreferences(
                AppSettings.PREFERENCES, Context.MODE_PRIVATE);
        darkTheme = ThemeSettings.isDark(context);
        root = new FrameLayout(context);
        root.setBackgroundColor(darkTheme
                ? Color.rgb(18, 18, 18) : Color.rgb(242, 244, 246));

        hudPanel = new LinearLayout(context);
        hudPanel.setOrientation(LinearLayout.VERTICAL);
        hudPanel.setGravity(Gravity.START);
        hudPanel.setPadding(dp(14), dp(10), dp(14), dp(12));
        hudPanel.setElevation(dp(4));
        FrameLayout.LayoutParams hudParams = new FrameLayout.LayoutParams(
                dp(300), FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START);
        hudParams.setMargins(dp(8), 0, 0, dp(8));
        root.addView(hudPanel, hudParams);

        hudPanel.addView(text("Скорость", 14, GREEN, Typeface.BOLD));
        speedView = text("0", 46, GREEN, Typeface.BOLD);
        speedView.setIncludeFontPadding(false);
        hudPanel.addView(speedView);
        unitView = text("км/ч", 13, Color.DKGRAY, Typeface.NORMAL);
        hudPanel.addView(unitView);
        distanceView = text("—", 24, Color.rgb(30, 30, 30), Typeface.BOLD);
        hudPanel.addView(distanceView);
        cameraView = text("Объектов впереди нет", 13, GREEN, Typeface.BOLD);
        hudPanel.addView(cameraView);

        hintView = text("", 13, Color.BLACK, Typeface.BOLD);
        hintView.setPadding(dp(10), dp(7), dp(10), dp(7));
        hintView.setVisibility(View.GONE);
        FrameLayout.LayoutParams hintParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        hintParams.setMargins(0, dp(8), 0, 0);
        root.addView(hintView, hintParams);

        onCarConfigurationChanged();
    }

    public void start() {
        if (destroyed) {
            throw new IllegalStateException("Car map presentation is destroyed");
        }
        if (mapView != null) return;
        registerSettingsListener();
        try {
            mapView = new MapView(context);
            root.addView(mapView, 0, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            startMap();
            root.removeCallbacks(themeRefresh);
            root.postDelayed(themeRefresh, 60_000L);
        } catch (RuntimeException | LinkageError error) {
            destroy();
            throw error;
        }
    }

    public View rootView() {
        return root;
    }

    public void onDrivingSnapshot(DrivingSnapshot snapshot) {
        if (destroyed || snapshot == null) return;
        latestSnapshot = snapshot;
        trackingStopped = false;
        if (snapshot.hasLocation()) {
            ThemeSettings.rememberLocation(context,
                    snapshot.latitude, snapshot.longitude);
            applyTheme(ThemeSettings.isDark(context, System.currentTimeMillis(),
                    snapshot.latitude, snapshot.longitude));
        } else {
            applyTheme(ThemeSettings.isDark(context));
        }
        renderHud(snapshot);
        if (mapLayer != null) {
            mapLayer.updateActiveCamera(snapshot.cameraId);
            if (snapshot.hasLocation()) {
                mapLayer.updateCurrentLocation(
                        snapshot.latitude, snapshot.longitude, snapshot.speedKmh,
                        snapshot.headingDegrees);
            }
        }
    }

    private void renderHud(DrivingSnapshot snapshot) {
        int overspeedThresholdKmh = AppSettings.clampOverspeedThreshold(
                preferences.getInt(AppSettings.OVERSPEED_THRESHOLD,
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

    public void onDatabaseCount(int count) {
        if (destroyed || count < 0) return;
        databaseEmpty = count == 0;
        if (!trackingStopped) renderHud(latestSnapshot);
    }

    public void onTrackingStopped() {
        if (destroyed) return;
        trackingStopped = true;
        latestSnapshot = DrivingSnapshot.idle();
        speedView.setText("0");
        speedView.setTextColor(GREEN);
        distanceView.setText("—");
        cameraView.setText("Антирадар остановлен");
        cameraView.setTextColor(GREEN);
        if (mapLayer != null) mapLayer.updateActiveCamera(-1L);
    }

    public void refreshVisible() {
        if (!destroyed && mapLayer != null) mapLayer.refreshVisible();
    }

    public void zoomBy(float delta) {
        if (gestureController != null) gestureController.zoomBy(delta);
    }

    public void recenter() {
        if (gestureController != null) gestureController.recenter();
    }

    public void setPanMode(boolean enabled) {
        if (gestureController != null) gestureController.setPanMode(enabled);
    }

    public void onScroll(float distanceX, float distanceY) {
        if (gestureController != null) gestureController.onScroll(distanceX, distanceY);
    }

    public void onFling(float velocityX, float velocityY) {
        if (gestureController != null) gestureController.onFling(velocityX, velocityY);
    }

    public void onScale(float focusX, float focusY, float scaleFactor) {
        if (gestureController != null) {
            gestureController.onScale(focusX, focusY, scaleFactor);
        }
    }

    public void onClick(float x, float y) {
        if (gestureController != null) gestureController.onClick(x, y);
    }

    public void onStableAreaChanged(Rect area) {
        if (destroyed) return;
        stableArea = area == null || area.isEmpty() ? null : new Rect(area);
        applyStableArea();
        root.post(this::applyStableArea);
    }

    public void onVisibleAreaChanged(Rect area) {
        if (destroyed) return;
        visibleArea = area == null || area.isEmpty() ? null : new Rect(area);
        applyVisibleArea();
        root.post(this::applyVisibleArea);
    }

    public void onCarConfigurationChanged() {
        if (destroyed) return;
        applyTheme(ThemeSettings.isDark(context));
    }

    public void refreshHudTransparency() {
        if (destroyed) return;
        int percent = AppSettings.clampHudTransparency(
                preferences.getInt(AppSettings.HUD_TRANSPARENCY,
                        AppSettings.DEFAULT_HUD_TRANSPARENCY_PERCENT));
        int alpha = Math.round(255f * (100 - percent) / 100f);
        int base = darkTheme ? Color.rgb(28, 30, 32) : Color.WHITE;
        hudPanel.setBackground(roundedBackground(
                Color.argb(alpha, Color.red(base), Color.green(base), Color.blue(base))));
    }

    public void destroy() {
        if (destroyed) return;
        destroyed = true;
        root.removeCallbacks(themeRefresh);
        unregisterSettingsListener();
        if (mapLayer != null) {
            try {
                mapLayer.destroy();
            } catch (RuntimeException | LinkageError error) {
                Log.e(TAG, "Failed to destroy shared camera layer", error);
            }
            mapLayer = null;
        }
        gestureController = null;
        if (mapStarted && mapView != null) {
            try {
                mapView.onStop();
            } catch (RuntimeException | LinkageError error) {
                Log.e(TAG, "Failed to stop car MapView", error);
            }
            mapStarted = false;
        }
        if (mapView != null) {
            try {
                mapView.destroy();
            } catch (RuntimeException | LinkageError error) {
                Log.e(TAG, "Failed to destroy car MapView", error);
            }
            mapView = null;
        }
        if (mapKitAcquired) {
            try {
                application.releaseMapKit();
            } catch (RuntimeException | LinkageError error) {
                Log.e(TAG, "Failed to release MapKit", error);
            }
            mapKitAcquired = false;
        }
    }

    private void startMap() {
        mapKitAcquired = true;
        application.acquireMapKit();
        mapStarted = true;
        mapView.onStart();
        final MapWindow mapWindow = mapView.getMapWindow();
        mapLayer = new SharedCameraMapLayer(context, mapWindow,
                new SharedCameraMapLayer.Host() {
                    @Override public void postToUi(Runnable action) {
                        root.post(action);
                    }

                    @Override public void onMarkerPresentationChanged() {
                        hintView.setVisibility(View.GONE);
                        hintGeneration++;
                    }

                    @Override public void onCameraTapped(CameraPoint camera, Point position) {
                        hintView.setText(CameraHintFormatter.format(camera));
                        hintView.setVisibility(View.VISIBLE);
                        final int generation = ++hintGeneration;
                        root.postDelayed(new Runnable() {
                            @Override public void run() {
                                if (!destroyed && generation == hintGeneration) {
                                    hintView.setVisibility(View.GONE);
                                }
                            }
                        }, HINT_VISIBLE_MS);
                    }
                });
        mapLayer.setNightMode(darkTheme);
        gestureController = new CarMapGestureController(
                mapWindow, mapLayer, new CarMapGestureController.ClickListener() {
                    @Override public void onMapClick(Point point) {
                        hintGeneration++;
                        hintView.setVisibility(View.GONE);
                    }
                });
        mapLayer.loadInitial(true);
    }

    private void applyTheme(boolean dark) {
        if (destroyed || themeApplied && darkTheme == dark) return;
        darkTheme = dark;
        themeApplied = true;
        int primary = dark ? Color.rgb(238, 238, 238) : Color.rgb(30, 30, 30);
        int secondary = dark ? Color.rgb(185, 190, 195) : Color.DKGRAY;
        int hintSurface = dark ? Color.rgb(32, 35, 38) : Color.WHITE;
        root.setBackgroundColor(dark
                ? Color.rgb(18, 18, 18) : Color.rgb(242, 244, 246));
        unitView.setTextColor(secondary);
        distanceView.setTextColor(primary);
        hintView.setTextColor(primary);
        hintView.setBackground(roundedBackground(hintSurface));
        refreshHudTransparency();
        if (mapLayer != null) mapLayer.setNightMode(dark);
    }

    private void registerSettingsListener() {
        if (settingsRegistered) return;
        preferences.registerOnSharedPreferenceChangeListener(settingsListener);
        settingsRegistered = true;
    }

    private void unregisterSettingsListener() {
        if (!settingsRegistered) return;
        preferences.unregisterOnSharedPreferenceChangeListener(settingsListener);
        settingsRegistered = false;
    }

    private void applySettingChange(String key) {
        if (destroyed || key == null) return;
        if (AppSettings.HUD_TRANSPARENCY.equals(key)) {
            refreshHudTransparency();
        } else if (AppSettings.THEME_MODE.equals(key)) {
            applyTheme(ThemeSettings.isDark(context));
        } else if (AppSettings.OVERSPEED_THRESHOLD.equals(key) && !trackingStopped) {
            renderHud(latestSnapshot);
        }
    }

    private void applyStableArea() {
        if (destroyed) return;
        if (stableArea == null || stableArea.isEmpty()) {
            FrameLayout.LayoutParams params =
                    (FrameLayout.LayoutParams) hudPanel.getLayoutParams();
            params.leftMargin = dp(8);
            params.bottomMargin = dp(8);
            params.width = dp(300);
            hudPanel.setLayoutParams(params);
            return;
        }
        Rect area = stableArea;
        FrameLayout.LayoutParams params =
                (FrameLayout.LayoutParams) hudPanel.getLayoutParams();
        int surfaceHeight = mapView == null ? root.getHeight()
                : mapView.getMapWindow().height();
        params.leftMargin = Math.max(dp(8), area.left + dp(8));
        params.bottomMargin = Math.max(dp(8), surfaceHeight - area.bottom + dp(8));
        int availableWidth = Math.max(dp(180), area.width() - dp(16));
        params.width = Math.min(dp(300), availableWidth);
        hudPanel.setLayoutParams(params);
    }

    private void applyVisibleArea() {
        if (destroyed || mapView == null) return;
        MapWindow mapWindow = mapView.getMapWindow();
        if (visibleArea == null || visibleArea.isEmpty()) {
            mapWindow.setFocusRect(null);
            return;
        }
        int width = mapWindow.width();
        int height = mapWindow.height();
        if (width <= 0 || height <= 0) return;
        Rect area = visibleArea;
        float left = Math.max(0, Math.min(width, area.left));
        float top = Math.max(0, Math.min(height, area.top));
        float right = Math.max(left, Math.min(width, area.right));
        float bottom = Math.max(top, Math.min(height, area.bottom));
        mapWindow.setFocusRect(new ScreenRect(
                new ScreenPoint(left, top), new ScreenPoint(right, bottom)));
    }

    private TextView text(String value, int sp, int color, int style) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans", style));
        return view;
    }

    private GradientDrawable roundedBackground(int color) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(7));
        background.setStroke(dp(1), Color.argb(40, 0, 0, 0));
        return background;
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
