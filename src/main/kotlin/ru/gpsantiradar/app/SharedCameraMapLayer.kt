package ru.gpsantiradar.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.SystemClock
import com.yandex.mapkit.Animation
import com.yandex.mapkit.ScreenPoint
import com.yandex.mapkit.geometry.LinearRing
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.geometry.Polygon
import com.yandex.mapkit.map.CameraListener
import com.yandex.mapkit.map.CameraPosition
import com.yandex.mapkit.map.CameraUpdateReason
import com.yandex.mapkit.map.IconStyle
import com.yandex.mapkit.map.MapObject
import com.yandex.mapkit.map.MapObjectCollection
import com.yandex.mapkit.map.MapObjectTapListener
import com.yandex.mapkit.map.MapWindow
import com.yandex.mapkit.map.PlacemarkMapObject
import com.yandex.mapkit.map.PolygonMapObject
import com.yandex.mapkit.map.RotationType
import com.yandex.runtime.image.ImageProvider
import java.lang.ref.WeakReference
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Camera, coverage and current-location objects owned by one MapWindow. */
class SharedCameraMapLayer(context: Context?, mapWindow: MapWindow?, host: Host?) {
    interface Host {
        fun postToUi(action: Runnable)
        fun onMarkerPresentationChanged()
        fun onCameraTapped(camera: CameraPoint, position: Point)
    }

    private var queryContext: Context?
    private var resourceContext: Context?
    private var mapWindow: MapWindow?
    private var host: Host?
    private var map: com.yandex.mapkit.map.Map?
    private var cameraMarkerCollection: MapObjectCollection?
    private var cameraCoverageCollection: MapObjectCollection?
    private var locationCollection: MapObjectCollection?
    private var locationPlacemark: PlacemarkMapObject? = null
    private var cameraCoverageVisible = false
    private var mapCenteredOnGps = false
    private var nightMode = false
    private var followPausedUntil = 0L
    private var lastLatitude = Double.NaN
    private var lastLongitude = Double.NaN
    private var lastHeadingDegrees = 0f
    private var lastSpeedKmh = 0f
    private var activeCameraId = -1L

    @Volatile
    private var initialLoadGeneration = 0

    @Volatile
    private var cameraLoadGeneration = 0

    @Volatile
    private var destroyed = false

    private val markerIcons = HashMap<Int, ImageProvider>()
    private val markerResources = HashMap<Int, Int>()
    private val clusterIcons = HashMap<Int, ImageProvider>()
    private val renderedCameras = HashMap<Long, CameraPoint>()
    private val renderedCameraCoverage = HashMap<Long, List<PolygonMapObject>>()
    private val renderedMarkerObjects = HashMap<String, PlacemarkMapObject>()
    private val renderedMarkerEntities = HashMap<String, MapMarkerLayout.Entity>()

    private val placemarkTapListener = MapObjectTapListener { mapObject, point ->
        val data = mapObject.userData
        val activeHost = this.host
        if (!destroyed && activeHost != null &&
            data is CameraPoint && mapObject is PlacemarkMapObject
        ) {
            activeHost.onCameraTapped(data, point)
            true
        } else {
            false
        }
    }
    private val placemarkTapListenerReference = WeakReference(placemarkTapListener)

    private val cameraListener = CameraListener {
            _: com.yandex.mapkit.map.Map,
            _: CameraPosition,
            reason: CameraUpdateReason,
            finished: Boolean,
        ->
        if (reason == CameraUpdateReason.GESTURES) pauseFollowing()
        if (finished) refreshVisible()
    }
    private val cameraListenerReference = WeakReference(cameraListener)

    init {
        val checkedContext = requireNotNull(context) { "context is required" }
        val checkedWindow = requireNotNull(mapWindow) { "mapWindow is required" }
        val checkedHost = requireNotNull(host) { "host is required" }
        queryContext = checkedContext.applicationContext
        resourceContext = checkedContext
        this.mapWindow = checkedWindow
        this.host = checkedHost
        val activeMap = checkedWindow.map
        map = activeMap
        cameraCoverageCollection = activeMap.mapObjects.addCollection()
        cameraMarkerCollection = activeMap.mapObjects.addCollection()
        locationCollection = activeMap.mapObjects.addCollection()
        activeMap.addCameraListener(cameraListenerReference)
    }

