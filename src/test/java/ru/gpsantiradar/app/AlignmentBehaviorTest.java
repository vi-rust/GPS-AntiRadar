package ru.gpsantiradar.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.view.View;
import android.widget.TextView;

import androidx.car.app.CarContext;
import androidx.car.app.testing.TestCarContext;
import androidx.car.app.model.MessageTemplate;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Robolectric;

import java.lang.reflect.Method;

@RunWith(RobolectricTestRunner.class)
public final class AlignmentBehaviorTest {
    @Test public void missingMapKitSetupKeepsRadarActiveAndOffersKeyEntry() {
        Context context = ApplicationProvider.getApplicationContext();
        MessageTemplate template = (MessageTemplate) new CarSetupScreen(
                carContext(context), true, false, true, null).onGetTemplate();

        assertTrue(template.getMessage().toString().contains(
                "Антирадар и звуковые предупреждения работают без карты"));
        assertEquals(1, template.getActions().size());
        assertEquals("Ввести ключ MapKit",
                template.getActions().get(0).getTitle().toString());
    }

    @Test public void carHudKeepsEmptyDatabaseStatusAcrossDrivingSnapshots() {
        Context context = ApplicationProvider.getApplicationContext();
        CarMapPresentation presentation = new CarMapPresentation(
                context, null, carContext(context));

        presentation.onDatabaseCount(0);
        presentation.onDrivingSnapshot(new DrivingSnapshot(
                50f, Float.NaN, -1, "", -1L, 0, 0,
                55.0, 37.0, 0f, "", ""));

        assertTrue(findText(presentation.rootView(),
                "База объектов пуста — обновите RadarBase") != null);
        presentation.destroy();
    }

    @Test public void stoppedTrackingClearsTheLastCarSnapshot() {
        Context context = ApplicationProvider.getApplicationContext();
        CarMapPresentation presentation = new CarMapPresentation(
                context, null, carContext(context));
        presentation.onDrivingSnapshot(new DrivingSnapshot(
                70f, Float.NaN, 400, "Камера", 42L, 60, 800,
                55.0, 37.0, 0f, "", ""));

        presentation.onTrackingStopped();

        assertTrue(findText(presentation.rootView(), "Антирадар остановлен") != null);
        assertTrue(findText(presentation.rootView(), "0") != null);
        presentation.destroy();
    }

    @Test public void surfaceRecenterReportsMissingAndAvailableLocation() {
        Context context = ApplicationProvider.getApplicationContext();
        CarContext carContext = carContext(context);
        RecordingSurfaceResource resource = new RecordingSurfaceResource();
        CarSurfaceController controller = new CarSurfaceController(
                carContext, null, (spec, surface) -> resource);

        assertFalse(controller.recenter());
        controller.onDatabaseCount(0);
        controller.onTrackingStopped();
        controller.onSurfaceAvailable(new androidx.car.app.SurfaceContainer(
                null, 800, 480, 160));
        assertEquals(1, resource.databaseCountUpdates);
        assertEquals(1, resource.stoppedUpdates);
        assertFalse(controller.recenter());
        controller.onDrivingSnapshot(new DrivingSnapshot(
                10f, Float.NaN, -1, "", -1L, 0, 0,
                55.0, 37.0, 0f, "", ""));
        assertTrue(controller.recenter());
        assertEquals(1, resource.recenterCount);
        controller.destroy();
    }

    @Test public void projectedCameraTapIsConsumedBeforeBackgroundClick() {
        RecordingGestureTarget target = new RecordingGestureTarget();
        target.cameraTap = true;
        int[] backgroundClicks = { 0 };
        CarMapGestureController controller = new CarMapGestureController(
                target, point -> backgroundClicks[0]++);

        controller.onClick(120f, 80f);

        assertEquals(1, target.cameraHitTests);
        assertEquals(0, backgroundClicks[0]);
    }

    @Test public void projectedEmptyTapReachesBackgroundClick() {
        RecordingGestureTarget target = new RecordingGestureTarget();
        int[] backgroundClicks = { 0 };
        CarMapGestureController controller = new CarMapGestureController(
                target, point -> backgroundClicks[0]++);

        controller.onClick(120f, 80f);

        assertEquals(1, target.cameraHitTests);
        assertEquals(1, backgroundClicks[0]);
    }

