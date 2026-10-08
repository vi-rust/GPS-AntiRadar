package ru.gpsantiradar.app

import android.graphics.Color
import com.yandex.mapkit.Animation
import com.yandex.mapkit.ScreenPoint
import com.yandex.mapkit.ScreenRect
import com.yandex.mapkit.geometry.Circle
import com.yandex.mapkit.geometry.Geometry
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.geometry.Polyline
import com.yandex.mapkit.map.Map
import com.yandex.mapkit.map.MapObjectCollection
import com.yandex.mapkit.map.MapWindow
import com.yandex.mapkit.map.PolylineMapObject
import com.yandex.mapkit.navigation.JamType

/** Route objects owned by one MapWindow. */
class RouteMapLayer(mapWindow: MapWindow) {
    private var map: Map? = mapWindow.map
    private var window: MapWindow? = mapWindow
    private var collection: MapObjectCollection? = mapWindow.map.mapObjects.addCollection()
    private var routePolyline: Polyline? = null
    private var routePoints: List<Point> = emptyList()

    fun render(snapshot: RouteSnapshot) {
        val activeCollection = collection ?: return
        activeCollection.clear()
        routePolyline = null
        routePoints = emptyList()
        if (snapshot.status != RouteStatus.READY || snapshot.points.size < 2) return

        val polyline = Polyline(snapshot.points)
        routePolyline = polyline
        routePoints = snapshot.points
        val routeObject = activeCollection.addPolyline(polyline).apply {
            strokeWidth = 7f
            outlineColor = Color.WHITE
            outlineWidth = 2f
            zIndex = 20f
        }
        applyTrafficColors(routeObject, snapshot.jamTypes, snapshot.points.size - 1)
        snapshot.destination?.let { destination ->
            activeCollection.addCircle(Circle(destination, 24f)).apply {
                fillColor = DESTINATION_COLOR
                strokeColor = Color.WHITE
                strokeWidth = 3f
                zIndex = 21f
            }
        }
    }

    fun isPointNearRoute(point: Point, tolerancePixels: Float): Boolean {
        val activeWindow = window ?: return false
        if (routePoints.size < 2 || tolerancePixels <= 0f) return false
        val target = activeWindow.worldToScreen(point) ?: return false
        var previous = activeWindow.worldToScreen(routePoints.first()) ?: return false
        val toleranceSquared = tolerancePixels * tolerancePixels
        for (index in 1 until routePoints.size) {
            val current = activeWindow.worldToScreen(routePoints[index]) ?: continue
            if (
                RouteHitTest.distanceSquared(
                    target.x,
                    target.y,
                    previous.x,
                    previous.y,
                    current.x,
                    current.y,
                ) <= toleranceSquared
            ) {
                return true
            }
            previous = current
        }
        return false
    }

    fun fitRoute() {
        val activeMap = map ?: return
        val activeWindow = window ?: return
        val polyline = routePolyline ?: return
        val width = activeWindow.width()
        val height = activeWindow.height()
        if (width <= 0 || height <= 0) return
        val horizontalPadding = (width * 0.08f).coerceAtLeast(28f)
        val verticalPadding = (height * 0.10f).coerceAtLeast(28f)
        val focus = ScreenRect(
            ScreenPoint(horizontalPadding, verticalPadding),
            ScreenPoint(width - horizontalPadding, height - verticalPadding),
        )
        val camera = activeMap.cameraPosition(Geometry.fromPolyline(polyline), focus)
        activeMap.move(camera, Animation(Animation.Type.SMOOTH, 0.6f))
    }

    fun destroy() {
        val activeMap = map
        val activeCollection = collection
        if (activeMap != null && activeCollection != null && activeCollection.isValid) {
            try {
                activeCollection.parent.remove(activeCollection)
            } catch (_: RuntimeException) {
                // The MapWindow can already be released by its host.
            }
        }
        routePolyline = null
        routePoints = emptyList()
        collection = null
        window = null
        map = null
    }

    private fun applyTrafficColors(
        routeObject: PolylineMapObject,
        jamTypes: List<JamType>,
        segmentCount: Int,
    ) {
        if (jamTypes.isEmpty() || segmentCount <= 0) {
            routeObject.setStrokeColor(ROUTE_COLOR)
            return
        }
        routeObject.setPaletteColor(PALETTE_UNKNOWN, ROUTE_COLOR)
        routeObject.setPaletteColor(PALETTE_FREE, Color.rgb(47, 180, 95))
        routeObject.setPaletteColor(PALETTE_LIGHT, Color.rgb(255, 193, 7))
        routeObject.setPaletteColor(PALETTE_HARD, Color.rgb(255, 128, 0))
        routeObject.setPaletteColor(PALETTE_VERY_HARD, Color.rgb(229, 57, 53))
        routeObject.setPaletteColor(PALETTE_BLOCKED, Color.rgb(128, 0, 0))
        routeObject.setStrokeColors(
            List(segmentCount) { index -> paletteIndex(jamTypes.getOrNull(index)) },
        )
    }

    private fun paletteIndex(jamType: JamType?): Int = when (jamType) {
        JamType.FREE -> PALETTE_FREE
        JamType.LIGHT -> PALETTE_LIGHT
        JamType.HARD -> PALETTE_HARD
        JamType.VERY_HARD -> PALETTE_VERY_HARD
        JamType.BLOCKED -> PALETTE_BLOCKED
        JamType.UNKNOWN, null -> PALETTE_UNKNOWN
    }

    companion object {
        private val ROUTE_COLOR = Color.rgb(38, 132, 255)
        private val DESTINATION_COLOR = Color.rgb(239, 83, 80)
        private const val PALETTE_UNKNOWN = 0
        private const val PALETTE_FREE = 1
        private const val PALETTE_LIGHT = 2
        private const val PALETTE_HARD = 3
        private const val PALETTE_VERY_HARD = 4
        private const val PALETTE_BLOCKED = 5
    }
}

internal object RouteHitTest {
    fun distanceSquared(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        if (dx == 0f && dy == 0f) return (px - ax) * (px - ax) + (py - ay) * (py - ay)
        val ratio = (((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy))
            .coerceIn(0f, 1f)
        val closestX = ax + ratio * dx
        val closestY = ay + ratio * dy
        val offsetX = px - closestX
        val offsetY = py - closestY
        return offsetX * offsetX + offsetY * offsetY
    }
}
