package ru.gpsantiradar.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.view.Surface;

import androidx.car.app.CarContext;
import androidx.car.app.AppManager;
import androidx.car.app.SurfaceContainer;
import androidx.car.app.model.Action;
import androidx.car.app.model.MessageTemplate;
import androidx.car.app.navigation.model.NavigationTemplate;
import androidx.car.app.testing.TestAppManager;
import androidx.car.app.testing.TestCarContext;
import androidx.car.app.validation.HostValidator;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Robolectric;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

@RunWith(RobolectricTestRunner.class)
public final class CarTemplateTest {
    @Test public void projectedServiceLoadsOfficialHostAllowlist() {
        GpsCarAppService service = Robolectric.buildService(GpsCarAppService.class)
                .create().get();

        HostValidator validator = service.createHostValidator();

        assertFalse(validator.getAllowedHosts().isEmpty());
    }

    @Test public void setupScreenExplainsMissingRequirements() {
        CarContext carContext = carContext();
        MessageTemplate template = (MessageTemplate) new CarSetupScreen(
                carContext, false, false).onGetTemplate();

        assertEquals("Настройка GPS AntiRadar", template.getTitle().toString());
        assertTrue(template.getMessage().toString().contains("геопозиции"));
        assertTrue(template.getMessage().toString().contains("MapKit"));
        assertEquals(Action.TYPE_BACK, template.getHeaderAction().getType());
    }

    @Test public void setupScreenExplainsSurfaceFailure() {
        CarContext carContext = carContext();
        MessageTemplate template = (MessageTemplate) new CarSetupScreen(
                carContext, true, true, "Не удалось создать поверхность карты.")
                .onGetTemplate();

        assertTrue(template.getMessage().toString().contains(
                "Не удалось создать поверхность карты."));
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
        RecordingSurfaceReleaser surfaces = new RecordingSurfaceReleaser(events);
        TestSurface oldSurface = new TestSurface();
        TestSurface replacement = new TestSurface();
        CarSurfaceController controller = new CarSurfaceController(
                carContext, null, (spec, surface) -> {
                    events.add("create:" + spec.width);
                    return new RecordingSurfaceResource(events);
                }, surfaces, message -> fail(message));

        controller.onSurfaceAvailable(
                new SurfaceContainer(oldSurface.surface, 800, 480, 160));
        controller.onSurfaceAvailable(
                new SurfaceContainer(replacement.surface, 1280, 720, 240));

        assertEquals(Arrays.asList(
                "create:800", "release", "surface:old", "create:1280"), events);
        assertEquals(1, surfaces.releaseCount(oldSurface.surface));
        assertEquals(0, surfaces.releaseCount(replacement.surface));
        controller.destroy();
        assertEquals(1, surfaces.releaseCount(replacement.surface));
        oldSurface.close();
        replacement.close();
    }

    @Test public void staleDestroyDoesNotReleaseReplacementAndDestroyIsIdempotent() {
        CarContext carContext = carContext();
        List<String> events = new ArrayList<>();
        RecordingSurfaceReleaser surfaces = new RecordingSurfaceReleaser();
        TestSurface oldSurfaceObject = new TestSurface();
        TestSurface replacementObject = new TestSurface();
        CarSurfaceController controller = new CarSurfaceController(
                carContext, null, (spec, surface) -> {
                    events.add("create:" + spec.width);
                    return new NamedSurfaceResource(events, spec.width);
                }, surfaces, message -> fail(message));
        SurfaceContainer oldSurface = new SurfaceContainer(
                oldSurfaceObject.surface, 800, 480, 160);
        SurfaceContainer replacement = new SurfaceContainer(
                replacementObject.surface, 1280, 720, 240);

        controller.onSurfaceAvailable(oldSurface);
        controller.onSurfaceAvailable(replacement);
        controller.onSurfaceDestroyed(oldSurface);
        controller.zoomBy(1f);
        controller.destroy();
        controller.destroy();

        assertEquals(Arrays.asList(
                "create:800", "release:800", "create:1280",
                "zoom:1280", "release:1280"), events);
        assertEquals(1, surfaces.releaseCount(oldSurfaceObject.surface));
        assertEquals(1, surfaces.releaseCount(replacementObject.surface));
        oldSurfaceObject.close();
        replacementObject.close();
    }

    @Test public void activeSurfaceDestroyReleasesResourceAndSurfaceOnce() {
        CarContext carContext = carContext();
        List<String> events = new ArrayList<>();
        RecordingSurfaceReleaser surfaces = new RecordingSurfaceReleaser();
        TestSurface testSurface = new TestSurface();
        CarSurfaceController controller = new CarSurfaceController(
                carContext, null,
                (spec, surface) -> new RecordingSurfaceResource(events),
                surfaces, message -> fail(message));
        SurfaceContainer container = new SurfaceContainer(
                testSurface.surface, 800, 480, 160);

        controller.onSurfaceAvailable(container);
        controller.onSurfaceDestroyed(container);
        controller.onSurfaceDestroyed(container);

        assertEquals(Arrays.asList("release"), events);
        assertEquals(1, surfaces.releaseCount(testSurface.surface));
        controller.destroy();
        testSurface.close();
    }