    fun loadInitial(moveToData: Boolean) {
        if (destroyed) return
        val queryContext = this.queryContext ?: return
        val generation = ++initialLoadGeneration
        Thread({
            val bounds = CameraDatabase(queryContext).use { it.bounds() }
            if (generation != initialLoadGeneration || destroyed) return@Thread
            val activeHost = host ?: return@Thread
            activeHost.postToUi {
                if (generation != initialLoadGeneration || destroyed) return@postToUi
                val activeMap = map ?: return@postToUi
                if (moveToData && bounds != null) {
                    val latitude = (bounds[0] + bounds[1]) / 2.0
                    val longitude = (bounds[2] + bounds[3]) / 2.0
                    activeMap.move(CameraPosition(Point(latitude, longitude), 7.5f, 0f, 0f))
                }
                refreshVisible()
            }
        }, "map-camera-load").start()
    }

    fun refreshVisible() {
        val activeMap = map
        val queryContext = this.queryContext
        if (destroyed || activeMap == null || queryContext == null) return
        val region = try {
            activeMap.visibleRegion
        } catch (_: RuntimeException) {
            return
        }
        val south = min(
            min(region.topLeft.latitude, region.topRight.latitude),
            min(region.bottomLeft.latitude, region.bottomRight.latitude),
        )
        val north = max(
            max(region.topLeft.latitude, region.topRight.latitude),
            max(region.bottomLeft.latitude, region.bottomRight.latitude),
        )
        val west = min(
            min(region.topLeft.longitude, region.bottomLeft.longitude),
            min(region.topRight.longitude, region.bottomRight.longitude),
        )
        val east = max(
            max(region.topLeft.longitude, region.bottomLeft.longitude),
            max(region.topRight.longitude, region.bottomRight.longitude),
        )

        val latPadding = (north - south) * 0.20
        val lonPadding = (east - west) * 0.20
        val querySouth = max(-90.0, south - latPadding)
        val queryNorth = min(90.0, north + latPadding)
        val queryWest = max(-180.0, west - lonPadding)
        val queryEast = min(180.0, east + lonPadding)
        val generation = ++cameraLoadGeneration

        Thread({
            val points = CameraDatabase(queryContext).use {
                it.withinBounds(querySouth, queryNorth, queryWest, queryEast, MAX_VISIBLE_MARKERS)
            }
            if (generation != cameraLoadGeneration || destroyed) return@Thread
            val activeHost = host ?: return@Thread
            activeHost.postToUi {
                if (generation == cameraLoadGeneration && !destroyed) renderCameraMarkers(points)
            }
        }, "visible-camera-load").start()
    }

    fun setNightMode(enabled: Boolean) {
        if (destroyed) return
        val markerColorsChanged = nightMode != enabled
        nightMode = enabled
        val activeMap = map
        if (activeMap != null && activeMap.isNightModeEnabled != enabled) {
            activeMap.isNightModeEnabled = enabled
        }
        val placemark = locationPlacemark
        if (markerColorsChanged && placemark != null && placemark.isValid) {
            placemark.setIcon(ImageProvider.fromBitmap(createLocationBitmap(nightMode)))
        }
    }

    /** Updates the position and suppresses redundant following below 1 km/h. */
    fun updateCurrentLocation(
        latitude: Double,
        longitude: Double,
        speedKmh: Float,
        headingDegrees: Float,
    ) {
        if (destroyed) return
        lastLatitude = latitude
        lastLongitude = longitude
        lastSpeedKmh = speedKmh
        lastHeadingDegrees = MapOrientation.stableHeading(lastHeadingDegrees, headingDegrees, speedKmh)
        updateLocationMarker()
        centerOnLocationFromGps(speedKmh)
    }

