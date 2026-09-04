package ru.gpsantiradar.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
    private static final int GREEN = Color.rgb(0, 166, 82);

    private final Context context;
    private final GpsAntiRadarApplication application;
    private final CarContext carContext;
    private final FrameLayout root;
    private final LinearLayout hudPanel;
    private final TextView speedView;
    private final TextView distanceView;
    private final TextView cameraView;
    private final TextView stateView;
    private final TextView hintView;
    private MapView mapView;
    private SharedCameraMapLayer mapLayer;
    private CarMapGestureController gestureController;
    private Rect stableArea;
    private Rect visibleArea;
    private boolean mapKitAcquired;
    private boolean mapStarted;
    private boolean destroyed;

    public CarMapPresentation(Context context, GpsAntiRadarApplication application,
            CarContext carContext) {
        this.context = context;
        this.application = application;
        this.carContext = carContext;
        root = new FrameLayout(context);
        root.setBackgroundColor(Color.BLACK);

        mapView = new MapView(context);
        root.addView(mapView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

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
        hudPanel.addView(text("км/ч", 13, Color.DKGRAY, Typeface.NORMAL));
        distanceView = text("—", 24, Color.rgb(30, 30, 30), Typeface.BOLD);
        hudPanel.addView(distanceView);
        cameraView = text("Объектов впереди нет", 13, GREEN, Typeface.BOLD);
        hudPanel.addView(cameraView);
        stateView = text("", 12, Color.DKGRAY, Typeface.NORMAL);
        hudPanel.addView(stateView);

        hintView = text("", 13, Color.BLACK, Typeface.BOLD);
        hintView.setPadding(dp(10), dp(7), dp(10), dp(7));
        hintView.setVisibility(View.GONE);
        FrameLayout.LayoutParams hintParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        hintParams.setMargins(0, dp(8), 0, 0);
        root.addView(hintView, hintParams);

        startMap();
        refreshHudTransparency();
        onCarConfigurationChanged();
    }

    public View rootView() {
        return root;
    }

    public void onDrivingSnapshot(DrivingSnapshot snapshot) {
        if (destroyed || snapshot == null) return;
        DrivingHudPresentation presentation = DrivingHudPresentation.from(snapshot);
        speedView.setText(presentation.speedText);
        speedView.setTextColor(presentation.speedColor);
        distanceView.setText(presentation.distanceText);
        cameraView.setText(presentation.cameraText);
        cameraView.setTextColor(presentation.speedColor);
        stateView.setText(snapshot.alertState);
        if (snapshot.hasLocation() && mapLayer != null) {
            mapLayer.updateCurrentLocation(
                    snapshot.latitude, snapshot.longitude, snapshot.speedKmh);
        }
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
        if (destroyed || area == null) return;
        stableArea = new Rect(area);
        applyStableArea();
        root.post(this::applyStableArea);
    }

    public void onVisibleAreaChanged(Rect area) {
        if (destroyed || area == null) return;
        visibleArea = new Rect(area);
        applyVisibleArea();
        root.post(this::applyVisibleArea);
    }

    public void onCarConfigurationChanged() {
        if (destroyed) return;
        boolean dark = carContext.isDarkMode();
        int primary = dark ? Color.WHITE : Color.rgb(30, 30, 30);
        int secondary = dark ? Color.LTGRAY : Color.DKGRAY;
        distanceView.setTextColor(primary);
        stateView.setTextColor(secondary);
        refreshHudTransparency();
    }

    public void refreshHudTransparency() {
        if (destroyed) return;
        int percent = AppSettings.clampHudTransparency(
                context.getSharedPreferences(AppSettings.PREFERENCES, Context.MODE_PRIVATE)
                        .getInt(AppSettings.HUD_TRANSPARENCY,
                                AppSettings.DEFAULT_HUD_TRANSPARENCY_PERCENT));
        int alpha = Math.round(255f * (100 - percent) / 100f);
        boolean dark = carContext.isDarkMode();
        int base = dark ? Color.rgb(28, 28, 28) : Color.WHITE;
        hudPanel.setBackground(roundedBackground(
                Color.argb(alpha, Color.red(base), Color.green(base), Color.blue(base))));
    }

    public void destroy() {
        if (destroyed) return;
        destroyed = true;
        if (mapLayer != null) {
            mapLayer.destroy();
            mapLayer = null;
        }
        gestureController = null;
        if (mapStarted && mapView != null) {
            mapView.onStop();
            mapStarted = false;
        }
        if (mapView != null) {
            mapView.destroy();
            mapView = null;
        }
        if (mapKitAcquired) {
            application.releaseMapKit();
            mapKitAcquired = false;
        }
    }

    private void startMap() {
        application.acquireMapKit();
        mapKitAcquired = true;
        mapView.onStart();
        mapStarted = true;
        final MapWindow mapWindow = mapView.getMapWindow();
        mapLayer = new SharedCameraMapLayer(context, mapWindow,
                new SharedCameraMapLayer.Host() {
                    @Override public void postToUi(Runnable action) {
                        root.post(action);
                    }

                    @Override public void onMarkerPresentationChanged() {
                        hintView.setVisibility(View.GONE);
                    }

                    @Override public void onCameraTapped(CameraPoint camera, Point position) {
                        hintView.setText(camera.typeName());
                        hintView.setVisibility(View.VISIBLE);
                    }
                });
        gestureController = new CarMapGestureController(
                mapWindow, mapLayer, new CarMapGestureController.ClickListener() {
                    @Override public void onMapClick(Point point) {
                        hintView.setVisibility(View.GONE);
                    }
                });
        mapLayer.loadInitial(true);
    }

    private void applyStableArea() {
        if (destroyed || stableArea == null) return;
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
        if (destroyed || visibleArea == null || mapView == null) return;
        MapWindow mapWindow = mapView.getMapWindow();
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
