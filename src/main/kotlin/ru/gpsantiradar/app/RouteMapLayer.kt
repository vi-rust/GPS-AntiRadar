package ru.gpsantiradar.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import com.yandex.mapkit.Animation
import com.yandex.mapkit.ScreenPoint
import com.yandex.mapkit.ScreenRect
import com.yandex.mapkit.geometry.Geometry
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.geometry.Polyline
import com.yandex.mapkit.map.Map
import com.yandex.mapkit.map.MapObjectCollection
import com.yandex.mapkit.map.MapWindow
import com.yandex.mapkit.map.IconStyle
import com.yandex.mapkit.map.PolylineMapObject
import com.yandex.mapkit.map.RotationType
import com.yandex.mapkit.navigation.JamType
import com.yandex.runtime.image.ImageProvider
import kotlin.math.roundToInt

/** Route objects owned by one MapWindow. */
class RouteMapLayer(context: Context, mapWindow: MapWindow) {
    private val density = context.resources.displayMetrics.density
    private var map: Map? = mapWindow.map
    private var window: MapWindow? = mapWindow
    private var collection: MapObjectCollection? = mapWindow.map.mapObjects.addCollection()
    private var routePolyline: Polyline? = null
    private var routeObject: PolylineMapObject? = null
    private var routePoints: List<Point> = emptyList()
    private var routeDestination: Point? = null
    private var routeJamTypes: List<JamType> = emptyList()
    private var renderedRequestId = -1L
    private var trafficVisible = true

    fun render(snapshot: RouteSnapshot) {
        val activeCollection = collection ?: return
        if (snapshot.status != RouteStatus.READY || snapshot.points.size < 2) {
            clearRenderedRoute(activeCollection)
            return
        }

        val polyline = Polyline(snapshot.points)
        val currentObject = routeObject
        val reusable = renderedRequestId == snapshot.requestId && currentObject?.isValid == true
        val activeRouteObject = if (reusable) {
            currentObject!!.apply { geometry = polyline }
        } else {
            clearRenderedRoute(activeCollection)
            activeCollection.addPolyline(polyline).apply {
                strokeWidth = 7f
                outlineColor = Color.WHITE
                outlineWidth = 2f
                zIndex = 20f
            }.also { created ->
                routeObject = created
                renderedRequestId = snapshot.requestId
                snapshot.destination?.let { destination ->
                    activeCollection.addPlacemark(
                        destination,
                        ImageProvider.fromBitmap(createDestinationBitmap()),
                        destinationMarkerStyle(),
                    ).apply {
                        zIndex = 21f
                    }
                }
            }
        }
        routePolyline = polyline
        routePoints = snapshot.points
        routeDestination = snapshot.destination
        routeJamTypes = snapshot.jamTypes
        applyTrafficColors(activeRouteObject, snapshot.jamTypes, snapshot.points.size - 1)
    }

    fun setTrafficVisible(visible: Boolean) {
        if (trafficVisible == visible) return
        trafficVisible = visible
        routeObject?.takeIf { it.isValid }?.let { activeRouteObject ->
            applyTrafficColors(activeRouteObject, routeJamTypes, maxOf(0, routePoints.size - 1))
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
        val points = routePoints
        if (points.size < 2) return
        val destination = routeDestination
        val fitPolyline = if (destination != null && destination != points.last()) {
            Polyline(points + destination)
        } else {
            routePolyline ?: return
        }
        val width = activeWindow.width()
        val height = activeWindow.height()
        if (width <= 0 || height <= 0) return
        val horizontalPadding = (width * 0.08f).coerceAtLeast(28f)
        val verticalPadding = (height * 0.14f).coerceAtLeast(dp(50f))
        val focus = ScreenRect(
            ScreenPoint(horizontalPadding, verticalPadding),
            ScreenPoint(width - horizontalPadding, height - verticalPadding),
        )
        val camera = activeMap.cameraPosition(Geometry.fromPolyline(fitPolyline), focus)
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
        routeObject = null
        routePoints = emptyList()
        routeDestination = null
        routeJamTypes = emptyList()
        renderedRequestId = -1L
        collection = null
        window = null
        map = null
    }

    private fun clearRenderedRoute(activeCollection: MapObjectCollection) {
        activeCollection.clear()
        routePolyline = null
        routeObject = null
        routePoints = emptyList()
        routeDestination = null
        routeJamTypes = emptyList()
        renderedRequestId = -1L
    }

    private fun applyTrafficColors(
        routeObject: PolylineMapObject,
        jamTypes: List<JamType>,
        segmentCount: Int,
    ) {
        if (!trafficVisible || jamTypes.isEmpty() || segmentCount <= 0) {
            // MapKit resets the segment palette when a uniform stroke color is set.
            // Passing an empty segment-color list can fail inside the native SDK.
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

    private fun createDestinationBitmap(): Bitmap {
        val width = px(36f)
        val height = px(44f)
        val centerX = width / 2f
        val marker = Path().apply {
            moveTo(centerX, height - dp(2f))
            cubicTo(
                centerX - dp(2f),
                height - dp(10f),
                dp(4f),
                dp(29f),
                dp(4f),
                dp(18f),
            )
            cubicTo(dp(4f), dp(9f), dp(10f), dp(3f), centerX, dp(3f))
            cubicTo(width - dp(10f), dp(3f), width - dp(4f), dp(9f), width - dp(4f), dp(18f))
            cubicTo(
                width - dp(4f),
                dp(29f),
                centerX + dp(2f),
                height - dp(10f),
                centerX,
                height - dp(2f),
            )
            close()
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = DESTINATION_COLOR
                style = Paint.Style.FILL
            }
            canvas.drawPath(marker, paint)
            paint.apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = dp(2.5f)
                strokeJoin = Paint.Join.ROUND
            }
            canvas.drawPath(marker, paint)
            paint.apply {
                color = Color.WHITE
                style = Paint.Style.FILL
            }
            canvas.drawCircle(centerX, dp(17f), dp(5.5f), paint)
        }
    }

    private fun destinationMarkerStyle(): IconStyle = IconStyle()
        .setAnchor(PointF(0.5f, 1f))
        .setRotationType(RotationType.NO_ROTATION)
        .setFlat(false)
        .setScale(1f)
        .setZIndex(21f)

    private fun dp(value: Float): Float = value * density

    private fun px(value: Float): Int = dp(value).roundToInt().coerceAtLeast(1)

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