    /** Applies emphasis to the camera already selected by the tracking algorithm. */
    fun updateActiveCamera(cameraId: Long) {
        if (destroyed || activeCameraId == cameraId) return
        val previousActiveCameraId = activeCameraId
        activeCameraId = cameraId
        refreshCoverageStyle(previousActiveCameraId)
        refreshCoverageStyle(activeCameraId)
    }

    fun moveToCurrentLocation() {
        val activeMap = map
        if (destroyed || activeMap == null || lastLatitude.isNaN() || lastLongitude.isNaN()) return
        resumeFollowing()
        val current = activeMap.cameraPosition
        val azimuth = MapOrientation.cameraAzimuth(
            autoRotateMap(),
            lastSpeedKmh,
            lastHeadingDegrees,
            current.azimuth,
        )
        activeMap.move(
            CameraPosition(Point(lastLatitude, lastLongitude), 15f, azimuth, current.tilt),
            Animation(Animation.Type.SMOOTH, 0.55f),
        )
        mapCenteredOnGps = true
    }

    fun zoomBy(delta: Float) {
        zoomBy(delta, Animation(Animation.Type.SMOOTH, 0.35f))
    }

    fun zoomByImmediately(delta: Float) {
        zoomBy(delta, null)
    }

    /** Handles projected-surface clicks because they are not dispatched to MapView. */
    fun tapCameraAt(x: Float, y: Float): Boolean {
        val window = mapWindow
        if (destroyed || window == null || !x.isFinite() || !y.isFinite()) return false
        val candidates = ArrayList<MapMarkerHitTest.Candidate>()
        for (entity in renderedMarkerEntities.values) {
            val camera = entity.camera
            if (entity.cluster || camera == null) continue
            val screen = window.worldToScreen(Point(entity.latitude, entity.longitude))
            if (screen != null) {
                candidates.add(MapMarkerHitTest.Candidate(camera, screen.x, screen.y))
            }
        }
        val selected = MapMarkerHitTest.nearest(x, y, dp(28f), candidates)
        val activeHost = host
        if (selected == null || activeHost == null) return false
        activeHost.onCameraTapped(selected, Point(selected.latitude, selected.longitude))
        return true
    }

    private fun zoomBy(delta: Float, animation: Animation?) {
        val activeMap = map
        if (destroyed || activeMap == null) return
        pauseFollowing()
        val current = activeMap.cameraPosition
        val zoom = max(2f, min(21f, current.zoom + delta))
        val next = CameraPosition(current.target, zoom, current.azimuth, current.tilt)
        if (animation == null) activeMap.move(next) else activeMap.move(next, animation)
    }

    fun pauseFollowing() {
        if (!destroyed) followPausedUntil = SystemClock.elapsedRealtime() + FOLLOW_PAUSE_MS
    }

    fun resumeFollowing() {
        if (!destroyed) followPausedUntil = 0L
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        initialLoadGeneration++
        cameraLoadGeneration++
        val activeMap = map
        if (activeMap != null) {
            try {
                activeMap.removeCameraListener(cameraListenerReference)
                removeOwnedCollection(cameraMarkerCollection)
                removeOwnedCollection(cameraCoverageCollection)
                removeOwnedCollection(locationCollection)
            } catch (_: RuntimeException) {
                // The MapWindow may already have been released by its host.
            }
        }
        renderedMarkerObjects.clear()
        renderedMarkerEntities.clear()
        renderedCameraCoverage.clear()
        renderedCameras.clear()
        activeCameraId = -1L
        markerIcons.clear()
        markerResources.clear()
        clusterIcons.clear()
        locationPlacemark = null
        cameraMarkerCollection = null
        cameraCoverageCollection = null
        locationCollection = null
        map = null
        mapWindow = null
        host = null
        queryContext = null
        resourceContext = null
    }

    private fun removeOwnedCollection(collection: MapObjectCollection?) {
        if (collection != null && collection.isValid && collection.parent != null) {
            collection.parent.remove(collection)
        }
    }

