package ru.gpsantiradar.app

import java.util.Collections
import java.util.TreeMap
import kotlin.math.*

/** Stable, world-anchored marker entities for a camera query. */
object MapMarkerLayout {
    const val INDIVIDUAL_ZOOM = 14f
    private const val TILE_SIZE = 256.0
    private const val CELL_PIXELS = 52.0

    fun create(cameras: List<CameraPoint?>?, zoom: Float): List<Entity> {
        val validCameras = validCameras(cameras)
        if (validCameras.isEmpty()) return emptyList()
        if (zoom >= INDIVIDUAL_ZOOM) return individualEntities(validCameras)
        val floorZoom = floor(zoom.toDouble()).toLong()
        val worldSize = TILE_SIZE * 2.0.pow(floorZoom.toDouble())
        val cells = TreeMap<String, MutableList<CameraPoint>>()
        validCameras.forEach { camera -> cells.getOrPut(clusterKey(camera, floorZoom, worldSize), ::ArrayList).add(camera) }
        return Collections.unmodifiableList(cells.map { (key, members) ->
            if (members.size == 1) individual(members[0]) else cluster(key, members)
        })
    }

    fun validCameras(cameras: List<CameraPoint?>?): List<CameraPoint> =
        if (cameras.isNullOrEmpty()) emptyList() else Collections.unmodifiableList(cameras.filterNotNull().filter(::isValid))

    private fun individualEntities(cameras: List<CameraPoint>): List<Entity> {
        val sorted = TreeMap<String, CameraPoint>()
        cameras.forEach { sorted["camera:${it.id}"] = it }
        return Collections.unmodifiableList(sorted.values.map(::individual))
    }

    private fun clusterKey(camera: CameraPoint, floorZoom: Long, worldSize: Double): String {
        val x = (camera.longitude + 180.0) / 360.0
        val radians = Math.toRadians(camera.latitude.coerceIn(-85.05112878, 85.05112878))
        val y = (1.0 - ln(tan(radians) + 1.0 / cos(radians)) / Math.PI) / 2.0
        val cellX = floor(x * worldSize / CELL_PIXELS).toLong()
        val cellY = floor(y * worldSize / CELL_PIXELS).toLong()
        return "cluster:$floorZoom:$cellX:$cellY"
    }

    private fun isValid(camera: CameraPoint) = camera.latitude.isFinite() && camera.longitude.isFinite() &&
        camera.latitude in -90.0..90.0 && camera.longitude in -180.0..180.0
    private fun individual(camera: CameraPoint) = Entity(
        "camera:${camera.id}", false, camera.latitude, camera.longitude, listOf(camera.id), camera
    )
    private fun cluster(key: String, members: MutableList<CameraPoint>): Entity {
        members.sortBy { it.id }
        val memberIds = members.map { it.id }.sorted()
        return Entity(key, true, members.sumOf { it.latitude } / members.size,
            members.sumOf { it.longitude } / members.size, memberIds, null)
    }

    class Entity internal constructor(
        val key: String,
        val cluster: Boolean,
        val latitude: Double,
        val longitude: Double,
        memberIds: List<Long>,
        val camera: CameraPoint?
    ) {
        val memberIds: List<Long> = Collections.unmodifiableList(ArrayList(memberIds))
        fun samePresentation(other: Entity?): Boolean = other != null && key == other.key && cluster == other.cluster &&
            latitude.compareTo(other.latitude) == 0 && longitude.compareTo(other.longitude) == 0 &&
            memberIds == other.memberIds && sameCameraPresentation(camera, other.camera)

        companion object {
            private fun sameCameraPresentation(left: CameraPoint?, right: CameraPoint?): Boolean = left === right ||
                left != null && right != null && left.id == right.id && left.latitude.compareTo(right.latitude) == 0 &&
                left.longitude.compareTo(right.longitude) == 0 && left.type == right.type && left.dirType == right.dirType &&
                left.direction.compareTo(right.direction) == 0 && left.distanceMeters == right.distanceMeters &&
                left.reverseDistanceMeters == right.reverseDistanceMeters && left.angleDegrees.compareTo(right.angleDegrees) == 0 &&
                left.rank.compareTo(right.rank) == 0 && left.newbie == right.newbie && left.speedRules == right.speedRules
        }
    }
}
