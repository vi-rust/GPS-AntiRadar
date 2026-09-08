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

    interface GestureTarget {
        void zoomBy(float delta, boolean animated);
        void recenter();
        void pauseFollowing();
        void panBy(float offsetX, float offsetY, boolean animated);
        Point pointAt(float x, float y);
        default boolean tapCameraAt(float x, float y) { return false; }
    }

    private static final float FLING_SECONDS = 0.12f;

    private final GestureTarget target;
    private final ClickListener clickListener;

    public CarMapGestureController(MapWindow mapWindow,
            SharedCameraMapLayer mapLayer, ClickListener clickListener) {
        this(new MapKitGestureTarget(mapWindow, mapLayer), clickListener);
    }

    CarMapGestureController(GestureTarget target, ClickListener clickListener) {
        if (target == null) throw new IllegalArgumentException("target is required");
        this.target = target;
        this.clickListener = clickListener;
    }

    static ScreenPoint translatedCameraTarget(ScreenPoint displayedTarget,
            float offsetX, float offsetY) {
        if (displayedTarget == null) return null;
        return new ScreenPoint(displayedTarget.getX() + offsetX,
                displayedTarget.getY() + offsetY);
    }

    public void zoomBy(float delta) {
        target.zoomBy(delta, true);
    }

    public void recenter() {
        target.recenter();
    }

    public void setPanMode(boolean enabled) {
        if (enabled) target.pauseFollowing();
    }

    public void onScale(float focusX, float focusY, float scaleFactor) {
        if (!Float.isFinite(scaleFactor) || scaleFactor <= 0f) return;
        float delta = (float) (Math.log(scaleFactor) / Math.log(2.0));
        if (Float.isFinite(delta) && Math.abs(delta) > 0.001f) {
            target.zoomBy(delta, false);
        }
    }

    public void onScroll(float distanceX, float distanceY) {
        moveCenterToScreenOffset(distanceX, distanceY, false);
    }

    public void onFling(float velocityX, float velocityY) {
        moveCenterToScreenOffset(-velocityX * FLING_SECONDS,
                -velocityY * FLING_SECONDS,
                true);
    }

    public void onClick(float x, float y) {
        if (!Float.isFinite(x) || !Float.isFinite(y)) return;
        if (target.tapCameraAt(x, y)) return;
        Point point = target.pointAt(x, y);
        if (point != null && clickListener != null) clickListener.onMapClick(point);
    }

    private void moveCenterToScreenOffset(float offsetX, float offsetY,
            boolean animated) {
        target.panBy(offsetX, offsetY, animated);
    }

    private static final class MapKitGestureTarget implements GestureTarget {
        private final MapWindow mapWindow;
        private final SharedCameraMapLayer mapLayer;

        MapKitGestureTarget(MapWindow mapWindow, SharedCameraMapLayer mapLayer) {
            if (mapWindow == null) throw new IllegalArgumentException("mapWindow is required");
            if (mapLayer == null) throw new IllegalArgumentException("mapLayer is required");
            this.mapWindow = mapWindow;
            this.mapLayer = mapLayer;
        }

        @Override public void zoomBy(float delta, boolean animated) {
            if (animated) {
                mapLayer.zoomBy(delta);
            } else {
                mapLayer.zoomByImmediately(delta);
            }
        }

        @Override public void recenter() {
            mapLayer.moveToCurrentLocation();
        }

        @Override public void pauseFollowing() {
            mapLayer.pauseFollowing();
        }

        @Override public Point pointAt(float x, float y) {
            return mapWindow.screenToWorld(new ScreenPoint(x, y));
        }

        @Override public boolean tapCameraAt(float x, float y) {
            return mapLayer.tapCameraAt(x, y);
        }

        @Override public void panBy(float offsetX, float offsetY, boolean animated) {
            if (!Float.isFinite(offsetX) || !Float.isFinite(offsetY)) return;
            com.yandex.mapkit.map.Map map = mapWindow.getMap();
            if (map == null || !map.isValid()) return;
            CameraPosition current = map.getCameraPosition();
            ScreenPoint displayedTarget = mapWindow.worldToScreen(current.getTarget());
            ScreenPoint translatedTarget = translatedCameraTarget(
                    displayedTarget, offsetX, offsetY);
            if (translatedTarget == null) return;
            Point target = mapWindow.screenToWorld(translatedTarget);
            if (target == null) return;
            mapLayer.pauseFollowing();
            CameraPosition next = new CameraPosition(target, current.getZoom(),
                    current.getAzimuth(), current.getTilt());
            if (!animated) {
                map.move(next);
            } else {
                map.move(next, new Animation(Animation.Type.SMOOTH, 0.28f));
            }
        }
    }
}