    private fun renderCameraMarkers(points: List<CameraPoint?>?) {
        val activeMap = map ?: return
        val markerCollection = cameraMarkerCollection ?: return
        val coverageCollection = cameraCoverageCollection ?: return
        val validPoints = MapMarkerLayout.validCameras(points)
        val coverageDiff = CameraMarkerDiff.between(renderedCameras, validPoints)
        val entities = MapMarkerLayout.create(validPoints, activeMap.cameraPosition.zoom)
        val markerDiff = MapMarkerEntityDiff.between(renderedMarkerEntities, entities)
        val showCoverage = activeMap.cameraPosition.zoom >= COVERAGE_MIN_ZOOM
        val coverageModeChanged = showCoverage != cameraCoverageVisible
        val markerChanges = markerDiff.removeKeys.isNotEmpty() ||
            markerDiff.update.isNotEmpty() || markerDiff.add.isNotEmpty()
        if (!markerChanges && !coverageDiff.hasMarkerChanges() && !coverageModeChanged) return

        if (markerChanges) host?.onMarkerPresentationChanged()
        for (key in markerDiff.removeKeys) {
            renderedMarkerObjects.remove(key)?.let { markerCollection.remove(it) }
        }
        for (entity in markerDiff.update) {
            val marker = renderedMarkerObjects[entity.key]
            val previous = renderedMarkerEntities[entity.key]
            if (marker != null && previous != null) updateMarkerEntity(marker, previous, entity)
        }
        for (entity in markerDiff.add) {
            renderedMarkerObjects[entity.key] = addMarkerEntity(entity)
        }
        renderedMarkerEntities.clear()
        renderedMarkerEntities.putAll(markerDiff.desired)

        for (id in coverageDiff.removeIds) removeCameraCoverage(id)
        for (camera in coverageDiff.addOrReplace) {
            if (showCoverage && !coverageModeChanged) {
                renderedCameraCoverage[camera.id] = addCameraCoverage(camera)
            }
        }
        renderedCameras.clear()
        renderedCameras.putAll(coverageDiff.desired)

        if (coverageModeChanged) {
            coverageCollection.clear()
            renderedCameraCoverage.clear()
            if (showCoverage) {
                for (camera in renderedCameras.values) {
                    renderedCameraCoverage[camera.id] = addCameraCoverage(camera)
                }
            }
            cameraCoverageVisible = showCoverage
        }
    }

    private fun addMarkerEntity(entity: MapMarkerLayout.Entity): PlacemarkMapObject {
        val collection = checkNotNull(cameraMarkerCollection)
        val point = Point(entity.latitude, entity.longitude)
        if (entity.cluster) {
            return collection.addPlacemark(
                point,
                clusterIcon(entity.memberIds.size),
                clusterMarkerStyle(),
            )
        }
        val camera = checkNotNull(entity.camera)
        val marker = collection.addPlacemark(point, iconForCamera(camera), individualMarkerStyle())
        if (camera.isCameraOrControl()) marker.direction = shootingBearing(camera)
        marker.userData = camera
        marker.addTapListener(placemarkTapListenerReference)
        return marker
    }

    private fun updateMarkerEntity(
        marker: PlacemarkMapObject,
        previous: MapMarkerLayout.Entity,
        current: MapMarkerLayout.Entity,
    ) {
        if (previous.latitude.compareTo(current.latitude) != 0 ||
            previous.longitude.compareTo(current.longitude) != 0
        ) {
            marker.geometry = Point(current.latitude, current.longitude)
        }
        if (current.cluster) {
            if (previous.memberIds.size != current.memberIds.size) {
                marker.setIcon(clusterIcon(current.memberIds.size))
            }
            return
        }

        val oldCamera = checkNotNull(previous.camera)
        val newCamera = checkNotNull(current.camera)
        if (oldCamera.type != newCamera.type) marker.setIcon(iconForCamera(newCamera))
        val oldDirection = if (oldCamera.isCameraOrControl()) shootingBearing(oldCamera) else 0f
        val newDirection = if (newCamera.isCameraOrControl()) shootingBearing(newCamera) else 0f
        if (oldDirection.compareTo(newDirection) != 0) marker.direction = newDirection
        marker.userData = newCamera
    }