    @Test public void carHudReactsToThresholdChangedByAnotherSurface() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences(AppSettings.PREFERENCES, Context.MODE_PRIVATE)
                .edit().clear().putInt(AppSettings.OVERSPEED_THRESHOLD, 10).commit();
        CarMapPresentation presentation = new CarMapPresentation(
                context, null, carContext(context));
        Method register = CarMapPresentation.class.getDeclaredMethod(
                "registerSettingsListener");
        register.setAccessible(true);
        register.invoke(presentation);
        presentation.onDrivingSnapshot(new DrivingSnapshot(
                70f, Float.NaN, 400, "Камера", 42L, 60, 800,
                55.0, 37.0, 0f, "", ""));
        TextView speed = findText(presentation.rootView(), "70");
        assertEquals(DrivingHudPresentation.COLOR_OVERSPEED,
                speed.getCurrentTextColor());

        context.getSharedPreferences(AppSettings.PREFERENCES, Context.MODE_PRIVATE)
                .edit().putInt(AppSettings.OVERSPEED_THRESHOLD, 20).commit();
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();

        assertEquals(DrivingHudPresentation.COLOR_ALERT, speed.getCurrentTextColor());
        presentation.destroy();
    }

    @Test public void phoneHudReactsToThresholdChangedByAnotherSurface() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences(AppSettings.PREFERENCES, Context.MODE_PRIVATE)
                .edit().clear().putInt(AppSettings.OVERSPEED_THRESHOLD, 10).commit();
        org.robolectric.android.controller.ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).create().start().resume();
        MainActivity activity = controller.get();
        java.lang.reflect.Field receiverField = MainActivity.class.getDeclaredField("receiver");
        receiverField.setAccessible(true);
        BroadcastReceiver receiver = (BroadcastReceiver) receiverField.get(activity);
        receiver.onReceive(activity, new Intent(TrackingService.ACTION_UPDATE)
                .putExtra(TrackingService.EXTRA_SPEED, 70f)
                .putExtra(TrackingService.EXTRA_DISTANCE, 400)
                .putExtra(TrackingService.EXTRA_CAMERA, "Камера")
                .putExtra(TrackingService.EXTRA_CAMERA_ID, 42L)
                .putExtra(TrackingService.EXTRA_LIMIT, 60)
                .putExtra(TrackingService.EXTRA_ALERT_DISTANCE, 800));
        TextView speed = findText(activity.findViewById(android.R.id.content), "70");
        assertEquals(DrivingHudPresentation.COLOR_OVERSPEED,
                speed.getCurrentTextColor());

        context.getSharedPreferences(AppSettings.PREFERENCES, Context.MODE_PRIVATE)
                .edit().putInt(AppSettings.OVERSPEED_THRESHOLD, 20).commit();
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();

        assertEquals(DrivingHudPresentation.COLOR_ALERT, speed.getCurrentTextColor());
        controller.pause().stop().destroy();
    }

    private static CarContext carContext(Context context) {
        return TestCarContext.createCarContext(context);
    }

    private static TextView findText(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) {
            return (TextView) view;
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                TextView found = findText(group.getChildAt(index), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static final class RecordingSurfaceResource
            implements CarSurfaceController.SurfaceResource {
        int recenterCount;
        int databaseCountUpdates;
        int stoppedUpdates;

        @Override public void onDrivingSnapshot(DrivingSnapshot snapshot) {}
        @Override public void onDatabaseCount(int count) { databaseCountUpdates++; }
        @Override public void onTrackingStopped() { stoppedUpdates++; }
        @Override public void refreshVisible() {}
        @Override public void zoomBy(float delta) {}
        @Override public void recenter() { recenterCount++; }
        @Override public void setPanMode(boolean enabled) {}
        @Override public void onStableAreaChanged(android.graphics.Rect area) {}
        @Override public void onVisibleAreaChanged(android.graphics.Rect area) {}
        @Override public void onCarConfigurationChanged() {}
        @Override public void onScroll(float distanceX, float distanceY) {}
        @Override public void onFling(float velocityX, float velocityY) {}
        @Override public void onScale(float focusX, float focusY, float scaleFactor) {}
        @Override public void onClick(float x, float y) {}
        @Override public void release() {}
    }

    private static final class RecordingGestureTarget
            implements CarMapGestureController.GestureTarget {
        boolean cameraTap;
        int cameraHitTests;

        @Override public void zoomBy(float delta, boolean animated) {}
        @Override public void recenter() {}
        @Override public void pauseFollowing() {}
        @Override public void panBy(float offsetX, float offsetY, boolean animated) {}
        @Override public com.yandex.mapkit.geometry.Point pointAt(float x, float y) {
            return new com.yandex.mapkit.geometry.Point(55.0, 37.0);
        }
        @Override public boolean tapCameraAt(float x, float y) {
            cameraHitTests++;
            return cameraTap;
        }
    }
}
