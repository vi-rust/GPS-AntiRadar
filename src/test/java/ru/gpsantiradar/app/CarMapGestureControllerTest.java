package ru.gpsantiradar.app;

import com.yandex.mapkit.ScreenPoint;
import com.yandex.mapkit.geometry.Point;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public final class CarMapGestureControllerTest {
    @Test public void panOffsetStartsAtDisplayedCameraTarget() {
        ScreenPoint translated = CarMapGestureController.translatedCameraTarget(
                new ScreenPoint(280f, 160f), 36f, -18f);

        assertEquals(316f, translated.getX(), 0f);
        assertEquals(142f, translated.getY(), 0f);
    }

    @Test public void touchDragPansWithoutHostPanMode() {
        RecordingGestureTarget target = new RecordingGestureTarget();
        CarMapGestureController controller =
                new CarMapGestureController(target, null);

        controller.onScroll(36f, -18f);

        assertEquals(1, target.panCount);
        assertEquals(36f, target.panX, 0f);
        assertEquals(-18f, target.panY, 0f);
        assertFalse(target.panAnimated);
    }

    @Test public void consecutivePinchStepsUseImmediateZoom() {
        RecordingGestureTarget target = new RecordingGestureTarget();
        CarMapGestureController controller =
                new CarMapGestureController(target, null);

        controller.onScale(120f, 80f, 2f);
        controller.onScale(120f, 80f, 0.5f);

        assertEquals(2, target.zoomCount);
        assertEquals(0f, target.totalZoomDelta, 0.0001f);
        assertFalse(target.zoomAnimated);
    }

    private static final class RecordingGestureTarget
            implements CarMapGestureController.GestureTarget {
        int panCount;
        float panX;
        float panY;
        boolean panAnimated;
        int zoomCount;
        float totalZoomDelta;
        boolean zoomAnimated;

        @Override public void zoomBy(float delta, boolean animated) {
            zoomCount++;
            totalZoomDelta += delta;
            zoomAnimated = animated;
        }

        @Override public void recenter() {}

        @Override public void pauseFollowing() {}

        @Override public void panBy(float offsetX, float offsetY, boolean animated) {
            panCount++;
            panX += offsetX;
            panY += offsetY;
            panAnimated = animated;
        }

        @Override public Point pointAt(float x, float y) {
            return null;
        }
    }
}