    private fun individualMarkerStyle(): IconStyle = IconStyle()
        .setAnchor(android.graphics.PointF(0.5f, 0.5f))
        .setRotationType(RotationType.ROTATE)
        .setFlat(true)
        .setScale(1.0f)
        .setZIndex(10f)

    private fun clusterMarkerStyle(): IconStyle = IconStyle()
        .setAnchor(android.graphics.PointF(0.5f, 0.5f))
        .setRotationType(RotationType.NO_ROTATION)
        .setFlat(false)
        .setScale(1.0f)
        .setZIndex(20f)

    private fun clusterIcon(count: Int): ImageProvider =
        clusterIcons.getOrPut(count) { createClusterIcon(count) }

    private fun removeCameraCoverage(id: Long) {
        val coverage = renderedCameraCoverage.remove(id) ?: return
        val collection = cameraCoverageCollection ?: return
        for (polygon in coverage) collection.remove(polygon)
    }

    private fun addCameraCoverage(camera: CameraPoint): List<PolygonMapObject> {
        val result = ArrayList<PolygonMapObject>()
        if (!camera.isCameraOrControl()) return result
        val baseColor = markerColor(if (camera.isObservation()) OBSERVATION_MARKER else camera.type)
        val colors = MapVisualStyle.coverage(baseColor, camera.id, activeCameraId)
        val origin = Point(camera.latitude, camera.longitude)
        if (camera.dirType == 0) {
            addCoverageCircle(origin, camera.distanceMeters.toDouble(), colors.fillColor, colors.strokeColor, result)
            return result
        }
        val halfAngle = max(1f, camera.angleDegrees / 2f)
        addCoverageSector(
            origin,
            primaryCoverageBearing(camera),
            camera.distanceMeters.toDouble(),
            halfAngle,
            colors.fillColor,
            colors.strokeColor,
            result,
        )
        if (camera.hasReverseZone()) {
            addCoverageSector(
                origin,
                camera.direction,
                camera.reverseDistanceMeters.toDouble(),
                halfAngle,
                colors.fillColor,
                colors.strokeColor,
                result,
            )
        }
        return result
    }

    private fun refreshCoverageStyle(cameraId: Long) {
        val camera = renderedCameras[cameraId] ?: return
        val coverage = renderedCameraCoverage[cameraId] ?: return
        val baseColor = markerColor(if (camera.isObservation()) OBSERVATION_MARKER else camera.type)
        val colors = MapVisualStyle.coverage(baseColor, camera.id, activeCameraId)
        for (polygon in coverage) {
            polygon.fillColor = colors.fillColor
            polygon.strokeColor = colors.strokeColor
        }
    }

    private fun addCoverageCircle(
        origin: Point,
        radiusMeters: Double,
        fill: Int,
        stroke: Int,
        result: MutableList<PolygonMapObject>,
    ) {
        if (radiusMeters <= 0) return
        val boundary = ArrayList<Point>()
        for (bearing in 0..360 step 10) boundary.add(destination(origin, bearing.toDouble(), radiusMeters))
        val polygon = Polygon(LinearRing(boundary), emptyList())
        val circle = checkNotNull(cameraCoverageCollection).addPolygon(polygon)
        circle.fillColor = fill
        circle.strokeColor = stroke
        circle.strokeWidth = 1.2f
        circle.isGeodesic = true
        circle.zIndex = 2f
        result.add(circle)
    }

