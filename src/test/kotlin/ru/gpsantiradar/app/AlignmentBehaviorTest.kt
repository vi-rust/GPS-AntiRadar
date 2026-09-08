package ru.gpsantiradar.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.view.View
import android.widget.TextView

import androidx.car.app.CarContext
import androidx.car.app.testing.TestCarContext
import androidx.car.app.model.MessageTemplate
import androidx.test.core.app.ApplicationProvider

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric

import java.lang.reflect.Method

@RunWith(RobolectricTestRunner::class)
 class AlignmentBehaviorTest {
@Test
fun missingMapKitSetupKeepsRadarActiveAndOffersKeyEntry() {
val context = ApplicationProvider.getApplicationContext<Context>()
val template = CarSetupScreen(
carContext(context), true, false, true, null).onGetTemplate() as MessageTemplate

assertTrue(template!!.getMessage().toString().contains(
"Антирадар и звуковые предупреждения работают без карты"))
assertEquals(1, template!!.getActions().size)
assertEquals("Ввести ключ MapKit",
template!!.getActions().get(0).getTitle().toString())
}

@Test
fun carHudKeepsEmptyDatabaseStatusAcrossDrivingSnapshots() {
val context = ApplicationProvider.getApplicationContext<Context>()
val presentation = CarMapPresentation(
context, null, carContext(context))

presentation.onDatabaseCount(0)
presentation.onDrivingSnapshot(DrivingSnapshot(
50f, Float.NaN, -1, "", -1L, 0, 0,
55.0, 37.0, 0f, "", ""))

assertTrue((findText(presentation.rootView(),
"База объектов пуста — обновите RadarBase") != null))
presentation.destroy()
}

@Test
fun stoppedTrackingClearsTheLastCarSnapshot() {
val context = ApplicationProvider.getApplicationContext<Context>()
val presentation = CarMapPresentation(
context, null, carContext(context))
presentation.onDrivingSnapshot(DrivingSnapshot(
70f, Float.NaN, 400, "Камера", 42L, 60, 800,
55.0, 37.0, 0f, "", ""))

presentation.onTrackingStopped()

assertTrue(findText(presentation.rootView(), "Антирадар остановлен") != null)
assertTrue(findText(presentation.rootView(), "0") != null)
presentation.destroy()
}

@Test
fun surfaceRecenterReportsMissingAndAvailableLocation() {
val context = ApplicationProvider.getApplicationContext<Context>()
val carContext = carContext(context)
val resource = RecordingSurfaceResource()
val controller = CarSurfaceController(
carContext, null, { spec, surface-> resource })

assertFalse(controller.recenter())
controller.onDatabaseCount(0)
controller.onTrackingStopped()
controller.onSurfaceAvailable(androidx.car.app.SurfaceContainer(null, 800, 480, 160))
assertEquals(1, resource.databaseCountUpdates)
assertEquals(1, resource.stoppedUpdates)
assertFalse(controller.recenter())
controller.onDrivingSnapshot(DrivingSnapshot(
10f, Float.NaN, -1, "", -1L, 0, 0,
55.0, 37.0, 0f, "", ""))
assertTrue(controller.recenter())
assertEquals(1, resource.recenterCount)
controller.destroy()
}

@Test
fun projectedCameraTapIsConsumedBeforeBackgroundClick() {
val target = RecordingGestureTarget()
target.cameraTap = true
val backgroundClicks = intArrayOf(0)
val controller = CarMapGestureController(
target, { point-> backgroundClicks!![0]++ })

controller.onClick(120f, 80f)

assertEquals(1, target.cameraHitTests)
assertEquals(0, backgroundClicks!![0])
}

@Test
fun projectedEmptyTapReachesBackgroundClick() {
val target = RecordingGestureTarget()
val backgroundClicks = intArrayOf(0)
val controller = CarMapGestureController(
target, { point-> backgroundClicks!![0]++ })

controller.onClick(120f, 80f)

assertEquals(1, target.cameraHitTests)
assertEquals(1, backgroundClicks!![0])
}

@Test @Throws(Exception::class)
fun carHudReactsToThresholdChangedByAnotherSurface() {
val context = ApplicationProvider.getApplicationContext<Context>()
context!!.getSharedPreferences(AppSettings.PREFERENCES, Context.MODE_PRIVATE)
.edit().clear().putInt(AppSettings.OVERSPEED_THRESHOLD, 10).commit()
val presentation = CarMapPresentation(
context, null, carContext(context))
val register = CarMapPresentation::class.java!!.getDeclaredMethod(
"registerSettingsListener")
register!!.setAccessible(true)
register!!.invoke(presentation)
presentation.onDrivingSnapshot(DrivingSnapshot(
70f, Float.NaN, 400, "Камера", 42L, 60, 800,
55.0, 37.0, 0f, "", ""))
val speed = findText(presentation.rootView(), "70")
assertEquals(DrivingHudPresentation.COLOR_OVERSPEED,
speed!!.getCurrentTextColor())

context!!.getSharedPreferences(AppSettings.PREFERENCES, Context.MODE_PRIVATE)
.edit().putInt(AppSettings.OVERSPEED_THRESHOLD, 20).commit()
org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

assertEquals(DrivingHudPresentation.COLOR_ALERT, speed!!.getCurrentTextColor())
presentation.destroy()
}

@Test @Throws(Exception::class)
fun phoneHudReactsToThresholdChangedByAnotherSurface() {
val context = ApplicationProvider.getApplicationContext<Context>()
context!!.getSharedPreferences(AppSettings.PREFERENCES, Context.MODE_PRIVATE)
.edit().clear().putInt(AppSettings.OVERSPEED_THRESHOLD, 10).commit()
val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
val activity = controller!!.get()
val receiverField = MainActivity::class.java!!.getDeclaredField("receiver")
receiverField!!.setAccessible(true)
val receiver = receiverField!!.get(activity) as BroadcastReceiver
receiver!!.onReceive(activity, Intent(TrackingService.ACTION_UPDATE)
.putExtra(TrackingService.EXTRA_SPEED, 70f)
.putExtra(TrackingService.EXTRA_DISTANCE, 400)
.putExtra(TrackingService.EXTRA_CAMERA, "Камера")
.putExtra(TrackingService.EXTRA_CAMERA_ID, 42L)
.putExtra(TrackingService.EXTRA_LIMIT, 60)
.putExtra(TrackingService.EXTRA_ALERT_DISTANCE, 800))
val speed = findText(activity!!.findViewById(android.R.id.content), "70")
assertEquals(DrivingHudPresentation.COLOR_OVERSPEED,
speed!!.getCurrentTextColor())

context!!.getSharedPreferences(AppSettings.PREFERENCES, Context.MODE_PRIVATE)
.edit().putInt(AppSettings.OVERSPEED_THRESHOLD, 20).commit()
org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

assertEquals(DrivingHudPresentation.COLOR_ALERT, speed!!.getCurrentTextColor())
controller!!.pause().stop().destroy()
}

private fun carContext(context:Context):CarContext {
return TestCarContext.createCarContext(context)
}

private fun findText(view:View?, text:String?):TextView? {
if (view is TextView && text!!.contentEquals((view as TextView).getText()))
{
return view as TextView?
}
if (view is android.view.ViewGroup)
{
val group = view as android.view.ViewGroup?
for (index in 0 until group!!.getChildCount())
{
val found = findText(group!!.getChildAt(index), text)
if (found != null) return found
}
}
return null
}

private class RecordingSurfaceResource:CarSurfaceController.SurfaceResource {
 var recenterCount:Int = 0
 var databaseCountUpdates:Int = 0
 var stoppedUpdates:Int = 0

override fun onDrivingSnapshot(snapshot:DrivingSnapshot) {}
override fun onDatabaseCount(count:Int) {
databaseCountUpdates++
}
override fun onTrackingStopped() {
stoppedUpdates++
}
override fun refreshVisible() {}
override fun zoomBy(delta:Float) {}
override fun recenter() {
recenterCount++
}
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

private class RecordingGestureTarget:CarMapGestureController.GestureTarget {
 var cameraTap:Boolean = false
 var cameraHitTests:Int = 0

override fun zoomBy(delta:Float, animated:Boolean) {}
override fun recenter() {}
override fun pauseFollowing() {}
override fun panBy(offsetX:Float, offsetY:Float, animated:Boolean) {}
override fun pointAt(x:Float, y:Float):com.yandex.mapkit.geometry.Point {
return com.yandex.mapkit.geometry.Point(55.0, 37.0)
}
override fun tapCameraAt(x:Float, y:Float):Boolean {
cameraHitTests++
return cameraTap
}
}
}
