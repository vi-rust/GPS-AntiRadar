package ru.gpsantiradar.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.view.View;
import android.view.Surface;
import android.widget.FrameLayout;

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
import java.util.List;

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

    @Test public void destroyWithDifferentWrapperReleasesCurrentOrderedSurface() {
        CarContext carContext = carContext();
        List<String> events = new ArrayList<>();
        RecordingSurfaceReleaser surfaces = new RecordingSurfaceReleaser();
        TestSurface testSurface = new TestSurface();
        Surface activeWrapper = testSurface.surface;
        Surface destroyWrapper = testSurface.newWrapper();
        CarSurfaceController controller = new CarSurfaceController(
                carContext, null,
                (spec, surface) -> new RecordingSurfaceResource(events),
                surfaces, message -> fail(message));

        controller.onSurfaceAvailable(
                new SurfaceContainer(activeWrapper, 800, 480, 160));
        controller.onSurfaceDestroyed(
                new SurfaceContainer(destroyWrapper, 800, 480, 160));
        controller.destroy();
        controller.destroy();

        assertFalse(activeWrapper == destroyWrapper);
        assertEquals(Arrays.asList("release"), events);
        assertEquals(1, surfaces.releaseCount(activeWrapper));
        assertEquals(1, surfaces.releaseCount(destroyWrapper));
        testSurface.close();
    }

    @Test public void staleDifferentSpecDestroyDoesNotReleaseReplacement() {
        CarContext carContext = carContext();
        List<String> events = new ArrayList<>();
        RecordingSurfaceReleaser surfaces = new RecordingSurfaceReleaser();
        TestSurface oldSurface = new TestSurface();
        TestSurface replacement = new TestSurface();
        CarSurfaceController controller = new CarSurfaceController(
                carContext, null, (spec, surface) -> {
                    events.add("create:" + spec.width);
                    return new NamedSurfaceResource(events, spec.width);
                }, surfaces, message -> fail(message));
        SurfaceContainer oldContainer = new SurfaceContainer(
                oldSurface.surface, 800, 480, 160);

        controller.onSurfaceAvailable(oldContainer);
        controller.onSurfaceAvailable(new SurfaceContainer(
                replacement.surface, 1280, 720, 240));
        controller.onSurfaceDestroyed(oldContainer);
        controller.zoomBy(1f);
        controller.destroy();

        assertEquals(Arrays.asList(
                "create:800", "release:800", "create:1280",
                "zoom:1280", "release:1280"), events);
        assertEquals(2, surfaces.releaseCount(oldSurface.surface));
        assertEquals(1, surfaces.releaseCount(replacement.surface));
        oldSurface.close();
        replacement.close();
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

        assertEquals(Arrays.asList("release"), events);
        assertEquals(1, surfaces.releaseCount(testSurface.surface));
        controller.destroy();
        controller.destroy();
        testSurface.close();
    }

    @Test public void repeatedAvailabilityUsesFreshWrapperWithoutDoubleUse() {
        CarContext carContext = carContext();
        List<Surface> factorySurfaces = new ArrayList<>();
        RecordingSurfaceReleaser surfaces = new RecordingSurfaceReleaser();
        TestSurface testSurface = new TestSurface();
        Surface firstWrapper = testSurface.surface;
        Surface secondWrapper = testSurface.newWrapper();
        CarSurfaceController controller = new CarSurfaceController(
                carContext, null, (spec, surface) -> {
                    assertTrue("factory received a released Surface", surface.isValid());
                    factorySurfaces.add(surface);
                    return new NoOpSurfaceResource();
                }, surfaces, message -> fail(message));

        controller.onSurfaceAvailable(
                new SurfaceContainer(firstWrapper, 800, 480, 160));
        controller.onSurfaceAvailable(
                new SurfaceContainer(secondWrapper, 800, 480, 160));

        assertFalse(firstWrapper == secondWrapper);
        assertEquals(Arrays.asList(firstWrapper, secondWrapper), factorySurfaces);
        assertEquals(1, surfaces.releaseCount(firstWrapper));
        assertEquals(0, surfaces.releaseCount(secondWrapper));
        controller.destroy();
        assertEquals(1, surfaces.releaseCount(secondWrapper));
        testSurface.close();
    }

    @Test public void exactRepeatedSurfaceIsNotReleasedAndReused() {
        CarContext carContext = carContext();
        int[] creates = { 0 };
        RecordingSurfaceReleaser surfaces = new RecordingSurfaceReleaser();
        TestSurface testSurface = new TestSurface();
        CarSurfaceController controller = new CarSurfaceController(
                carContext, null, (spec, surface) -> {
                    assertTrue("factory received a released Surface", surface.isValid());
                    creates[0]++;
                    return new NoOpSurfaceResource();
                }, surfaces, message -> fail(message));
        SurfaceContainer container = new SurfaceContainer(
                testSurface.surface, 800, 480, 160);

        controller.onSurfaceAvailable(container);
        controller.onSurfaceAvailable(container);

        assertEquals(1, creates[0]);
        assertEquals(0, surfaces.releaseCount(testSurface.surface));
        controller.destroy();
        assertEquals(1, surfaces.releaseCount(testSurface.surface));
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

    @Test public void emptyAreasClearActiveGenerationAndAreNotReplayed() {
        CarContext carContext = carContext();
        List<AreaRecordingSurfaceResource> resources = new ArrayList<>();
        CarSurfaceController controller = new CarSurfaceController(
                carContext, null, (spec, surface) -> {
                    AreaRecordingSurfaceResource resource =
                            new AreaRecordingSurfaceResource();
                    resources.add(resource);
                    return resource;
                });

        controller.onStableAreaChanged(new Rect());
        controller.onVisibleAreaChanged(new Rect());
        controller.onSurfaceAvailable(new SurfaceContainer(null, 800, 480, 160));
        controller.onStableAreaChanged(new Rect(10, 10, 790, 470));
        controller.onVisibleAreaChanged(new Rect(20, 20, 780, 460));
        controller.onStableAreaChanged(new Rect());
        controller.onVisibleAreaChanged(new Rect());
        controller.onSurfaceAvailable(new SurfaceContainer(null, 1280, 720, 240));

        assertEquals(2, resources.size());
        assertEquals(Arrays.asList(new Rect(10, 10, 790, 470), null),
                resources.get(0).stableAreas);
        assertEquals(Arrays.asList(new Rect(20, 20, 780, 460), null),
                resources.get(0).visibleAreas);
        assertTrue(resources.get(1).stableAreas.isEmpty());
        assertTrue(resources.get(1).visibleAreas.isEmpty());
        controller.destroy();
    }

    @Test public void validAreasAreNotReplayedToReplacementGeneration() {
        CarContext carContext = carContext();
        List<AreaRecordingSurfaceResource> resources = new ArrayList<>();
        CarSurfaceController controller = new CarSurfaceController(
                carContext, null, (spec, surface) -> {
                    AreaRecordingSurfaceResource resource =
                            new AreaRecordingSurfaceResource();
                    resources.add(resource);
                    return resource;
                });

        controller.onSurfaceAvailable(new SurfaceContainer(null, 800, 480, 160));
        controller.onStableAreaChanged(new Rect(10, 10, 790, 470));
        controller.onVisibleAreaChanged(new Rect(20, 20, 780, 460));
        controller.onSurfaceAvailable(new SurfaceContainer(null, 1280, 720, 240));

        assertEquals(2, resources.size());
        assertEquals(Arrays.asList(new Rect(10, 10, 790, 470)),
                resources.get(0).stableAreas);
        assertEquals(Arrays.asList(new Rect(20, 20, 780, 460)),
                resources.get(0).visibleAreas);
        assertTrue(resources.get(1).stableAreas.isEmpty());
        assertTrue(resources.get(1).visibleAreas.isEmpty());
        controller.destroy();
    }

    @Test public void emptyStableAreaRestoresDefaultHudLayout() {
        Context context = ApplicationProvider.getApplicationContext();
        CarMapPresentation presentation =
                new CarMapPresentation(context, null, carContext());
        FrameLayout root = (FrameLayout) presentation.rootView();
        View hud = root.getChildAt(0);
        FrameLayout.LayoutParams defaults =
                (FrameLayout.LayoutParams) hud.getLayoutParams();
        int defaultLeft = defaults.leftMargin;
        int defaultBottom = defaults.bottomMargin;
        int defaultWidth = defaults.width;

        presentation.onStableAreaChanged(new Rect(100, 20, 700, 400));
        assertTrue(((FrameLayout.LayoutParams) hud.getLayoutParams()).leftMargin
                > defaultLeft);
        presentation.onStableAreaChanged(new Rect());

        FrameLayout.LayoutParams reset =
                (FrameLayout.LayoutParams) hud.getLayoutParams();
        assertEquals(defaultLeft, reset.leftMargin);
        assertEquals(defaultBottom, reset.bottomMargin);
        assertEquals(defaultWidth, reset.width);
        presentation.destroy();
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
            stableAreas.add(area == null ? null : new Rect(area));
        }

        @Override public void onVisibleAreaChanged(Rect area) {
            visibleAreas.add(area == null ? null : new Rect(area));
        }
    }

    private static final class RecordingSurfaceReleaser
            implements CarSurfaceController.SurfaceReleaser {
        private final List<Surface> releasedSurfaces = new ArrayList<>();
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
            releasedSurfaces.add(surface);
            if (events != null) {
                events.add(surface == firstSurface ? "surface:old" : "surface:new");
            }
            surface.release();
        }

        int releaseCount(Surface surface) {
            int count = 0;
            for (Surface released : releasedSurfaces) {
                if (released == surface) count++;
            }
            return count;
        }
    }

    private static final class TestSurface {
        final SurfaceTexture texture = new SurfaceTexture(0);
        final List<Surface> wrappers = new ArrayList<>();
        final Surface surface = newWrapper();

        Surface newWrapper() {
            Surface wrapper = new Surface(texture);
            wrappers.add(wrapper);
            return wrapper;
        }

        void close() {
            for (Surface wrapper : wrappers) wrapper.release();
            texture.release();
        }
    }
}
