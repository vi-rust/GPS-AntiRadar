package ru.gpsantiradar.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.view.Surface
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView

import androidx.car.app.CarContext
import androidx.car.app.AppManager
import androidx.car.app.OnDoneCallback
import androidx.car.app.Screen
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.Item
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.LongMessageTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.testing.ScreenController
import androidx.car.app.testing.TestAppManager
import androidx.car.app.testing.TestCarContext
import androidx.car.app.testing.TestScreenManager
import androidx.car.app.validation.HostValidator
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.Lifecycle

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.shadows.ShadowAlertDialog

import java.util.ArrayList
import java.util.Arrays
import java.util.concurrent.Executor
import java.lang.reflect.Method

@RunWith(RobolectricTestRunner::class)
 class CarTemplateTest {
@Test
fun releaseMetadataDescribesCurrentChanges() {
assertEquals("4.9.11", BuildConfig.VERSION_NAME)
val release = ReleaseHistory.find(BuildConfig.VERSION_NAME)
assertTrue(release != null)
assertTrue(release!!.changes.contains("подтверждённого"))
assertTrue(release!!.changes.contains("Android Auto"))
assertTrue(release!!.changes.contains("HUD"))
}

@Test @Throws(Exception::class)
fun themeTransitionKeepsCurrentPhoneViewAndHud() {
val context = ApplicationProvider.getApplicationContext<Context>()
context!!.getSharedPreferences(AppSettings.PREFERENCES, Context.MODE_PRIVATE)
.edit().clear().commit()
ThemeSettings.setMode(context, ThemeMode.LIGHT)
val activity = Robolectric.buildActivity(MainActivity::class.java)
.create().get()
val content = activity!!.findViewById<ViewGroup>(android.R.id.content)
val root = content!!.getChildAt(0)
val speed = findText(root, "0")
assertTrue(speed != null)
speed!!.setText("83")
val applyTheme = MainActivity::class.java!!.getDeclaredMethod(
"applyResolvedTheme", Boolean::class.javaPrimitiveType)
applyTheme!!.setAccessible(true)

assertTrue(applyTheme!!.invoke(activity, true) as Boolean)

assertSame(root, content!!.getChildAt(0))
assertEquals(Color.rgb(18, 18, 18),
(root!!.background as ColorDrawable).getColor())
assertEquals("83", speed!!.getText().toString())
assertFalse(activity!!.isFinishing())
activity!!.finish()
}

@Test
fun lightPhoneMenuPreservesSemanticIconColors() {
val context = ApplicationProvider.getApplicationContext<Context>()
context!!.getSharedPreferences(AppSettings.PREFERENCES, Context.MODE_PRIVATE)
.edit().clear().commit()
ThemeSettings.setMode(context, ThemeMode.LIGHT)
val activity = Robolectric.buildActivity(MainActivity::class.java)
.create().get()
val root = (activity!!.findViewById(android.R.id.content) as ViewGroup).getChildAt(0)
val menu = findByDescription(root!!, "Меню")
assertTrue(menu != null)
menu!!.performClick()
val dialog = ShadowAlertDialog.getLatestAlertDialog()
val exit = findText(dialog!!.getWindow()!!.getDecorView(), "Выйти")
assertTrue(exit != null)
val exitIcon = (exit!!.getParent() as ViewGroup).getChildAt(0) as ImageView

assertNull(exitIcon!!.getColorFilter())
dialog!!.dismiss()
activity!!.finish()
}

@Test
fun phoneDarkModeUsesDarkSurfaceAndReadableText() {
val context = ApplicationProvider.getApplicationContext<Context>()
context!!.getSharedPreferences(AppSettings.PREFERENCES, Context.MODE_PRIVATE)
.edit().clear().commit()
ThemeSettings.setMode(context, ThemeMode.DARK)
val activity = Robolectric.buildActivity(MainActivity::class.java)
.create().get()
val root = (activity!!.findViewById(android.R.id.content) as ViewGroup).getChildAt(0)

assertEquals(Color.rgb(18, 18, 18),
(root!!.background as ColorDrawable).getColor())
val distance = findText(root, "—")
assertTrue(distance != null)
assertEquals(Color.rgb(238, 238, 238), distance!!.getCurrentTextColor())
activity!!.finish()
}

@Test @Throws(Exception::class)
fun phoneHudAppliesConfiguredThresholdColor() {
val context = ApplicationProvider.getApplicationContext<Context>()
context!!.getSharedPreferences(AppSettings.PREFERENCES, Context.MODE_PRIVATE)
.edit().clear().putInt(AppSettings.OVERSPEED_THRESHOLD, 10).commit()
val activity = Robolectric.buildActivity(MainActivity::class.java)
.create().get()
val root = (activity!!.findViewById(android.R.id.content) as ViewGroup).getChildAt(0)
val speed = findText(root, "0")
assertTrue(speed != null)
val receiverField = MainActivity::class.java!!.getDeclaredField("receiver")
receiverField!!.setAccessible(true)
val receiver = receiverField!!.get(activity) as BroadcastReceiver
val update = Intent(TrackingService.ACTION_UPDATE)
.putExtra(TrackingService.EXTRA_SPEED, 70f)
.putExtra(TrackingService.EXTRA_DISTANCE, 500)
.putExtra(TrackingService.EXTRA_CAMERA, "Камера")
.putExtra(TrackingService.EXTRA_CAMERA_ID, 42L)
.putExtra(TrackingService.EXTRA_ACTIVE_CAMERA_IDS, longArrayOf(42L))
.putExtra(TrackingService.EXTRA_LIMIT, 60)
.putExtra(TrackingService.EXTRA_ALERT_DISTANCE, 500)

receiver!!.onReceive(activity, update)

assertEquals("70", speed!!.getText().toString())
assertEquals(DrivingHudPresentation.COLOR_OVERSPEED, speed!!.getCurrentTextColor())
activity!!.finish()
}

@Test
fun carHudUsesConfiguredThresholdColor() {
val context = ApplicationProvider.getApplicationContext<Context>()
context!!.getSharedPreferences(AppSettings.PREFERENCES, Context.MODE_PRIVATE)
.edit().clear().putInt(AppSettings.OVERSPEED_THRESHOLD, 5).commit()
val presentation = CarMapPresentation(context, null, carContext())

presentation.onDrivingSnapshot(DrivingSnapshot(
64f, Float.NaN, 500, "Камера", 42L, 60, 800,
Double.NaN, Double.NaN, Float.NaN, "", "", longArrayOf(42L)))

val speed = findText(presentation.rootView(), "64")
assertTrue(speed != null)
assertEquals(DrivingHudPresentation.COLOR_ALERT, speed!!.getCurrentTextColor())
presentation.destroy()
}

@Test
fun phoneMenuShowsStoredTheme() {
val context = ApplicationProvider.getApplicationContext<Context>()
context!!.getSharedPreferences(AppSettings.PREFERENCES, Context.MODE_PRIVATE)
.edit().clear().commit()
ThemeSettings.setMode(context, ThemeMode.DARK)
val activity = Robolectric.buildActivity(MainActivity::class.java)
.create().get()
val root = (activity!!.findViewById(android.R.id.content) as ViewGroup).getChildAt(0)

val menu = findByDescription(root!!, "Меню")
assertTrue(menu != null)
menu!!.performClick()
val dialog = ShadowAlertDialog.getLatestAlertDialog()
assertTrue(dialog != null && dialog!!.isShowing())
assertTrue(findText(dialog!!.getWindow()!!.getDecorView(), "Тема: Тёмная") != null)
assertTrue(findText(dialog!!.getWindow()!!.getDecorView(), "Прозрачность зон: 85%") != null)
assertTrue(findText(dialog!!.getWindow()!!.getDecorView(), "Прозрачность активной зоны: 70%") != null)
assertTrue(findText(dialog!!.getWindow()!!.getDecorView(), "Отображение зон: Все") != null)
assertNull(findTextStartingWith(dialog!!.getWindow()!!.getDecorView(),
"Расстояние оповещения"))
dialog!!.dismiss()
activity!!.finish()
}

@Test
fun automaticThemeUsesRememberedLocationAfterRestart() {
val context = ApplicationProvider.getApplicationContext<Context>()
context!!.getSharedPreferences(AppSettings.PREFERENCES, Context.MODE_PRIVATE)
.edit().clear().commit()
ThemeSettings.setMode(context, ThemeMode.AUTOMATIC)
ThemeSettings.rememberLocation(context, 0.0, 0.0)
val noon = java.time.Instant.parse("2026-03-20T12:00:00Z").toEpochMilli()
val midnight = java.time.Instant.parse("2026-03-20T00:00:00Z").toEpochMilli()

assertFalse(ThemeSettings.isDark(context, noon))
assertTrue(ThemeSettings.isDark(context, midnight))
}

@Test
fun projectedServiceLoadsOfficialHostAllowlist() {
val service = Robolectric.buildService(GpsCarAppService::class.java)
.create().get()

val validator = service!!.createHostValidator()

assertFalse(validator!!.getAllowedHosts().isEmpty())
}

@Test
fun setupScreenExplainsMissingRequirements() {
val carContext = carContext()
val template = CarSetupScreen(
carContext, false, false).onGetTemplate() as MessageTemplate

assertEquals("Настройка GPS AntiRadar", template!!.getTitle().toString())
assertTrue(template!!.getMessage().toString().contains("геопозиции"))
assertTrue(template!!.getMessage().toString().contains("MapKit"))
assertEquals(Action.TYPE_BACK, template!!.getHeaderAction()!!.getType())
}

@Test
fun setupScreenExplainsSurfaceFailure() {
val carContext = carContext()
val template = CarSetupScreen(
carContext, true, true, "Не удалось создать поверхность карты.")
.onGetTemplate() as MessageTemplate

assertTrue(template!!.getMessage().toString().contains(
"Не удалось создать поверхность карты."))
}

@Test
fun mapScreenPublishesNavigationActionsAndRegistersSurface() {
val carContext = carContext()
val controller = CarSurfaceController(
carContext, null, { spec, surface-> NoOpSurfaceResource() })
val template = CarMapScreen(carContext, controller).onGetTemplate() as NavigationTemplate

assertFalse(template!!.getActionStrip()!!.getActions().isEmpty())
assertEquals(4, template!!.getMapActionStrip()!!.getActions().size)
assertSame(Action.PAN, template!!.getMapActionStrip()!!.getActions().get(0))
val appManager = carContext!!.getCarService(AppManager::class.java) as TestAppManager
assertSame(controller, appManager!!.getSurfaceCallback())
controller.destroy()
}

@Test
fun mapMenuActionPushesCarMenuScreen() {
val carContext = carContext()
val controller = CarSurfaceController(
carContext, null, { spec, surface-> NoOpSurfaceResource() })
val template = CarMapScreen(carContext, controller)
.onGetTemplate() as NavigationTemplate

assertTrue(template!!.getActionStrip()!!.getActions().get(0).getIcon() != null)
click(template!!.getActionStrip()!!.getActions().get(0))

val screenManager = carContext!!.getCarService(androidx.car.app.ScreenManager::class.java) as TestScreenManager
assertTrue(screenManager!!.getScreensPushed().get(0) is CarMenuScreen)
controller.destroy()
}

@Test
fun carValueSettingsUseSharedRangesAndRussianFormatting() {
assertSetting(CarValueScreen.Setting.OVERSPEED_THRESHOLD,
AppSettings.OVERSPEED_THRESHOLD, 0, 20, 1, "10 км/ч")
assertSetting(CarValueScreen.Setting.HUD_TRANSPARENCY,
AppSettings.HUD_TRANSPARENCY, 0, 80, 5, "10%")
assertSetting(CarValueScreen.Setting.ZONE_TRANSPARENCY,
AppSettings.ZONE_TRANSPARENCY, 10, 90, 5, "85%")
assertSetting(CarValueScreen.Setting.ACTIVE_ZONE_TRANSPARENCY,
AppSettings.ACTIVE_ZONE_TRANSPARENCY, 10, 90, 5, "70%")
}

@Test
fun carValueActionsPersistClampedValuesAndRefreshHud() {
val carContext = carContext()
val context = carContext
context!!.getSharedPreferences(AppSettings.PREFERENCES, Context.MODE_PRIVATE)
.edit().clear().commit()
val hudRefreshes = intArrayOf(0)
val screen = CarValueScreen(carContext,
CarValueScreen.Setting.HUD_TRANSPARENCY, { hudRefreshes!![0]++ })
val initial = screen.onGetTemplate() as PaneTemplate

assertEquals("Прозрачность HUD", initial!!.getTitle().toString())
assertEquals("10%", initial!!.getPane().getRows().get(0).getTitle().toString())
assertEquals(2, initial!!.getPane().getActions().size)
click(initial!!.getPane().getActions().get(0))
assertEquals(5, context!!.getSharedPreferences(
AppSettings.PREFERENCES, Context.MODE_PRIVATE).getInt(
AppSettings.HUD_TRANSPARENCY, -1))
assertEquals(1, hudRefreshes!![0])

context!!.getSharedPreferences(AppSettings.PREFERENCES, Context.MODE_PRIVATE)
.edit().putInt(AppSettings.HUD_TRANSPARENCY, 80).commit()
click((screen.onGetTemplate() as PaneTemplate).getPane().getActions().get(1))
assertEquals(80, context!!.getSharedPreferences(
AppSettings.PREFERENCES, Context.MODE_PRIVATE).getInt(
AppSettings.HUD_TRANSPARENCY, -1))
assertEquals(2, hudRefreshes!![0])
}

@Test
fun carMenuShowsThemeAndRoutesEverySettingsScreen() {
val carContext = carContext()
carContext!!.getSharedPreferences(AppSettings.PREFERENCES, Context.MODE_PRIVATE)
.edit().clear().commit()
val updates = FakeUpdateController()
val screen = CarMenuScreen(
carContext, null, updates, {  })
val template = screen.onGetTemplate() as ListTemplate
val items = template!!.getSingleList()!!.getItems()

assertEquals(Action.TYPE_BACK, template!!.getHeaderAction()!!.getType())
assertEquals(Arrays.asList(
"Обновить базу",
"Предел превышения скорости",
"Прозрачность HUD",
"Прозрачность зон",
"Прозрачность активной зоны",
"Отображение зон: Все",
"Автоповорот карты",
"Тема: Автоматически",
"Ключ MapKit",
"О программе",
"Выход"), rowTitles(items!!))

val screenManager = carContext!!.getCarService(androidx.car.app.ScreenManager::class.java) as TestScreenManager
val expectedTitles = arrayOf<String?>("Предел превышения скорости", "Прозрачность HUD",
"Прозрачность зон", "Прозрачность активной зоны")
for (index in 1..4)
{
screenManager!!.reset()
click(items!!.get(index) as Row)
val pushed = screenManager!!.getScreensPushed().get(0)
assertTrue(pushed is CarValueScreen)
assertEquals(expectedTitles!![index - 1],
(pushed!!.onGetTemplate() as PaneTemplate).getTitle().toString())
}
screenManager!!.reset()
click(items!!.get(5) as Row)
assertTrue(screenManager!!.getScreensPushed().get(0) is CarZoneDisplayScreen)
val autoRotate = items!!.get(6) as Row
assertFalse(autoRotate!!.getToggle()!!.isChecked())
autoRotate!!.getToggle()!!.getOnCheckedChangeDelegate().sendCheckedChange(
true, object:OnDoneCallback {

})
assertTrue(carContext!!.getSharedPreferences(
AppSettings.PREFERENCES, Context.MODE_PRIVATE).getBoolean(
AppSettings.AUTO_ROTATE_MAP, false))
screenManager!!.reset()
click(items!!.get(7) as Row)
assertTrue(screenManager!!.getScreensPushed().get(0) is CarThemeScreen)
screenManager!!.reset()
click(items!!.get(8) as Row)
assertTrue(screenManager!!.getScreensPushed().get(0) is CarMapKeyScreen)
screenManager!!.reset()
click(items!!.get(9) as Row)
assertTrue(screenManager!!.getScreensPushed().get(0) is CarAboutScreen)
}

@Test
fun carZoneDisplayScreenPersistsSelectionAndRefreshesSurface() {
val carContext = carContext()
carContext!!.getSharedPreferences(AppSettings.PREFERENCES, Context.MODE_PRIVATE)
.edit().clear().commit()
val refreshes = intArrayOf(0)
val screen = CarZoneDisplayScreen(carContext, { refreshes!![0]++ })

val initialItems = (screen.onGetTemplate() as ListTemplate)
.getSingleList()!!.getItems()
assertEquals(Arrays.asList("Все", "Только активная", "Не показывать"),
rowTitles(initialItems!!))
assertTrue((initialItems!!.get(0) as Row).getTexts().get(0).toString()
.contains("Выбрано"))

click(initialItems!!.get(1) as Row)

assertEquals("ACTIVE_ONLY", carContext!!.getSharedPreferences(
AppSettings.PREFERENCES, Context.MODE_PRIVATE).getString(
AppSettings.ZONE_DISPLAY_MODE, ""))
assertEquals(1, refreshes!![0])
}

@Test
fun carThemeScreenPersistsSelectionAndRefreshesSurface() {
val carContext = carContext()
carContext!!.getSharedPreferences(AppSettings.PREFERENCES, Context.MODE_PRIVATE)
.edit().clear().commit()
val refreshes = intArrayOf(0)
val screen = CarThemeScreen(carContext, { refreshes!![0]++ })

val initialItems = (screen.onGetTemplate() as ListTemplate)
.getSingleList()!!.getItems()
assertEquals(Arrays.asList("Светлая", "Тёмная", "Автоматически"),
rowTitles(initialItems!!))
assertTrue((initialItems!!.get(2) as Row).getTexts().get(0).toString()
.contains("Выбрано"))

click(initialItems!!.get(1) as Row)

assertEquals(ThemeMode.DARK, ThemeSettings.mode(carContext))
assertEquals(1, refreshes!![0])
val updatedItems = (screen.onGetTemplate() as ListTemplate)
.getSingleList()!!.getItems()
assertTrue((updatedItems!!.get(1) as Row).getTexts().get(0).toString()
.contains("Выбрано"))
}

@Test
fun carMenuRequestsSharedUpdateAndExplicitExit() {
val carContext = carContext()
val updates = FakeUpdateController()
val exits = intArrayOf(0)
val screen = CarMenuScreen(
carContext, null, updates, { exits!![0]++ })
val items = (screen.onGetTemplate() as ListTemplate)
.getSingleList()!!.getItems()

click(items!!.get(0) as Row)
assertEquals(1, updates.requestCount)

click(items!!.get(10) as Row)
assertEquals(1, exits!![0])
}

@Test
fun refreshVisibleReachesOnlyActiveSurfaceResource() {
val carContext = carContext()
val resources = ArrayList<RefreshRecordingSurfaceResource>()
val controller = CarSurfaceController(
carContext, null, { spec, surface->
val resource = RefreshRecordingSurfaceResource()
resources.add(resource)
resource })

controller.refreshVisible()
controller.onSurfaceAvailable(SurfaceContainer(null, 800, 480, 160))
controller.refreshVisible()
controller.onSurfaceAvailable(SurfaceContainer(null, 1280, 720, 240))
controller.refreshVisible()
controller.destroy()
controller.refreshVisible()

assertEquals(2, resources.size)
assertEquals(1, resources.get(0).refreshCount)
assertEquals(1, resources.get(1).refreshCount)
}

@Test
fun cameraStateSurvivesSurfaceRecreation() {
val carContext = carContext()
val expected = CarMapCameraState(55.75, 37.61, 16.5f, 42f, 12f, true, 3500L)
val resources = ArrayList<CameraStateSurfaceResource>()
val controller = CarSurfaceController(
carContext, null, { spec, surface->
val resource = CameraStateSurfaceResource(if (resources.isEmpty()) expected else null)
resources.add(resource)
resource })

controller.onSurfaceAvailable(SurfaceContainer(null, 800, 480, 160))
controller.onSurfaceAvailable(SurfaceContainer(null, 1280, 720, 240))

assertEquals(2, resources.size)
assertNull(resources.get(0).restoredState)
assertEquals(expected, resources.get(1).restoredState)
controller.destroy()
}

@Test
fun mapKeyScreenRejectsBlankAndPersistsTrimmedKey() {
val carContext = carContext()
val context = carContext
context!!.getSharedPreferences(AppSettings.PREFERENCES, Context.MODE_PRIVATE)
.edit().clear().commit()
val template = CarMapKeyScreen(carContext).onGetTemplate() as SearchTemplate

assertEquals(Action.TYPE_BACK, template!!.getHeaderAction()!!.getType())
assertEquals("Ключ Yandex MapKit", template!!.getSearchHint())
assertTrue(template!!.isShowKeyboardByDefault())
assertTrue((template!!.getItemList()!!.getItems().get(0) as Row)
.getTexts().get(0).toString().contains("телефоне"))

template!!.getSearchCallbackDelegate()!!.sendSearchSubmitted(
"   ", object:OnDoneCallback {

})
assertFalse(context!!.getSharedPreferences(
AppSettings.PREFERENCES, Context.MODE_PRIVATE)
.contains(AppSettings.MAPKIT_KEY))

template!!.getSearchCallbackDelegate()!!.sendSearchSubmitted(
"  test-map-key  ", object:OnDoneCallback {

})
assertEquals("test-map-key", context!!.getSharedPreferences(
AppSettings.PREFERENCES, Context.MODE_PRIVATE)
.getString(AppSettings.MAPKIT_KEY, ""))
val appManager = carContext!!.getCarService(AppManager::class.java) as TestAppManager
assertTrue(appManager!!.getToastsShown().get(0).toString().contains("пустым"))
assertTrue(appManager!!.getToastsShown().get(1).toString().contains("перезапустите"))
}

@Test
fun aboutScreenLoadsCountOnBackgroundAndShowsOnlyKnownHistory() {
val carContext = carContext()
carContext!!.getSharedPreferences(AppSettings.PREFERENCES, Context.MODE_PRIVATE)
.edit().putLong(AppSettings.RADARBASE_LAST_SUCCESSFUL_DOWNLOAD, 0L)
.commit()
val background = QueuedExecutor()
val main = QueuedExecutor()
val screen = CarAboutScreen(
carContext, { 12345 }, background, main)
val lifecycle = ScreenController(screen)

val loading = (screen.onGetTemplate() as LongMessageTemplate)
.getMessage().toString()
assertTrue(loading!!.contains("Объектов в базе: загрузка…"))
lifecycle.moveToState(Lifecycle.State.STARTED)
assertEquals(1, background.tasks.size)
background.runNext()
assertTrue((screen.onGetTemplate() as LongMessageTemplate)
.getMessage().toString().contains("загрузка…"))
main.runNext()

val loaded = screen.onGetTemplate() as LongMessageTemplate
val message = loaded!!.getMessage().toString()
assertEquals(Action.TYPE_BACK, loaded!!.getHeaderAction()!!.getType())
assertTrue(message!!.contains("Версия " + BuildConfig.VERSION_NAME))
assertTrue(message!!.contains(java.text.NumberFormat
.getIntegerInstance().format(12345)))
assertTrue(message!!.contains("Последняя успешная загрузка: не выполнялась"))
for (entry in ReleaseHistory.entries())
{
assertTrue(message!!.contains("Версия " + entry!!.version))
assertTrue(message!!.contains(entry!!.changes))
}
lifecycle.moveToState(Lifecycle.State.DESTROYED)
}

@Test
fun aboutScreenDropsAsyncCountAfterDestroy() {
val carContext = carContext()
val background = QueuedExecutor()
val main = QueuedExecutor()
val screen = CarAboutScreen(
carContext, { 77 }, background, main)
val lifecycle = ScreenController(screen)
lifecycle.moveToState(Lifecycle.State.STARTED)
background.runNext()
lifecycle.moveToState(Lifecycle.State.DESTROYED)
main.runNext()

assertTrue((screen.onGetTemplate() as LongMessageTemplate)
.getMessage().toString().contains("загрузка…"))
}

@Test
fun replacingSurfaceReleasesOldResourceBeforeCreatingNewOne() {
val carContext = carContext()
val events = ArrayList<String>()
val surfaces = RecordingSurfaceReleaser(events)
val oldSurface = TestSurface()
val replacement = TestSurface()
val controller = CarSurfaceController(
carContext, null, { spec, surface->
events.add("create:" + spec!!.width)
RecordingSurfaceResource(events) }, surfaces, { message-> fail(message) })

controller.onSurfaceAvailable(
SurfaceContainer(oldSurface.surface, 800, 480, 160))
controller.onSurfaceAvailable(
SurfaceContainer(replacement.surface, 1280, 720, 240))

assertEquals(Arrays.asList(
"create:800", "release", "surface:old", "create:1280"), events)
assertEquals(1, surfaces.releaseCount(oldSurface.surface))
assertEquals(0, surfaces.releaseCount(replacement.surface))
controller.destroy()
assertEquals(1, surfaces.releaseCount(replacement.surface))
oldSurface.close()
replacement.close()
}

@Test
fun destroyWithDifferentWrapperReleasesCurrentOrderedSurface() {
val carContext = carContext()
val events = ArrayList<String>()
val surfaces = RecordingSurfaceReleaser()
val testSurface = TestSurface()
val activeWrapper = testSurface.surface
val destroyWrapper = testSurface.newWrapper()
val controller = CarSurfaceController(
carContext, null,
{ spec, surface-> RecordingSurfaceResource(events) },
surfaces, { message-> fail(message) })

controller.onSurfaceAvailable(
SurfaceContainer(activeWrapper, 800, 480, 160))
controller.onSurfaceDestroyed(
SurfaceContainer(destroyWrapper, 800, 480, 160))
controller.destroy()
controller.destroy()

assertFalse(activeWrapper == destroyWrapper)
assertEquals(Arrays.asList("release"), events)
assertEquals(1, surfaces.releaseCount(activeWrapper))
assertEquals(1, surfaces.releaseCount(destroyWrapper))
testSurface.close()
}

@Test
fun staleDifferentSpecDestroyDoesNotReleaseReplacement() {
val carContext = carContext()
val events = ArrayList<String>()
val surfaces = RecordingSurfaceReleaser()
val oldSurface = TestSurface()
val replacement = TestSurface()
val controller = CarSurfaceController(
carContext, null, { spec, surface->
events.add("create:" + spec!!.width)
NamedSurfaceResource(events, spec!!.width) }, surfaces, { message-> fail(message) })
val oldContainer = SurfaceContainer(
oldSurface.surface, 800, 480, 160)

controller.onSurfaceAvailable(oldContainer)
controller.onSurfaceAvailable(SurfaceContainer(
replacement.surface, 1280, 720, 240))
controller.onSurfaceDestroyed(oldContainer)
controller.zoomBy(1f)
controller.destroy()

assertEquals(Arrays.asList(
"create:800", "release:800", "create:1280",
"zoom:1280", "release:1280"), events)
assertEquals(2, surfaces.releaseCount(oldSurface.surface))
assertEquals(1, surfaces.releaseCount(replacement.surface))
oldSurface.close()
replacement.close()
}

@Test
fun activeSurfaceDestroyReleasesResourceAndSurfaceOnce() {
val carContext = carContext()
val events = ArrayList<String>()
val surfaces = RecordingSurfaceReleaser()
val testSurface = TestSurface()
val controller = CarSurfaceController(
carContext, null,
{ spec, surface-> RecordingSurfaceResource(events) },
surfaces, { message-> fail(message) })
val container = SurfaceContainer(
testSurface.surface, 800, 480, 160)

controller.onSurfaceAvailable(container)
controller.onSurfaceDestroyed(container)

assertEquals(Arrays.asList("release"), events)
assertEquals(1, surfaces.releaseCount(testSurface.surface))
controller.destroy()
controller.destroy()
testSurface.close()
}

@Test
fun repeatedAvailabilityUsesFreshWrapperWithoutDoubleUse() {
val carContext = carContext()
val factorySurfaces = ArrayList<Surface>()
val surfaces = RecordingSurfaceReleaser()
val testSurface = TestSurface()
val firstWrapper = testSurface.surface
val secondWrapper = testSurface.newWrapper()
val controller = CarSurfaceController(
carContext, null, { spec, surface->
assertTrue("factory received a released Surface", surface!!.isValid())
factorySurfaces.add(surface)
NoOpSurfaceResource() }, surfaces, { message-> fail(message) })

controller.onSurfaceAvailable(
SurfaceContainer(firstWrapper, 800, 480, 160))
controller.onSurfaceAvailable(
SurfaceContainer(secondWrapper, 800, 480, 160))

assertFalse(firstWrapper == secondWrapper)
assertEquals(Arrays.asList(firstWrapper, secondWrapper), factorySurfaces)
assertEquals(1, surfaces.releaseCount(firstWrapper))
assertEquals(0, surfaces.releaseCount(secondWrapper))
controller.destroy()
assertEquals(1, surfaces.releaseCount(secondWrapper))
testSurface.close()
}

@Test
fun exactRepeatedSurfaceIsNotReleasedAndReused() {
val carContext = carContext()
val creates = intArrayOf(0)
val surfaces = RecordingSurfaceReleaser()
val testSurface = TestSurface()
val controller = CarSurfaceController(
carContext, null, { spec, surface->
assertTrue("factory received a released Surface", surface!!.isValid())
creates!![0]++
NoOpSurfaceResource() }, surfaces, { message-> fail(message) })
val container = SurfaceContainer(
testSurface.surface, 800, 480, 160)

controller.onSurfaceAvailable(container)
controller.onSurfaceAvailable(container)

assertEquals(1, creates!![0])
assertEquals(0, surfaces.releaseCount(testSurface.surface))
controller.destroy()
assertEquals(1, surfaces.releaseCount(testSurface.surface))
testSurface.close()
}

@Test
fun exactSurfaceWithChangedSpecRecreatesBeforeRelease() {
val carContext = carContext()
val events = ArrayList<String>()
val surfaces = RecordingSurfaceReleaser()
val testSurface = TestSurface()
val controller = CarSurfaceController(
carContext, null, { spec, surface->
assertTrue("factory received a released Surface", surface!!.isValid())
events.add(("create:" + spec!!.width + "x" + spec!!.height
+ "@" + spec!!.dpi))
NamedSurfaceResource(events, spec!!.width) }, surfaces, { message-> fail(message) })

controller.onSurfaceAvailable(SurfaceContainer(
testSurface.surface, 800, 480, 160))
controller.onSurfaceAvailable(SurfaceContainer(
testSurface.surface, 1280, 720, 240))

assertEquals(Arrays.asList(
"create:800x480@160", "release:800",
"create:1280x720@240"), events)
assertEquals(0, surfaces.releaseCount(testSurface.surface))
controller.zoomBy(1f)
controller.destroy()
assertEquals(Arrays.asList(
"create:800x480@160", "release:800",
"create:1280x720@240", "zoom:1280", "release:1280"), events)
assertEquals(1, surfaces.releaseCount(testSurface.surface))
testSurface.close()
}

@Test
fun exactSurfaceWithUnusableSpecReleasesResourceAndSurface() {
val carContext = carContext()
val events = ArrayList<String>()
val surfaces = RecordingSurfaceReleaser()
val testSurface = TestSurface()
val controller = CarSurfaceController(
carContext, null, { spec, surface->
events.add("create:" + spec!!.width)
NamedSurfaceResource(events, spec!!.width) }, surfaces, { message-> fail(message) })

controller.onSurfaceAvailable(SurfaceContainer(
testSurface.surface, 800, 480, 160))
controller.onSurfaceAvailable(SurfaceContainer(
testSurface.surface, 0, 480, 160))
controller.zoomBy(1f)

assertEquals(Arrays.asList("create:800", "release:800"), events)
assertEquals(1, surfaces.releaseCount(testSurface.surface))
controller.destroy()
assertEquals(1, surfaces.releaseCount(testSurface.surface))
testSurface.close()
}

@Test
fun exactSurfaceChangedSpecFailureReleasesSurfaceAndReports() {
val carContext = carContext()
val events = ArrayList<String>()
val failures = ArrayList<String>()
val surfaces = RecordingSurfaceReleaser()
val testSurface = TestSurface()
val controller = CarSurfaceController(
carContext, null, { spec, surface->
assertTrue("factory received a released Surface", surface!!.isValid())
events.add("create:" + spec!!.width)
if (spec!!.width == 1280)
{
throw IllegalStateException("replacement failed")
}
NamedSurfaceResource(events, spec!!.width) }, surfaces, CarSurfaceController.FailureListener { failures.add(it) })

controller.onSurfaceAvailable(SurfaceContainer(
testSurface.surface, 800, 480, 160))
controller.onSurfaceAvailable(SurfaceContainer(
testSurface.surface, 1280, 720, 240))

assertEquals(Arrays.asList(
"create:800", "release:800", "create:1280"), events)
assertEquals(1, surfaces.releaseCount(testSurface.surface))
assertEquals(1, failures.size)
controller.destroy()
assertEquals(1, surfaces.releaseCount(testSurface.surface))
testSurface.close()
}

@Test
fun unusableSurfaceIsReleasedWithoutCallingFactory() {
val carContext = carContext()
val creates = intArrayOf(0)
val surfaces = RecordingSurfaceReleaser()
val testSurface = TestSurface()
val controller = CarSurfaceController(
carContext, null, { spec, surface->
creates!![0]++
NoOpSurfaceResource() }, surfaces, { message-> fail(message) })

controller.onSurfaceAvailable(
SurfaceContainer(testSurface.surface, 0, 480, 160))

assertEquals(0, creates!![0])
assertEquals(1, surfaces.releaseCount(testSurface.surface))
controller.destroy()
testSurface.close()
}

@Test
fun factoryFailureIsContainedAndReleasesSurface() {
val carContext = carContext()
val surfaces = RecordingSurfaceReleaser()
val failures = ArrayList<String>()
val testSurface = TestSurface()
val controller = CarSurfaceController(
carContext, null, { spec, surface-> throw IllegalStateException("factory failed") }, surfaces, CarSurfaceController.FailureListener { failures.add(it) })

try
{
controller.onSurfaceAvailable(
SurfaceContainer(testSurface.surface, 800, 480, 160))
}
catch (error:RuntimeException) {
fail("Surface failure escaped to the host: " + error!!)
}

assertEquals(1, surfaces.releaseCount(testSurface.surface))
assertEquals(1, failures.size)
assertTrue(failures.get(0).contains("Не удалось отобразить карту"))
controller.destroy()
testSurface.close()
}

@Test
fun initializationFailureReleasesPartialResourceAndSurface() {
val carContext = carContext()
val surfaces = RecordingSurfaceReleaser()
val events = ArrayList<String>()
val failures = ArrayList<String>()
val testSurface = TestSurface()
val controller = CarSurfaceController(
carContext, null,
{ spec, surface-> FailingInitializationResource(events) },
surfaces, CarSurfaceController.FailureListener { failures.add(it) })

controller.onSurfaceAvailable(
SurfaceContainer(testSurface.surface, 800, 480, 160))

assertEquals(Arrays.asList("configure", "release"), events)
assertEquals(1, surfaces.releaseCount(testSurface.surface))
assertEquals(1, failures.size)
controller.destroy()
testSurface.close()
}

@Test
fun emptyAreasClearActiveGenerationAndAreNotReplayed() {
val carContext = carContext()
val resources = ArrayList<AreaRecordingSurfaceResource>()
val controller = CarSurfaceController(
carContext, null, { spec, surface->
val resource = AreaRecordingSurfaceResource()
resources.add(resource)
resource })

controller.onStableAreaChanged(Rect())
controller.onVisibleAreaChanged(Rect())
controller.onSurfaceAvailable(SurfaceContainer(null, 800, 480, 160))
controller.onStableAreaChanged(Rect(10, 10, 790, 470))
controller.onVisibleAreaChanged(Rect(20, 20, 780, 460))
controller.onStableAreaChanged(Rect())
controller.onVisibleAreaChanged(Rect())
controller.onSurfaceAvailable(SurfaceContainer(null, 1280, 720, 240))

assertEquals(2, resources.size)
assertEquals(Arrays.asList(Rect(10, 10, 790, 470), null),
resources.get(0).stableAreas)
assertEquals(Arrays.asList(Rect(20, 20, 780, 460), null),
resources.get(0).visibleAreas)
assertTrue(resources.get(1).stableAreas.isEmpty())
assertTrue(resources.get(1).visibleAreas.isEmpty())
controller.destroy()
}

@Test
fun validAreasAreNotReplayedToReplacementGeneration() {
val carContext = carContext()
val resources = ArrayList<AreaRecordingSurfaceResource>()
val controller = CarSurfaceController(
carContext, null, { spec, surface->
val resource = AreaRecordingSurfaceResource()
resources.add(resource)
resource })

controller.onSurfaceAvailable(SurfaceContainer(null, 800, 480, 160))
controller.onStableAreaChanged(Rect(10, 10, 790, 470))
controller.onVisibleAreaChanged(Rect(20, 20, 780, 460))
controller.onSurfaceAvailable(SurfaceContainer(null, 1280, 720, 240))

assertEquals(2, resources.size)
assertEquals(Arrays.asList(Rect(10, 10, 790, 470)),
resources.get(0).stableAreas)
assertEquals(Arrays.asList(Rect(20, 20, 780, 460)),
resources.get(0).visibleAreas)
assertTrue(resources.get(1).stableAreas.isEmpty())
assertTrue(resources.get(1).visibleAreas.isEmpty())
controller.destroy()
}

@Test
fun emptyStableAreaRestoresDefaultHudLayout() {
val context = ApplicationProvider.getApplicationContext<Context>()
val presentation = CarMapPresentation(context, null, carContext())
val root = presentation.rootView() as FrameLayout
val hud = root!!.getChildAt(0)
val defaults = hud!!.getLayoutParams() as FrameLayout.LayoutParams
val defaultLeft = defaults!!.leftMargin
val defaultBottom = defaults!!.bottomMargin
val defaultWidth = defaults!!.width

presentation.onStableAreaChanged(Rect(100, 20, 700, 400))
assertTrue(((hud!!.getLayoutParams() as FrameLayout.LayoutParams).leftMargin > defaultLeft))
presentation.onStableAreaChanged(Rect())

val reset = hud!!.getLayoutParams() as FrameLayout.LayoutParams
assertEquals(defaultLeft, reset!!.leftMargin)
assertEquals(defaultBottom, reset!!.bottomMargin)
assertEquals(defaultWidth, reset!!.width)
presentation.destroy()
}

@Test
fun carHudDoesNotShowAlertAlgorithmDiagnostics() {
val context = ApplicationProvider.getApplicationContext<Context>()
val presentation = CarMapPresentation(context, null, carContext())
presentation.onDrivingSnapshot(DrivingSnapshot(
0f, Float.NaN, -1, "", -1L, 0, 0,
Double.NaN, Double.NaN, Float.NaN,
"Поиск впереди: 1600 м", ""))

assertNull(findText(presentation.rootView(), "Поиск впереди: 1600 м"))
presentation.destroy()
}

private fun carContext():CarContext {
val context = ApplicationProvider.getApplicationContext<Context>()
return TestCarContext.createCarContext(context)
}

private fun assertSetting(setting:CarValueScreen.Setting,
key:String?, min:Int, max:Int, step:Int, formattedDefault:String?) {
assertEquals(key, setting.preferenceKey())
assertEquals(min, setting.minValue())
assertEquals(max, setting.maxValue())
assertEquals(step, setting.step())
assertEquals(formattedDefault, setting.format(setting.defaultValue()))
}

private fun click(action:Action) {
action.getOnClickDelegate()!!.sendClick(object:OnDoneCallback {

})
}

private fun findText(view:View?, text:String?):TextView? {
if (view is TextView && text!!.contentEquals((view as TextView).getText()))
{
return view as TextView?
}
if (view is ViewGroup)
{
val group = view as ViewGroup?
for (index in 0 until group!!.getChildCount())
{
val found = findText(group!!.getChildAt(index), text)
if (found != null) return found
}
}
return null
}

private fun findTextStartingWith(view:View?, prefix:String):TextView? {
if ((view is TextView && (view as TextView).getText().toString().startsWith(prefix)))
{
return view as TextView?
}
if (view is ViewGroup)
{
val group = view as ViewGroup?
for (index in 0 until group!!.getChildCount())
{
val found = findTextStartingWith(group!!.getChildAt(index), prefix)
if (found != null) return found
}
}
return null
}

private fun findByDescription(view:View, description:String?):View? {
if ((view.getContentDescription() != null && description!!.contentEquals(view.getContentDescription())))
{
return view
}
if (view is ViewGroup)
{
val group = view as ViewGroup
for (index in 0 until group!!.getChildCount())
{
val found = findByDescription(group!!.getChildAt(index), description)
if (found != null) return found
}
}
return null
}

private fun click(row:Row) {
row.getOnClickDelegate()!!.sendClick(object:OnDoneCallback {

})
}

private fun rowTitles(items:List<Item?>):List<String?> {
val titles = ArrayList<String>()
for (item in items)
{
titles.add((item as Row).getTitle().toString())
}
return titles
}

private class FakeUpdateController:CarMenuScreen.UpdateController {
 var requestCount:Int = 0

override fun requestUpdate() {
requestCount++
}
}

private class QueuedExecutor:Executor {
 val tasks:MutableList<Runnable> = ArrayList()

override fun execute(command:Runnable) {
tasks.add(command)
}

fun runNext() {
tasks.removeAt(0).run()
}
}

private open class NoOpSurfaceResource:CarSurfaceController.SurfaceResource {
override fun onDrivingSnapshot(snapshot:DrivingSnapshot) {}
override fun refreshVisible() {}
override fun zoomBy(delta:Float) {}
override fun recenter() {}
override fun setPanMode(enabled:Boolean) {}
override fun onStableAreaChanged(area:android.graphics.Rect?) {}
override fun onVisibleAreaChanged(area:android.graphics.Rect?) {}
override fun onCarConfigurationChanged() {}
override fun onScroll(distanceX:Float, distanceY:Float) {}
override fun onFling(velocityX:Float, velocityY:Float) {}
override fun onScale(focusX:Float, focusY:Float, scaleFactor:Float) {}
override fun onClick(x:Float, y:Float) {}
override fun release() {}
}

private class RefreshRecordingSurfaceResource:NoOpSurfaceResource() {
 var refreshCount:Int = 0

override fun refreshVisible() {
refreshCount++
}
}

private class CameraStateSurfaceResource(
private val currentState:CarMapCameraState?):NoOpSurfaceResource() {
 var restoredState:CarMapCameraState? = null

override fun cameraState():CarMapCameraState? = currentState

override fun restoreCameraState(state:CarMapCameraState?) {
restoredState = state
}
}

private class RecordingSurfaceResource(private val events:MutableList<String>?):NoOpSurfaceResource() {

override fun release() {
events!!.add("release")
}
}

private class NamedSurfaceResource(private val events:MutableList<String>?, private val width:Int):NoOpSurfaceResource() {

override fun zoomBy(delta:Float) {
events!!.add("zoom:" + width)
}

override fun release() {
events!!.add("release:" + width)
}
}

private class FailingInitializationResource(private val events:MutableList<String>?):NoOpSurfaceResource() {

override fun onCarConfigurationChanged() {
events!!.add("configure")
throw IllegalStateException("configuration failed")
}

override fun release() {
events!!.add("release")
}
}

private class AreaRecordingSurfaceResource:NoOpSurfaceResource() {
 val stableAreas:MutableList<Rect?> = ArrayList()
 val visibleAreas:MutableList<Rect?> = ArrayList()

override fun onStableAreaChanged(area:Rect?) {
stableAreas.add(if (area == null) null else Rect(area))
}

override fun onVisibleAreaChanged(area:Rect?) {
visibleAreas.add(if (area == null) null else Rect(area))
}
}

private class RecordingSurfaceReleaser constructor(private val events:MutableList<String>? = null):CarSurfaceController.SurfaceReleaser {
private val releasedSurfaces = ArrayList<Surface>()
private var firstSurface:Surface? = null

override fun release(surface:Surface) {
if (firstSurface == null) firstSurface = surface
releasedSurfaces.add(surface)
if (events != null)
{
events!!.add(if (surface == firstSurface) "surface:old" else "surface:new")
}
surface!!.release()
}

 fun releaseCount(surface:Surface?):Int {
var count = 0
for (released in releasedSurfaces)
{
if (released == surface) count++
}
return count
}
}

private class TestSurface {
 val texture = SurfaceTexture(0)
 val wrappers:MutableList<Surface> = ArrayList()
 val surface = newWrapper()

 fun newWrapper():Surface {
val wrapper = Surface(texture)
wrappers.add(wrapper)
return wrapper
}

fun close() {
for (wrapper in wrappers) wrapper!!.release()
texture.release()
}
}
}
