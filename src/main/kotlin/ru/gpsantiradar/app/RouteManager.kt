package ru.gpsantiradar.app

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.yandex.mapkit.RequestPoint
import com.yandex.mapkit.RequestPointType
import com.yandex.mapkit.directions.DirectionsFactory
import com.yandex.mapkit.directions.driving.ConditionsListener
import com.yandex.mapkit.directions.driving.DrivingOptions
import com.yandex.mapkit.directions.driving.DrivingRoute
import com.yandex.mapkit.directions.driving.DrivingRouter
import com.yandex.mapkit.directions.driving.DrivingRouterType
import com.yandex.mapkit.directions.driving.DrivingSession
import com.yandex.mapkit.directions.driving.VehicleOptions
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.geometry.PolylinePosition
import com.yandex.mapkit.navigation.JamType
import com.yandex.runtime.Error
import kotlin.math.max
import kotlin.math.min

/** Owns the single route shared by the handset and Android Auto map surfaces. */
class RouteManager {
    fun interface Listener {
        fun onRouteChanged(snapshot: RouteSnapshot)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = linkedSetOf<Listener>()
    private var router: DrivingRouter? = null
    private var activeSession: DrivingSession? = null
    private var activeListener: DrivingSession.DrivingRouteListener? = null
    private var activeRoute: DrivingRoute? = null
    private var activeRoutePosition: PolylinePosition? = null
    private var requestGeneration = 0L
    private var visibleRouteId = 0L
    private var lastRerouteAttemptMillis = 0L
    private var lastProgressPublishMillis = 0L
    private val conditionsListener = object : ConditionsListener {
        override fun onConditionsUpdated() {
            runOnMain {
                val route = activeRoute ?: return@runOnMain
                val destination = snapshot.destination ?: return@runOnMain
                val position = activeRoutePosition ?: PolylinePosition(0, 0.0)
                publishReadyRouteSafely(route, snapshot.requestId, destination, position)
            }
        }

        override fun onConditionsOutdated() {
            runOnMain {
                if (snapshot.status == RouteStatus.READY) {
                    val unknownTypes = List(max(0, snapshot.points.size - 1)) {
                        JamType.UNKNOWN
                    }
                    publish(snapshot.copy(jamTypes = unknownTypes))
                }
            }
        }
    }

    var snapshot: RouteSnapshot = RouteSnapshot()
        private set

    fun addListener(listener: Listener, notifyNow: Boolean = true) {
        runOnMain {
            listeners.add(listener)
            if (notifyNow) listener.onRouteChanged(snapshot)
        }
    }

    fun removeListener(listener: Listener) {
        runOnMain { listeners.remove(listener) }
    }

    fun buildRoute(start: Point, destination: Point, initialAzimuth: Double? = null) {
        runOnMain {
            activeSession?.cancel()
            activeSession = null
            activeListener = null
            requestGeneration++
            detachActiveRoute()
            val routeId = ++visibleRouteId
            lastRerouteAttemptMillis = SystemClock.elapsedRealtime()
            lastProgressPublishMillis = 0L
            publish(
                RouteSnapshot(
                    requestId = routeId,
                    status = RouteStatus.BUILDING,
                    destination = destination,
                ),
            )
            requestRoute(start, destination, initialAzimuth, routeId, replacingActiveRoute = false)
        }
    }

    fun updateLocation(
        latitude: Double,
        longitude: Double,
        headingDegrees: Float,
        accuracyMeters: Float,
        elapsedRealtimeMillis: Long = SystemClock.elapsedRealtime(),
    ) {
        if (!latitude.isFinite() || !longitude.isFinite() ||
            latitude !in -90.0..90.0 || longitude !in -180.0..180.0
        ) {
            return
        }
        runOnMain {
            updateRouteProgress(
                Point(latitude, longitude),
                headingDegrees,
                accuracyMeters,
                elapsedRealtimeMillis,
            )
        }
    }

    fun clearRoute() {
        runOnMain {
            requestGeneration++
            visibleRouteId++
            activeSession?.cancel()
            activeSession = null
            activeListener = null
            detachActiveRoute()
            lastRerouteAttemptMillis = 0L
            lastProgressPublishMillis = 0L
            publish(RouteSnapshot(requestId = visibleRouteId))
        }
    }

    private fun updateRouteProgress(
        location: Point,
        headingDegrees: Float,
        accuracyMeters: Float,
        elapsedRealtimeMillis: Long,
    ) {
        if (snapshot.status != RouteStatus.READY ||
            !RouteRefreshPolicy.acceptsAccuracy(accuracyMeters)
        ) {
            return
        }
        val route = activeRoute ?: return
        val destination = snapshot.destination ?: return
        try {
            val routePoints = route.geometry.points
            if (routePoints.size < 2) return
            val previous = activeRoutePosition ?: PolylinePosition(0, 0.0)
            val firstSegment = max(0, previous.segmentIndex - 1)
            val lastSegment = min(routePoints.lastIndex - 1, previous.segmentIndex + MAX_FORWARD_SEGMENTS)
            val projection = RouteProgress.closestPosition(
                routePoints,
                location,
                firstSegment,
                lastSegment,
            ) ?: return
            if (
                RouteRefreshPolicy.shouldReroute(
                    projection.distanceMeters,
                    accuracyMeters,
                    elapsedRealtimeMillis,
                    lastRerouteAttemptMillis,
                    activeSession != null,
                )
            ) {
                lastRerouteAttemptMillis = elapsedRealtimeMillis
                requestRoute(
                    location,
                    destination,
                    headingDegrees.takeIf(Float::isFinite)?.toDouble(),
                    snapshot.requestId,
                    replacingActiveRoute = true,
                )
                return
            }
            if (projection.distanceMeters > RouteRefreshPolicy.snapDistanceMeters(accuracyMeters) ||
                !RouteProgress.isAheadOrEqual(
                    projection,
                    previous.segmentIndex,
                    previous.segmentPosition,
                )
            ) {
                return
            }
            val nextPosition = PolylinePosition(
                projection.segmentIndex,
                projection.segmentPosition,
            )
            val forwardDistance = RouteProgress.forwardDistanceMeters(
                routePoints,
                previous.segmentIndex,
                previous.segmentPosition,
                projection,
            )
            if (forwardDistance <= MIN_MEANINGFUL_PROGRESS_METERS) return
            if (forwardDistance < RouteRefreshPolicy.progressStepMeters(accuracyMeters) &&
                lastProgressPublishMillis > 0L &&
                elapsedRealtimeMillis - lastProgressPublishMillis < RouteRefreshPolicy.MAX_PROGRESS_SILENCE_MILLIS
            ) {
                return
            }
            if (publishReadyRouteSafely(route, snapshot.requestId, destination, nextPosition)) {
                activeRoutePosition = nextPosition
                lastProgressPublishMillis = elapsedRealtimeMillis
            }
        } catch (_: RuntimeException) {
            // Keep the last valid route visible if MapKit rejects a transient GPS update.
        } catch (_: LinkageError) {
            // MapKit can be unavailable on unsupported devices.
        }
    }

    private fun requestRoute(
        start: Point,
        destination: Point,
        initialAzimuth: Double?,
        routeId: Long,
        replacingActiveRoute: Boolean,
    ) {
        val currentGeneration = ++requestGeneration
        try {
            val routeListener = object : DrivingSession.DrivingRouteListener {
                override fun onDrivingRoutes(routes: MutableList<DrivingRoute>) {
                    if (currentGeneration != requestGeneration) return
                    activeSession = null
                    activeListener = null
                    val route = routes.firstOrNull()
                    if (route == null) {
                        handleRouteError(routeId, destination, replacingActiveRoute)
                        return
                    }
                    val initialPosition = PolylinePosition(0, 0.0)
                    val readySnapshot = createReadySnapshot(
                        route,
                        routeId,
                        destination,
                        initialPosition,
                    )
                    if (readySnapshot == null) {
                        handleRouteError(routeId, destination, replacingActiveRoute)
                        return
                    }
                    if (replacingActiveRoute) detachActiveRoute()
                    activeRoute = route
                    activeRoutePosition = initialPosition
                    lastProgressPublishMillis = SystemClock.elapsedRealtime()
                    addConditionsListenerSafely(route)
                    publish(readySnapshot)
                    requestConditionsUpdateSafely(route)
                }

                override fun onDrivingRoutesError(error: Error) {
                    if (currentGeneration != requestGeneration) return
                    activeSession = null
                    activeListener = null
                    handleRouteError(routeId, destination, replacingActiveRoute)
                }
            }
            activeListener = routeListener
            val options = DrivingOptions().setRoutesCount(1)
            if (initialAzimuth != null && initialAzimuth.isFinite()) {
                options.initialAzimuth = initialAzimuth
            }
            val requestPoints = listOf(
                RequestPoint(start, RequestPointType.WAYPOINT, null, null, null),
                RequestPoint(destination, RequestPointType.WAYPOINT, null, null, null),
            )
            val activeRouter = router ?: DirectionsFactory.getInstance()
                .createDrivingRouter(DrivingRouterType.ONLINE)
                .also { router = it }
            activeSession = activeRouter.requestRoutes(
                requestPoints,
                options,
                VehicleOptions(),
                routeListener,
            )
        } catch (_: RuntimeException) {
            handleRouteError(routeId, destination, replacingActiveRoute)
        } catch (_: LinkageError) {
            handleRouteError(routeId, destination, replacingActiveRoute)
        }
    }

    private fun createReadySnapshot(
        route: DrivingRoute,
        routeId: Long,
        destination: Point,
        position: PolylinePosition,
    ): RouteSnapshot? = try {
        route.position = position
        val visiblePoints = RouteProgress.remainingPoints(
            route.geometry.points,
            position.segmentIndex,
            position.segmentPosition,
        )
        val segmentCount = max(0, visiblePoints.size - 1)
        val weight = route.metadataAt(position).weight
        RouteSnapshot(
            requestId = routeId,
            status = RouteStatus.READY,
            points = visiblePoints,
            jamTypes = route.jamSegments.asSequence()
                .drop(position.segmentIndex)
                .take(segmentCount)
                .map { it.jamType }
                .toList(),
            destination = destination,
            distanceText = weight.distance.text,
            durationText = weight.timeWithTraffic.text,
        )
    } catch (_: RuntimeException) {
        null
    } catch (_: LinkageError) {
        null
    }

    private fun publishReadyRouteSafely(
        route: DrivingRoute,
        routeId: Long,
        destination: Point,
        position: PolylinePosition,
    ): Boolean {
        val next = createReadySnapshot(route, routeId, destination, position) ?: return false
        publish(next)
        return true
    }

    private fun handleRouteError(
        routeId: Long,
        destination: Point,
        replacingActiveRoute: Boolean,
    ) {
        activeSession = null
        activeListener = null
        if (!replacingActiveRoute) publishError(routeId, destination)
    }

    private fun addConditionsListenerSafely(route: DrivingRoute) {
        try {
            route.addConditionsListener(conditionsListener)
        } catch (_: RuntimeException) {
            // The route remains usable without live traffic updates.
        } catch (_: LinkageError) {
            // Keep compatibility with SDK builds without condition listeners.
        }
    }

    private fun requestConditionsUpdateSafely(route: DrivingRoute) {
        try {
            route.requestConditionsUpdate()
        } catch (_: RuntimeException) {
            // The already rendered route remains usable if live traffic refresh is unavailable.
        } catch (_: LinkageError) {
            // Keep compatibility with SDK builds that do not expose condition refresh at runtime.
        }
    }

    private fun publishError(routeId: Long, destination: Point) {
        publish(
            RouteSnapshot(
                requestId = routeId,
                status = RouteStatus.ERROR,
                destination = destination,
                errorMessage = "Не удалось построить маршрут. Проверьте интернет.",
            ),
        )
    }

    private fun detachActiveRoute() {
        val route = activeRoute
        if (route != null) {
            try {
                route.removeConditionsListener(conditionsListener)
            } catch (_: RuntimeException) {
                // The native route may already have been released.
            } catch (_: LinkageError) {
                // MapKit can be unavailable on unsupported devices.
            }
        }
        activeRoute = null
        activeRoutePosition = null
        lastProgressPublishMillis = 0L
    }

    private fun publish(next: RouteSnapshot) {
        snapshot = next
        listeners.toList().forEach { it.onRouteChanged(next) }
    }

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }

    private companion object {
        const val MAX_FORWARD_SEGMENTS = 300
        const val MIN_MEANINGFUL_PROGRESS_METERS = 0.5
    }
}
