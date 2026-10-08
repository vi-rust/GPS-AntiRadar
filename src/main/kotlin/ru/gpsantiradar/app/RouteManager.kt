package ru.gpsantiradar.app

import android.os.Handler
import android.os.Looper
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
import com.yandex.mapkit.navigation.JamType
import com.yandex.runtime.Error

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
    private var requestId = 0L
    private val conditionsListener = object : ConditionsListener {
        override fun onConditionsUpdated() {
            runOnMain {
                val route = activeRoute ?: return@runOnMain
                if (snapshot.status != RouteStatus.READY) return@runOnMain
                val weight = route.metadata.weight
                publish(
                    snapshot.copy(
                        jamTypes = route.jamSegments.map { it.jamType },
                        distanceText = weight.distance.text,
                        durationText = weight.timeWithTraffic.text,
                    ),
                )
            }
        }

        override fun onConditionsOutdated() {
            runOnMain {
                if (snapshot.status == RouteStatus.READY) {
                    val unknownTypes = List(maxOf(0, snapshot.points.size - 1)) {
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
            detachActiveRoute()
            val currentRequestId = ++requestId
            publish(
                RouteSnapshot(
                    requestId = currentRequestId,
                    status = RouteStatus.BUILDING,
                    destination = destination,
                ),
            )
            try {
                val routeListener = object : DrivingSession.DrivingRouteListener {
                    override fun onDrivingRoutes(routes: MutableList<DrivingRoute>) {
                        if (currentRequestId != requestId) return
                        activeSession = null
                        activeListener = null
                        val route = routes.firstOrNull()
                        if (route == null) {
                            publishError(currentRequestId, destination)
                            return
                        }
                        activeRoute = route
                        route.addConditionsListener(conditionsListener)
                        val weight = route.metadata.weight
                        publish(
                            RouteSnapshot(
                                requestId = currentRequestId,
                                status = RouteStatus.READY,
                                points = route.geometry.points.toList(),
                                jamTypes = route.jamSegments.map { it.jamType },
                                destination = destination,
                                distanceText = weight.distance.text,
                                durationText = weight.timeWithTraffic.text,
                            ),
                        )
                        requestConditionsUpdateSafely(route)
                    }

                    override fun onDrivingRoutesError(error: Error) {
                        if (currentRequestId != requestId) return
                        activeSession = null
                        activeListener = null
                        publishError(currentRequestId, destination)
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
                publishError(currentRequestId, destination)
            } catch (_: LinkageError) {
                publishError(currentRequestId, destination)
            }
        }
    }

    fun clearRoute() {
        runOnMain {
            requestId++
            detachActiveRoute()
            activeSession?.cancel()
            activeSession = null
            activeListener = null
            publish(RouteSnapshot(requestId = requestId))
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

    private fun publishError(currentRequestId: Long, destination: Point) {
        activeSession = null
        activeListener = null
        publish(
            RouteSnapshot(
                requestId = currentRequestId,
                status = RouteStatus.ERROR,
                destination = destination,
                errorMessage = "Не удалось построить маршрут. Проверьте интернет.",
            ),
        )
    }

    private fun detachActiveRoute() {
        val route = activeRoute ?: return
        try {
            route.removeConditionsListener(conditionsListener)
        } catch (_: RuntimeException) {
            // The native route may already have been released.
        } catch (_: LinkageError) {
            // MapKit can be unavailable on unsupported devices.
        }
        activeRoute = null
    }

    private fun publish(next: RouteSnapshot) {
        snapshot = next
        listeners.toList().forEach { it.onRouteChanged(next) }
    }

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }
}
