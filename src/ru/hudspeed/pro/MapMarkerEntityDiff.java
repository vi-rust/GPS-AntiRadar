package ru.gpsantiradar.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Computes marker lifecycle changes without replacing stable map entities. */
public final class MapMarkerEntityDiff {
    private MapMarkerEntityDiff() {}

    public static Result between(
            Map<String, MapMarkerLayout.Entity> rendered,
            List<MapMarkerLayout.Entity> requested) {
        Map<String, MapMarkerLayout.Entity> desired = new LinkedHashMap<>();
        for (MapMarkerLayout.Entity entity : requested) desired.put(entity.key, entity);

        List<String> removeKeys = new ArrayList<>();
        for (String key : rendered.keySet()) {
            if (!desired.containsKey(key)) removeKeys.add(key);
        }
        Collections.sort(removeKeys);

        List<MapMarkerLayout.Entity> add = new ArrayList<>();
        List<MapMarkerLayout.Entity> update = new ArrayList<>();
        for (MapMarkerLayout.Entity entity : desired.values()) {
            MapMarkerLayout.Entity current = rendered.get(entity.key);
            if (current == null) {
                add.add(entity);
            } else if (!current.samePresentation(entity)) {
                update.add(entity);
            }
        }
        return new Result(removeKeys, add, update, desired);
    }

    public static final class Result {
        public final List<String> removeKeys;
        public final List<MapMarkerLayout.Entity> add;
        public final List<MapMarkerLayout.Entity> update;
        public final Map<String, MapMarkerLayout.Entity> desired;

        Result(List<String> removeKeys, List<MapMarkerLayout.Entity> add,
               List<MapMarkerLayout.Entity> update,
               Map<String, MapMarkerLayout.Entity> desired) {
            this.removeKeys = Collections.unmodifiableList(new ArrayList<>(removeKeys));
            this.add = Collections.unmodifiableList(new ArrayList<>(add));
            this.update = Collections.unmodifiableList(new ArrayList<>(update));
            this.desired = Collections.unmodifiableMap(new LinkedHashMap<>(desired));
        }
    }
}
