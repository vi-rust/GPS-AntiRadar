package ru.gpsantiradar.app;

import com.yandex.mapkit.Animation;
import com.yandex.mapkit.ScreenPoint;
import com.yandex.mapkit.geometry.Point;
import com.yandex.mapkit.map.CameraPosition;
import com.yandex.mapkit.map.MapWindow;

public final class CarMapGestureController {
    public interface ClickListener {
        void onMapClick(Point point);
    }

    private static final float FLING_SECONDS = 0.12f;

    private final MapWindow mapWindow;
    private final SharedCameraMapLayer mapLayer;
    private final ClickListener clickListener;
    private boolean panMode;

    public CarMapGestureController(MapWindow mapWindow,
            SharedCameraMapLayer mapLayer, ClickListener clickListener) {
        this.mapWindow = mapWindow;
        this.mapLayer = mapLayer;
        this.clickListener = clickListener;
    }

    public void zoomBy(float delta) {
        mapLayer.zoomBy(delta);
    }

    public void recenter() {
        mapLayer.moveToCurrentLocation();
    }

    public void setPanMode(boolean enabled) {
        panMode = enabled;
        if (enabled) mapLayer.pauseFollowing();
    }

    public void onScale(float focusX, float focusY, float scaleFactor) {
        if (!Float.isFinite(scaleFactor) || scaleFactor <= 0f) return;
        float delta = (float) (Math.log(scaleFactor) / Math.log(2.0));
        if (Float.isFinite(delta) && Math.abs(delta) > 0.001f) {
            mapLayer.zoomBy(delta);
        }
    }

    public void onScroll(float distanceX, float distanceY) {
        if (!panMode) return;
        moveCenterToScreenOffset(distanceX, distanceY, null);
    }

    public void onFling(float velocityX, float velocityY) {
        if (!panMode) return;
        moveCenterToScreenOffset(-velocityX * FLING_SECONDS,
                -velocityY * FLING_SECONDS,
                new Animation(Animation.Type.SMOOTH, 0.28f));
    }

    public void onClick(float x, float y) {
        if (!Float.isFinite(x) || !Float.isFinite(y)) return;
        Point point = mapWindow.screenToWorld(new ScreenPoint(x, y));
        if (point != null && clickListener != null) clickListener.onMapClick(point);
    }

    private void moveCenterToScreenOffset(float offsetX, float offsetY,
            Animation animation) {
        if (!Float.isFinite(offsetX) || !Float.isFinite(offsetY)) return;
        com.yandex.mapkit.map.Map map = mapWindow.getMap();
        if (map == null || !map.isValid()) return;
        float centerX = mapWindow.width() / 2f;
        float centerY = mapWindow.height() / 2f;
        Point target = mapWindow.screenToWorld(
                new ScreenPoint(centerX + offsetX, centerY + offsetY));
        if (target == null) return;
        mapLayer.pauseFollowing();
        CameraPosition current = map.getCameraPosition();
        CameraPosition next = new CameraPosition(target, current.getZoom(),
                current.getAzimuth(), current.getTilt());
        if (animation == null) {
            map.move(next);
        } else {
            map.move(next, animation);
        }
    }
}
