package ru.gpsantiradar.app

import com.yandex.mapkit.Animation
import com.yandex.mapkit.ScreenPoint
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.map.CameraPosition
import com.yandex.mapkit.map.MapWindow
import kotlin.math.abs
import kotlin.math.ln

class CarMapGestureController internal constructor(
    private val target: GestureTarget,
    private val clickListener: ClickListener?,
) {
    fun interface ClickListener {
        fun onMapClick(point: Point)
    }

    interface GestureTarget {
        fun zoomBy(delta: Float, animated: Boolean)
        fun recenter()
        fun pauseFollowing()
        fun panBy(offsetX: Float, offsetY: Float, animated: Boolean)
        fun pointAt(x: Float, y: Float): Point?
        fun tapCameraAt(x: Float, y: Float): Boolean = false
    }

    constructor(
        mapWindow: MapWindow,
        mapLayer: SharedCameraMapLayer,
        clickListener: ClickListener?,
    ) : this(MapKitGestureTarget(mapWindow, mapLayer), clickListener)

    fun zoomBy(delta: Float) {
        target.zoomBy(delta, true)
    }

    fun recenter() {
        target.recenter()
    }

    fun setPanMode(enabled: Boolean) {
        if (enabled) target.pauseFollowing()
    }

    fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        if (!scaleFactor.isFinite() || scaleFactor <= 0f) return
        val delta = (ln(scaleFactor.toDouble()) / ln(2.0)).toFloat()
        if (delta.isFinite() && abs(delta) > 0.001f) {
            target.zoomBy(delta, false)
        }
    }

    fun onScroll(distanceX: Float, distanceY: Float) {
        moveCenterToScreenOffset(distanceX, distanceY, false)
    }

    fun onFling(velocityX: Float, velocityY: Float) {
        moveCenterToScreenOffset(
            -velocityX * FLING_SECONDS,
            -velocityY * FLING_SECONDS,
            true,
        )
    }

    fun onClick(x: Float, y: Float) {
        if (!x.isFinite() || !y.isFinite()) return
        if (target.tapCameraAt(x, y)) return
        val point = target.pointAt(x, y)
        if (point != null) clickListener?.onMapClick(point)
    }

    private fun moveCenterToScreenOffset(offsetX: Float, offsetY: Float, animated: Boolean) {
        target.panBy(offsetX, offsetY, animated)
    }

    private class MapKitGestureTarget(
        private val mapWindow: MapWindow,
        private val mapLayer: SharedCameraMapLayer,
    ) : GestureTarget {
        override fun zoomBy(delta: Float, animated: Boolean) {
            if (animated) mapLayer.zoomBy(delta) else mapLayer.zoomByImmediately(delta)
        }

        override fun recenter() {
            mapLayer.moveToCurrentLocation()
        }

        override fun pauseFollowing() {
            mapLayer.pauseFollowing()
        }

        override fun pointAt(x: Float, y: Float): Point? =
            mapWindow.screenToWorld(ScreenPoint(x, y))

        override fun tapCameraAt(x: Float, y: Float): Boolean = mapLayer.tapCameraAt(x, y)

        override fun panBy(offsetX: Float, offsetY: Float, animated: Boolean) {
            if (!offsetX.isFinite() || !offsetY.isFinite()) return
            val map = mapWindow.map
            if (!map.isValid) return
            val current = map.cameraPosition
            val displayedTarget = mapWindow.worldToScreen(current.target)
            val translatedTarget = translatedCameraTarget(displayedTarget, offsetX, offsetY) ?: return
            val target = mapWindow.screenToWorld(translatedTarget) ?: return
            mapLayer.pauseFollowing()
            val next = CameraPosition(target, current.zoom, current.azimuth, current.tilt)
            if (!animated) {
                map.move(next)
            } else {
                map.move(next, Animation(Animation.Type.SMOOTH, 0.28f))
            }
        }
    }

    companion object {
        private const val FLING_SECONDS = 0.12f

        fun translatedCameraTarget(
            displayedTarget: ScreenPoint?,
            offsetX: Float,
            offsetY: Float,
        ): ScreenPoint? = displayedTarget?.let {
            ScreenPoint(it.x + offsetX, it.y + offsetY)
        }
    }
}
