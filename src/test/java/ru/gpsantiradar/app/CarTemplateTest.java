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
import androidx.car.app.OnDoneCallback;
import androidx.car.app.Screen;
import androidx.car.app.SurfaceContainer;
import androidx.car.app.model.Action;
import androidx.car.app.model.Item;
import androidx.car.app.model.ListTemplate;
import androidx.car.app.model.LongMessageTemplate;
import androidx.car.app.model.MessageTemplate;
import androidx.car.app.model.PaneTemplate;
import androidx.car.app.model.Row;
import androidx.car.app.model.SearchTemplate;
import androidx.car.app.navigation.model.NavigationTemplate;
import androidx.car.app.testing.ScreenController;
import androidx.car.app.testing.TestAppManager;
import androidx.car.app.testing.TestCarContext;
import androidx.car.app.testing.TestScreenManager;
import androidx.car.app.validation.HostValidator;
import androidx.test.core.app.ApplicationProvider;
import androidx.lifecycle.Lifecycle;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Robolectric;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;

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

    @Test public void mapMenuActionPushesCarMenuScreen() {
        CarContext carContext = carContext();
        CarSurfaceController controller = new CarSurfaceController(
                carContext, null, (spec, surface) -> new NoOpSurfaceResource());
        NavigationTemplate template =
                (NavigationTemplate) new CarMapScreen(carContext, controller)
                        .onGetTemplate();

        assertTrue(template.getActionStrip().getActions().get(0).getIcon() != null);
        click(template.getActionStrip().getActions().get(0));

        TestScreenManager screenManager = (TestScreenManager)
                carContext.getCarService(androidx.car.app.ScreenManager.class);
        assertTrue(screenManager.getScreensPushed().get(0) instanceof CarMenuScreen);
        controller.destroy();
    }

    @Test public void carValueSettingsUseSharedRangesAndRussianFormatting() {
        assertSetting(CarValueScreen.Setting.ALERT_DISTANCE,
                AppSettings.ALERT_DISTANCE, 300, 2000, 100, "800 м");
        assertSetting(CarValueScreen.Setting.OVERSPEED_THRESHOLD,
                AppSettings.OVERSPEED_THRESHOLD, 0, 20, 1, "10 км/ч");
        assertSetting(CarValueScreen.Setting.HUD_TRANSPARENCY,
                AppSettings.HUD_TRANSPARENCY, 0, 80, 5, "10%");
    }

    @Test public void carValueActionsPersistClampedValuesAndRefreshHud() {
        CarContext carContext = carContext();
        Context context = carContext;
        context.getSharedPreferences(AppSettings.PREFERENCES, Context.MODE_PRIVATE)
                .edit().clear().commit();
        int[] hudRefreshes = { 0 };
        CarValueScreen screen = new CarValueScreen(carContext,
                CarValueScreen.Setting.HUD_TRANSPARENCY, () -> hudRefreshes[0]++);
        PaneTemplate initial = (PaneTemplate) screen.onGetTemplate();

        assertEquals("Прозрачность HUD", initial.getTitle().toString());
        assertEquals("10%", initial.getPane().getRows().get(0).getTitle().toString());
        assertEquals(2, initial.getPane().getActions().size());
        click(initial.getPane().getActions().get(0));
        assertEquals(5, context.getSharedPreferences(
                AppSettings.PREFERENCES, Context.MODE_PRIVATE).getInt(
                AppSettings.HUD_TRANSPARENCY, -1));
        assertEquals(1, hudRefreshes[0]);

        context.getSharedPreferences(AppSettings.PREFERENCES, Context.MODE_PRIVATE)
                .edit().putInt(AppSettings.HUD_TRANSPARENCY, 80).commit();
        click(((PaneTemplate) screen.onGetTemplate()).getPane().getActions().get(1));
        assertEquals(80, context.getSharedPreferences(
                AppSettings.PREFERENCES, Context.MODE_PRIVATE).getInt(
                AppSettings.HUD_TRANSPARENCY, -1));
        assertEquals(2, hudRefreshes[0]);
    }

    @Test public void carMenuShowsSevenActionsAndRoutesEverySettingsScreen() {
        CarContext carContext = carContext();
        FakeUpdateController updates = new FakeUpdateController();
        CarMenuScreen screen = new CarMenuScreen(
                carContext, null, updates, () -> {});
        ListTemplate template = (ListTemplate) screen.onGetTemplate();
        List<Item> items = template.getSingleList().getItems();

        assertEquals(Action.TYPE_BACK, template.getHeaderAction().getType());
        assertEquals(Arrays.asList(
                "Обновить базу",
                "Расстояние оповещения",
                "Предел превышения для beep",
                "Прозрачность HUD",
                "Ключ MapKit",
                "О программе",
                "Выход"), rowTitles(items));

        TestScreenManager screenManager = (TestScreenManager)
                carContext.getCarService(androidx.car.app.ScreenManager.class);
        String[] expectedTitles = {
                "Расстояние оповещения",
                "Предел превышения для beep",
                "Прозрачность HUD"
        };
        for (int index = 1; index <= 3; index++) {
            screenManager.reset();
            click((Row) items.get(index));
            Screen pushed = screenManager.getScreensPushed().get(0);
            assertTrue(pushed instanceof CarValueScreen);
            assertEquals(expectedTitles[index - 1],
                    ((PaneTemplate) pushed.onGetTemplate()).getTitle().toString());
        }
        screenManager.reset();
        click((Row) items.get(4));
        assertTrue(screenManager.getScreensPushed().get(0) instanceof CarMapKeyScreen);
        screenManager.reset();
        click((Row) items.get(5));
        assertTrue(screenManager.getScreensPushed().get(0) instanceof CarAboutScreen);
    }

    @Test public void carMenuUsesSharedUpdaterToShowEveryStateAndExplicitExit() {
        CarContext carContext = carContext();
        FakeUpdateController updates = new FakeUpdateController();
        int[] exits = { 0 };
        CarMenuScreen screen = new CarMenuScreen(
                carContext, null, updates, () -> exits[0]++);
        ScreenController lifecycle = new ScreenController(screen);
        lifecycle.moveToState(Lifecycle.State.STARTED);
        List<Item> items = ((ListTemplate) screen.onGetTemplate())
                .getSingleList().getItems();

        click((Row) items.get(0));
        assertEquals(1, updates.requestCount);
        updates.emit(RadarBaseUpdateState.Status.STARTED, "Начато");
        updates.emit(RadarBaseUpdateState.Status.UNCHANGED, "Без изменений");
        updates.emit(RadarBaseUpdateState.Status.SUCCESS, "Готово");
        updates.emit(RadarBaseUpdateState.Status.ERROR, "Ошибка");
        updates.emit(RadarBaseUpdateState.Status.ALREADY_RUNNING, "Уже выполняется");
        TestAppManager appManager =
                (TestAppManager) carContext.getCarService(AppManager.class);
        assertEquals(Arrays.asList(
                "Начато", "Без изменений", "Готово", "Ошибка", "Уже выполняется"),
                appManager.getToastsShown());

        click((Row) items.get(6));
        assertEquals(1, exits[0]);
        lifecycle.moveToState(Lifecycle.State.DESTROYED);
        assertEquals(1, updates.removeCount);
    }

    @Test public void mapKeyScreenRejectsBlankAndPersistsTrimmedKey() {
        CarContext carContext = carContext();
        Context context = carContext;
        context.getSharedPreferences(AppSettings.PREFERENCES, Context.MODE_PRIVATE)
                .edit().clear().commit();
        SearchTemplate template =
                (SearchTemplate) new CarMapKeyScreen(carContext).onGetTemplate();

        assertEquals(Action.TYPE_BACK, template.getHeaderAction().getType());
        assertEquals("Ключ Yandex MapKit", template.getSearchHint());
        assertTrue(template.isShowKeyboardByDefault());
        assertTrue(((Row) template.getItemList().getItems().get(0))
                .getTexts().get(0).toString().contains("телефоне"));

        template.getSearchCallbackDelegate().sendSearchSubmitted(
                "   ", new OnDoneCallback() {});
        assertFalse(context.getSharedPreferences(
                AppSettings.PREFERENCES, Context.MODE_PRIVATE)
                .contains(AppSettings.MAPKIT_KEY));

        template.getSearchCallbackDelegate().sendSearchSubmitted(
                "  test-map-key  ", new OnDoneCallback() {});
        assertEquals("test-map-key", context.getSharedPreferences(
                AppSettings.PREFERENCES, Context.MODE_PRIVATE)
                .getString(AppSettings.MAPKIT_KEY, ""));
        TestAppManager appManager =
                (TestAppManager) carContext.getCarService(AppManager.class);
        assertTrue(appManager.getToastsShown().get(0).toString().contains("пустым"));
        assertTrue(appManager.getToastsShown().get(1).toString().contains("перезапустите"));
    }

    @Test public void aboutScreenLoadsCountOnBackgroundAndShowsOnlyKnownHistory() {
        CarContext carContext = carContext();
        carContext.getSharedPreferences(AppSettings.PREFERENCES, Context.MODE_PRIVATE)
                .edit().putLong(AppSettings.RADARBASE_LAST_SUCCESSFUL_DOWNLOAD, 0L)
                .commit();
        QueuedExecutor background = new QueuedExecutor();
        QueuedExecutor main = new QueuedExecutor();
        CarAboutScreen screen = new CarAboutScreen(
                carContext, () -> 12345, background, main);
        ScreenController lifecycle = new ScreenController(screen);

        String loading = ((LongMessageTemplate) screen.onGetTemplate())
                .getMessage().toString();
        assertTrue(loading.contains("Объектов в базе: загрузка…"));
        lifecycle.moveToState(Lifecycle.State.STARTED);
        assertEquals(1, background.tasks.size());
        background.runNext();
        assertTrue(((LongMessageTemplate) screen.onGetTemplate())
                .getMessage().toString().contains("загрузка…"));
        main.runNext();

        LongMessageTemplate loaded = (LongMessageTemplate) screen.onGetTemplate();
        String message = loaded.getMessage().toString();
        assertEquals(Action.TYPE_BACK, loaded.getHeaderAction().getType());
        assertTrue(message.contains("Версия " + BuildConfig.VERSION_NAME));
        assertTrue(message.contains(java.text.NumberFormat
                .getIntegerInstance().format(12345)));
        assertTrue(message.contains("Последняя успешная загрузка: не выполнялась"));
        for (ReleaseHistory.Entry entry : ReleaseHistory.entries()) {
            assertTrue(message.contains("Версия " + entry.version));
            assertTrue(message.contains(entry.changes));
        }
        lifecycle.moveToState(Lifecycle.State.DESTROYED);
    }

    @Test public void aboutScreenDropsAsyncCountAfterDestroy() {
        CarContext carContext = carContext();
        QueuedExecutor background = new QueuedExecutor();
        QueuedExecutor main = new QueuedExecutor();
        CarAboutScreen screen = new CarAboutScreen(
                carContext, () -> 77, background, main);
        ScreenController lifecycle = new ScreenController(screen);
        lifecycle.moveToState(Lifecycle.State.STARTED);
        background.runNext();
        lifecycle.moveToState(Lifecycle.State.DESTROYED);
        main.runNext();

        assertTrue(((LongMessageTemplate) screen.onGetTemplate())
                .getMessage().toString().contains("загрузка…"));
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

    @Test public void exactSurfaceWithChangedSpecRecreatesBeforeRelease() {
        CarContext carContext = carContext();
        List<String> events = new ArrayList<>();
        RecordingSurfaceReleaser surfaces = new RecordingSurfaceReleaser();
        TestSurface testSurface = new TestSurface();
        CarSurfaceController controller = new CarSurfaceController(
                carContext, null, (spec, surface) -> {
                    assertTrue("factory received a released Surface", surface.isValid());
                    events.add("create:" + spec.width + "x" + spec.height
                            + "@" + spec.dpi);
                    return new NamedSurfaceResource(events, spec.width);
                }, surfaces, message -> fail(message));

        controller.onSurfaceAvailable(new SurfaceContainer(
                testSurface.surface, 800, 480, 160));
        controller.onSurfaceAvailable(new SurfaceContainer(
                testSurface.surface, 1280, 720, 240));

        assertEquals(Arrays.asList(
                "create:800x480@160", "release:800",
                "create:1280x720@240"), events);
        assertEquals(0, surfaces.releaseCount(testSurface.surface));
        controller.zoomBy(1f);
        controller.destroy();
        assertEquals(Arrays.asList(
                "create:800x480@160", "release:800",
                "create:1280x720@240", "zoom:1280", "release:1280"), events);
        assertEquals(1, surfaces.releaseCount(testSurface.surface));
        testSurface.close();
    }

    @Test public void exactSurfaceWithUnusableSpecReleasesResourceAndSurface() {
        CarContext carContext = carContext();
        List<String> events = new ArrayList<>();
        RecordingSurfaceReleaser surfaces = new RecordingSurfaceReleaser();
        TestSurface testSurface = new TestSurface();
        CarSurfaceController controller = new CarSurfaceController(
                carContext, null, (spec, surface) -> {
                    events.add("create:" + spec.width);
                    return new NamedSurfaceResource(events, spec.width);
                }, surfaces, message -> fail(message));

        controller.onSurfaceAvailable(new SurfaceContainer(
                testSurface.surface, 800, 480, 160));
        controller.onSurfaceAvailable(new SurfaceContainer(
                testSurface.surface, 0, 480, 160));
        controller.zoomBy(1f);

        assertEquals(Arrays.asList("create:800", "release:800"), events);
        assertEquals(1, surfaces.releaseCount(testSurface.surface));
        controller.destroy();
        assertEquals(1, surfaces.releaseCount(testSurface.surface));
        testSurface.close();
    }

    @Test public void exactSurfaceChangedSpecFailureReleasesSurfaceAndReports() {
        CarContext carContext = carContext();
        List<String> events = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        RecordingSurfaceReleaser surfaces = new RecordingSurfaceReleaser();
        TestSurface testSurface = new TestSurface();
        CarSurfaceController controller = new CarSurfaceController(
                carContext, null, (spec, surface) -> {
                    assertTrue("factory received a released Surface", surface.isValid());
                    events.add("create:" + spec.width);
                    if (spec.width == 1280) {
                        throw new IllegalStateException("replacement failed");
                    }
                    return new NamedSurfaceResource(events, spec.width);
                }, surfaces, failures::add);

        controller.onSurfaceAvailable(new SurfaceContainer(
                testSurface.surface, 800, 480, 160));
        controller.onSurfaceAvailable(new SurfaceContainer(
                testSurface.surface, 1280, 720, 240));

        assertEquals(Arrays.asList(
                "create:800", "release:800", "create:1280"), events);
        assertEquals(1, surfaces.releaseCount(testSurface.surface));
        assertEquals(1, failures.size());
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

    private static void assertSetting(CarValueScreen.Setting setting,
            String key, int min, int max, int step, String formattedDefault) {
        assertEquals(key, setting.preferenceKey());
        assertEquals(min, setting.minValue());
        assertEquals(max, setting.maxValue());
        assertEquals(step, setting.step());
        assertEquals(formattedDefault, setting.format(setting.defaultValue()));
    }

    private static void click(Action action) {
        action.getOnClickDelegate().sendClick(new OnDoneCallback() {});
    }

    private static void click(Row row) {
        row.getOnClickDelegate().sendClick(new OnDoneCallback() {});
    }

    private static List<String> rowTitles(List<Item> items) {
        List<String> titles = new ArrayList<>();
        for (Item item : items) {
            titles.add(((Row) item).getTitle().toString());
        }
        return titles;
    }

    private static final class FakeUpdateController
            implements CarMenuScreen.UpdateController {
        RadarBaseUpdater.Listener listener;
        int requestCount;
        int removeCount;
        long sequence;

        @Override public void requestUpdate() {
            requestCount++;
        }

        @Override public void addListener(
                RadarBaseUpdater.Listener listener, boolean replayLatest) {
            this.listener = listener;
        }

        @Override public void removeListener(RadarBaseUpdater.Listener listener) {
            if (this.listener == listener) {
                this.listener = null;
                removeCount++;
            }
        }

        void emit(RadarBaseUpdateState.Status status, String message) {
            listener.onRadarBaseUpdate(new RadarBaseUpdateState(
                    ++sequence, status, 0, false, message));
        }
    }

    private static final class QueuedExecutor implements Executor {
        final List<Runnable> tasks = new ArrayList<>();

        @Override public void execute(Runnable command) {
            tasks.add(command);
        }

        void runNext() {
            tasks.remove(0).run();
        }
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