    private fun addCoverageSector(
        origin: Point,
        bearing: Float,
        rangeMeters: Double,
        halfAngle: Float,
        fill: Int,
        stroke: Int,
        result: MutableList<PolygonMapObject>,
    ) {
        if (rangeMeters <= 0) return
        val boundary = ArrayList<Point>()
        boundary.add(origin)
        val step = max(1.5f, halfAngle / 5f)
        var offset = -halfAngle
        while (offset <= halfAngle) {
            boundary.add(destination(origin, (bearing + offset).toDouble(), rangeMeters))
            offset += step
        }
        boundary.add(destination(origin, (bearing + halfAngle).toDouble(), rangeMeters))
        boundary.add(origin)
        val polygon = Polygon(LinearRing(boundary), emptyList())
        val sector = checkNotNull(cameraCoverageCollection).addPolygon(polygon)
        sector.fillColor = fill
        sector.strokeColor = stroke
        sector.strokeWidth = 1.2f
        sector.isGeodesic = true
        sector.zIndex = 2f
        result.add(sector)
    }

    private fun destination(start: Point, bearingDegrees: Double, distanceMeters: Double): Point {
        val radius = 6371000.0
        val angularDistance = distanceMeters / radius
        val bearing = Math.toRadians(bearingDegrees)
        val latitude = Math.toRadians(start.latitude)
        val longitude = Math.toRadians(start.longitude)
        val destinationLatitude = asin(
            sin(latitude) * cos(angularDistance) +
                cos(latitude) * sin(angularDistance) * cos(bearing),
        )
        val destinationLongitude = longitude + atan2(
            sin(bearing) * sin(angularDistance) * cos(latitude),
            cos(angularDistance) - sin(latitude) * sin(destinationLatitude),
        )
        return Point(Math.toDegrees(destinationLatitude), Math.toDegrees(destinationLongitude))
    }

    private fun shootingBearing(camera: CameraPoint): Float {
        var result = camera.direction
        if (camera.dirType == 1 || camera.dirType == 2 || camera.dirType == 4) result += 180f
        result %= 360f
        return if (result < 0f) result + 360f else result
    }

    private fun primaryCoverageBearing(camera: CameraPoint): Float {
        val result = (camera.direction + 180f) % 360f
        return if (result < 0f) result + 360f else result
    }

    private fun iconForCamera(camera: CameraPoint): ImageProvider {
        val resourceId = cameraIconResource(camera.type)
        return markerIcons.getOrPut(resourceId) {
            ImageProvider.fromBitmap(createCameraBitmap(resourceId))
        }
    }

    private fun cameraIconResource(type: Int): Int = markerResources.getOrPut(type) {
        val context = checkNotNull(resourceContext)
        val found = context.resources.getIdentifier("cam_type_$type", "drawable", context.packageName)
        if (found == 0) R.drawable.cam_type_0 else found
    }

    private fun createCameraBitmap(resourceId: Int): Bitmap {
        val size = dp(42)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val context = checkNotNull(resourceContext)
        var drawable: Drawable? = context.getDrawable(resourceId)
        if (drawable == null) drawable = context.getDrawable(R.drawable.cam_type_0)
        drawable?.let {
            it.setBounds(0, 0, size, size)
            it.draw(canvas)
        }
        return bitmap
    }

    private fun markerColor(type: Int): Int = when (type) {
        OBSERVATION_MARKER -> Color.rgb(70, 125, 165)
        16 -> Color.rgb(115, 115, 115)
        3, 10, 18, 103 -> Color.rgb(220, 55, 48)
        5, 104, 105, 108 -> Color.rgb(195, 65, 155)
        41, 42, 43 -> Color.rgb(236, 160, 20)
        107 -> Color.rgb(35, 115, 220)
        17, 171, 172 -> Color.rgb(145, 75, 190)
        else -> Color.rgb(238, 103, 28)
    }

