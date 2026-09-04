package ru.gpsantiradar.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.car.app.CarContext;
import androidx.car.app.AppManager;
import androidx.car.app.SurfaceContainer;
import androidx.car.app.model.Action;
import androidx.car.app.model.MessageTemplate;
import androidx.car.app.navigation.model.NavigationTemplate;
import androidx.car.app.testing.TestAppManager;
import androidx.car.app.testing.TestCarContext;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public final class CarTemplateTest {
    @Test public void setupScreenExplainsMissingRequirements() {
        CarContext carContext = carContext();
        MessageTemplate template = (MessageTemplate) new CarSetupScreen(
                carContext, false, false).onGetTemplate();

        assertEquals("Настройка GPS AntiRadar", template.getTitle().toString());
        assertTrue(template.getMessage().toString().contains("геопозиции"));
        assertTrue(template.getMessage().toString().contains("MapKit"));
        assertEquals(Action.TYPE_BACK, template.getHeaderAction().getType());
    }

    @Test public void mapScreenPublishesNavigationActionsAndRegistersSurface() {
        CarContext carContext = carContext();
        CarSurfaceController controller = new CarSurfaceController(
                carContext, null, (spec, surface) -> new NoOpSurfaceResource());
        NavigationTemplate template =
                (NavigationTemplate) new CarMapScreen(carContext, controller).onGetTemplate();

        assertFalse(template.getActionStrip().getActions().isEmpty());
        assertEquals(4, template.getMapActionStrip().getActions().size());
        assertSame(Action.PAN, template.getMapActionStrip().getActions().get(0));
        TestAppManager appManager =
                (TestAppManager) carContext.getCarService(AppManager.class);
        assertSame(controller, appManager.getSurfaceCallback());
        controller.destroy();
    }

    @Test public void replacingSurfaceReleasesOldResourceBeforeCreatingNewOne() {
        CarContext carContext = carContext();
        List<String> events = new ArrayList<>();
        CarSurfaceController controller = new CarSurfaceController(
                carContext, null, (spec, surface) -> {
                    events.add("create:" + spec.width);
                    return new RecordingSurfaceResource(events);
                });

        controller.onSurfaceAvailable(new SurfaceContainer(null, 800, 480, 160));
        controller.onSurfaceAvailable(new SurfaceContainer(null, 1280, 720, 240));

        assertEquals(Arrays.asList("create:800", "release", "create:1280"), events);
        controller.destroy();
    }

    @Test public void staleDestroyDoesNotReleaseReplacementAndDestroyIsIdempotent() {
        CarContext carContext = carContext();
        List<String> events = new ArrayList<>();
        CarSurfaceController controller = new CarSurfaceController(
                carContext, null, (spec, surface) -> {
                    events.add("create:" + spec.width);
                    return new NamedSurfaceResource(events, spec.width);
                });
        SurfaceContainer oldSurface = new SurfaceContainer(null, 800, 480, 160);
        SurfaceContainer replacement = new SurfaceContainer(null, 1280, 720, 240);

        controller.onSurfaceAvailable(oldSurface);
        controller.onSurfaceAvailable(replacement);
        controller.onSurfaceDestroyed(oldSurface);
        controller.zoomBy(1f);
        controller.destroy();
        controller.destroy();

        assertEquals(Arrays.asList(
                "create:800", "release:800", "create:1280",
                "zoom:1280", "release:1280"), events);
    }

    private static CarContext carContext() {
        Context context = ApplicationProvider.getApplicationContext();
        return TestCarContext.createCarContext(context);
    }

    private static class NoOpSurfaceResource
            implements CarSurfaceController.SurfaceResource {
        @Override public void onDrivingSnapshot(DrivingSnapshot snapshot) {}
        @Override public void zoomBy(float delta) {}
        @Override public void recenter() {}
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

    private static final class RecordingSurfaceResource extends NoOpSurfaceResource {
        private final List<String> events;

        RecordingSurfaceResource(List<String> events) {
            this.events = events;
        }

        @Override public void release() {
            events.add("release");
        }
    }

    private static final class NamedSurfaceResource extends NoOpSurfaceResource {
        private final List<String> events;
        private final int width;

        NamedSurfaceResource(List<String> events, int width) {
            this.events = events;
            this.width = width;
        }

        @Override public void zoomBy(float delta) {
            events.add("zoom:" + width);
        }

        @Override public void release() {
            events.add("release:" + width);
        }
    }
}
