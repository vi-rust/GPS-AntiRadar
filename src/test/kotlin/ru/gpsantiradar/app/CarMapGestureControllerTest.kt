package ru.gpsantiradar.app

import com.yandex.mapkit.ScreenPoint
import com.yandex.mapkit.geometry.Point

import org.junit.Test

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse

 class CarMapGestureControllerTest {
@Test
fun panOffsetStartsAtDisplayedCameraTarget() {
val translated = CarMapGestureController.translatedCameraTarget(
ScreenPoint(280f, 160f), 36f, -18f)

assertEquals(316f, translated!!.getX(), 0f)
assertEquals(142f, translated!!.getY(), 0f)
}

@Test
fun touchDragPansWithoutHostPanMode() {
val target = RecordingGestureTarget()
val controller = CarMapGestureController(target, null)

controller.onScroll(36f, -18f)

assertEquals(1, target.panCount)
assertEquals(36f, target.panX, 0f)
assertEquals(-18f, target.panY, 0f)
assertFalse(target.panAnimated)
}

@Test
fun consecutivePinchStepsUseImmediateZoom() {
val target = RecordingGestureTarget()
val controller = CarMapGestureController(target, null)

controller.onScale(120f, 80f, 2f)
controller.onScale(120f, 80f, 0.5f)

assertEquals(2, target.zoomCount)
assertEquals(0f, target.totalZoomDelta, 0.0001f)
assertFalse(target.zoomAnimated)
}

private class RecordingGestureTarget:CarMapGestureController.GestureTarget {
 var panCount:Int = 0
 var panX:Float = 0.toFloat()
 var panY:Float = 0.toFloat()
 var panAnimated:Boolean = false
 var zoomCount:Int = 0
 var totalZoomDelta:Float = 0.toFloat()
 var zoomAnimated:Boolean = false

override fun zoomBy(delta:Float, animated:Boolean) {
zoomCount++
totalZoomDelta += delta
zoomAnimated = animated
}

override fun recenter() {}

override fun pauseFollowing() {}

override fun panBy(offsetX:Float, offsetY:Float, animated:Boolean) {
panCount++
panX += offsetX
panY += offsetY
panAnimated = animated
}

override fun pointAt(x:Float, y:Float):Point? {
return null
}
}
}
