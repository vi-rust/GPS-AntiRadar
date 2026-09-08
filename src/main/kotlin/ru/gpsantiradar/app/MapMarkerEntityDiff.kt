package ru.gpsantiradar.app

import java.util.Collections
import java.util.LinkedHashMap

/** Computes marker lifecycle changes without replacing stable map entities. */
object MapMarkerEntityDiff {
    fun between(
        rendered: Map<String, MapMarkerLayout.Entity>, requested: List<MapMarkerLayout.Entity>
    ): Result {
        val desired = LinkedHashMap<String, MapMarkerLayout.Entity>()
        requested.forEach { desired[it.key] = it }
        val removeKeys = rendered.keys.filterNot(desired::containsKey).sorted()
        val add = ArrayList<MapMarkerLayout.Entity>()
        val update = ArrayList<MapMarkerLayout.Entity>()
        desired.values.forEach { entity ->
            val current = rendered[entity.key]
            if (current == null) add += entity else if (!current.samePresentation(entity)) update += entity
        }
        return Result(removeKeys, add, update, desired)
    }

    class Result internal constructor(
        removeKeys: List<String>, add: List<MapMarkerLayout.Entity>, update: List<MapMarkerLayout.Entity>,
        desired: Map<String, MapMarkerLayout.Entity>
    ) {
        val removeKeys = Collections.unmodifiableList(ArrayList(removeKeys))
        val add = Collections.unmodifiableList(ArrayList(add))
        val update = Collections.unmodifiableList(ArrayList(update))
        val desired = Collections.unmodifiableMap(LinkedHashMap(desired))
    }
}
