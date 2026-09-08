package ru.gpsantiradar.app

/** Computes camera marker lifecycle changes while keeping unchanged entities stable. */
object CameraMarkerDiff {
    fun between(rendered: Map<Long, CameraPoint>, requested: List<CameraPoint>): Result {
        val desired = HashMap<Long, CameraPoint>()
        requested.forEach { desired[it.id] = it }
        val removeIds = rendered.entries.filter { (id, current) ->
            val replacement = desired[id]
            replacement == null || !samePresentation(current, replacement)
        }.map { it.key }
        val addOrReplace = desired.values.filter { camera ->
            val current = rendered[camera.id]
            current == null || !samePresentation(current, camera)
        }
        return Result(removeIds, addOrReplace, desired)
    }

    private fun samePresentation(left: CameraPoint, right: CameraPoint) =
        left.id == right.id && left.latitude.compareTo(right.latitude) == 0 &&
            left.longitude.compareTo(right.longitude) == 0 && left.type == right.type &&
            left.dirType == right.dirType && left.direction.compareTo(right.direction) == 0 &&
            left.distanceMeters == right.distanceMeters && left.reverseDistanceMeters == right.reverseDistanceMeters &&
            left.angleDegrees.compareTo(right.angleDegrees) == 0 && left.rank.compareTo(right.rank) == 0 &&
            left.newbie == right.newbie && left.speedRules == right.speedRules

    class Result internal constructor(
        val removeIds: List<Long>,
        val addOrReplace: List<CameraPoint>,
        val desired: Map<Long, CameraPoint>
    ) { fun hasMarkerChanges() = removeIds.isNotEmpty() || addOrReplace.isNotEmpty() }
}