    private fun createClusterIcon(count: Int): ImageProvider {
        val size = dp(42)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.argb(245, 255, 255, 255)
        canvas.drawCircle(size / 2f, size / 2f, size * 0.48f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(3).toFloat()
        paint.color = GREEN
        canvas.drawCircle(size / 2f, size / 2f, size * 0.42f, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(35, 35, 35)
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = dp(if (count > 999) 10 else 13).toFloat()
        val fm = paint.fontMetrics
        val baseline = size / 2f - (fm.ascent + fm.descent) / 2f
        canvas.drawText(count.toString(), size / 2f, baseline, paint)
        return ImageProvider.fromBitmap(bitmap)
    }

    private fun updateLocationMarker() {
        val collection = locationCollection
        if (lastLatitude.isNaN() || lastLongitude.isNaN() || collection == null) return
        val point = Point(lastLatitude, lastLongitude)
        var placemark = locationPlacemark
        if (placemark == null || !placemark.isValid) {
            placemark = collection.addPlacemark(
                point,
                ImageProvider.fromBitmap(createLocationBitmap(nightMode)),
                locationMarkerStyle(),
            )
            placemark.zIndex = 100f
            locationPlacemark = placemark
        }
        placemark.geometry = point
        placemark.direction = lastHeadingDegrees
    }

    private fun createLocationBitmap(nightMode: Boolean): Bitmap {
        val size = dp(42)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val center = size / 2f
        val outline = Path().apply {
            moveTo(center, dp(2).toFloat())
            lineTo(dp(38).toFloat(), dp(36).toFloat())
            lineTo(center, dp(29).toFloat())
            lineTo(dp(4).toFloat(), dp(36).toFloat())
            close()
        }

        paint.style = Paint.Style.STROKE
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeWidth = dp(3).toFloat()
        paint.color = Color.WHITE
        canvas.drawPath(outline, paint)

        paint.style = Paint.Style.FILL
        paint.color = MapVisualStyle.locationPrimaryColor(nightMode)
        canvas.drawPath(outline, paint)

        val highlight = Path().apply {
            moveTo(center, dp(2).toFloat())
            lineTo(center, dp(29).toFloat())
            lineTo(dp(4).toFloat(), dp(36).toFloat())
            close()
        }
        paint.color = MapVisualStyle.locationHighlightColor(nightMode)
        canvas.drawPath(highlight, paint)
        return bitmap
    }

    private fun locationMarkerStyle(): IconStyle = IconStyle()
        .setAnchor(android.graphics.PointF(0.5f, 0.5f))
        .setRotationType(RotationType.ROTATE)
        .setFlat(true)
        .setScale(0.8f)
        .setZIndex(100f)

    private fun centerOnLocationFromGps(speedKmh: Float) {
        val activeMap = map
        if (activeMap == null || lastLatitude.isNaN() || lastLongitude.isNaN()) return
        if (SystemClock.elapsedRealtime() < followPausedUntil) return
        if (mapCenteredOnGps && speedKmh < 1f) return
        val current = activeMap.cameraPosition
        val zoom = if (mapCenteredOnGps) current.zoom else 15f
        val azimuth = MapOrientation.cameraAzimuth(
            autoRotateMap(),
            speedKmh,
            lastHeadingDegrees,
            current.azimuth,
        )
        activeMap.move(
            CameraPosition(Point(lastLatitude, lastLongitude), zoom, azimuth, current.tilt),
            Animation(Animation.Type.SMOOTH, 0.45f),
        )
        mapCenteredOnGps = true
    }

    private fun autoRotateMap(): Boolean = resourceContext?.getSharedPreferences(
        AppSettings.PREFERENCES,
        Context.MODE_PRIVATE,
    )?.getBoolean(AppSettings.AUTO_ROTATE_MAP, AppSettings.DEFAULT_AUTO_ROTATE_MAP) == true

    private fun dp(value: Int): Int = (
        value * checkNotNull(resourceContext).resources.displayMetrics.density
    ).roundToInt()

    private fun dp(value: Float): Float =
        value * checkNotNull(resourceContext).resources.displayMetrics.density

    companion object {
        private const val OBSERVATION_MARKER = -1
        private const val MAX_VISIBLE_MARKERS = 5000
        private const val COVERAGE_MIN_ZOOM = 13f
        private const val FOLLOW_PAUSE_MS = 7000L
        private val GREEN = Color.rgb(0, 166, 82)
    }
}