    @Test public void unusableSurfaceIsReleasedWithoutCallingFactory() {
        CarContext carContext = carContext();
        int[] creates = { 0 };
        RecordingSurfaceReleaser surfaces = new RecordingSurfaceReleaser();
        TestSurface testSurface = new TestSurface();
        CarSurfaceController controller = new CarSurfaceController(
                carContext, null, (spec, surface) -> {
                    creates[0]++;
                    return new NoOpSurfaceResource();
                }, surfaces, message -> fail(message));

        controller.onSurfaceAvailable(
                new SurfaceContainer(testSurface.surface, 0, 480, 160));

        assertEquals(0, creates[0]);
        assertEquals(1, surfaces.releaseCount(testSurface.surface));
        controller.destroy();
        testSurface.close();
    }

    @Test public void factoryFailureIsContainedAndReleasesSurface() {
        CarContext carContext = carContext();
        RecordingSurfaceReleaser surfaces = new RecordingSurfaceReleaser();
        List<String> failures = new ArrayList<>();
        TestSurface testSurface = new TestSurface();
        CarSurfaceController controller = new CarSurfaceController(
                carContext, null, (spec, surface) -> {
                    throw new IllegalStateException("factory failed");
                }, surfaces, failures::add);

        try {
            controller.onSurfaceAvailable(
                    new SurfaceContainer(testSurface.surface, 800, 480, 160));
        } catch (RuntimeException error) {
            fail("Surface failure escaped to the host: " + error);
        }

        assertEquals(1, surfaces.releaseCount(testSurface.surface));
        assertEquals(1, failures.size());
        assertTrue(failures.get(0).contains("Не удалось отобразить карту"));
        controller.destroy();
        testSurface.close();
    }

    @Test public void initializationFailureReleasesPartialResourceAndSurface() {
        CarContext carContext = carContext();
        RecordingSurfaceReleaser surfaces = new RecordingSurfaceReleaser();
        List<String> events = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        TestSurface testSurface = new TestSurface();
        CarSurfaceController controller = new CarSurfaceController(
                carContext, null,
                (spec, surface) -> new FailingInitializationResource(events),
                surfaces, failures::add);

        controller.onSurfaceAvailable(
                new SurfaceContainer(testSurface.surface, 800, 480, 160));

        assertEquals(Arrays.asList("configure", "release"), events);
        assertEquals(1, surfaces.releaseCount(testSurface.surface));
        assertEquals(1, failures.size());
        controller.destroy();
        testSurface.close();
    }

    @Test public void emptyAreasRemainUnknownAndAreNotForwarded() {
        CarContext carContext = carContext();
        AreaRecordingSurfaceResource resource =
                new AreaRecordingSurfaceResource();
        CarSurfaceController controller = new CarSurfaceController(
                carContext, null, (spec, surface) -> resource);

        controller.onStableAreaChanged(new Rect());
        controller.onVisibleAreaChanged(new Rect());
        controller.onSurfaceAvailable(new SurfaceContainer(null, 800, 480, 160));
        controller.onStableAreaChanged(new Rect(10, 10, 790, 470));
        controller.onVisibleAreaChanged(new Rect(20, 20, 780, 460));
        controller.onStableAreaChanged(new Rect());
        controller.onVisibleAreaChanged(new Rect());

        assertEquals(1, resource.stableAreas.size());
        assertEquals(new Rect(10, 10, 790, 470), resource.stableAreas.get(0));
        assertEquals(1, resource.visibleAreas.size());
        assertEquals(new Rect(20, 20, 780, 460), resource.visibleAreas.get(0));
        controller.destroy();
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

    private static final class FailingInitializationResource
            extends NoOpSurfaceResource {
        private final List<String> events;

        FailingInitializationResource(List<String> events) {
            this.events = events;
        }

        @Override public void onCarConfigurationChanged() {
            events.add("configure");
            throw new IllegalStateException("configuration failed");
        }

        @Override public void release() {
            events.add("release");
        }
    }

    private static final class AreaRecordingSurfaceResource
            extends NoOpSurfaceResource {
        final List<Rect> stableAreas = new ArrayList<>();
        final List<Rect> visibleAreas = new ArrayList<>();

        @Override public void onStableAreaChanged(Rect area) {
            stableAreas.add(new Rect(area));
        }

        @Override public void onVisibleAreaChanged(Rect area) {
            visibleAreas.add(new Rect(area));
        }
    }

    private static final class RecordingSurfaceReleaser
            implements CarSurfaceController.SurfaceReleaser {
        private final Map<Surface, Integer> releaseCounts = new IdentityHashMap<>();
        private final List<String> events;
        private Surface firstSurface;

        RecordingSurfaceReleaser() {
            this(null);
        }

        RecordingSurfaceReleaser(List<String> events) {
            this.events = events;
        }

        @Override public void release(Surface surface) {
            if (firstSurface == null) firstSurface = surface;
            releaseCounts.put(surface, releaseCount(surface) + 1);
            if (events != null) {
                events.add(surface == firstSurface ? "surface:old" : "surface:new");
            }
            surface.release();
        }

        int releaseCount(Surface surface) {
            Integer count = releaseCounts.get(surface);
            return count == null ? 0 : count;
        }
    }

    private static final class TestSurface {
        final SurfaceTexture texture = new SurfaceTexture(0);
        final Surface surface = new Surface(texture);

        void close() {
            surface.release();
            texture.release();
        }
    }
}
