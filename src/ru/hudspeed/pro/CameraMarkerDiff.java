package ru.gpsantiradar.app;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class CameraMarkerDiff {
    private CameraMarkerDiff() {}

    public static Result between(Map<Long, CameraPoint> rendered,
                                 List<CameraPoint> requested) {
        Map<Long, CameraPoint> desired = new HashMap<>();
        for (CameraPoint camera : requested) desired.put(camera.id, camera);

        List<Long> removeIds = new ArrayList<>();
        for (Map.Entry<Long, CameraPoint> entry : rendered.entrySet()) {
            CameraPoint replacement = desired.get(entry.getKey());
            if (replacement == null || !samePresentation(entry.getValue(), replacement)) {
                removeIds.add(entry.getKey());
            }
        }

        List<CameraPoint> addOrReplace = new ArrayList<>();
        for (CameraPoint camera : desired.values()) {
            CameraPoint current = rendered.get(camera.id);
            if (current == null || !samePresentation(current, camera)) {
                addOrReplace.add(camera);
            }
        }
        return new Result(removeIds, addOrReplace, desired);
    }

    private static boolean samePresentation(CameraPoint left, CameraPoint right) {
        return left.id == right.id
                && Double.compare(left.latitude, right.latitude) == 0
                && Double.compare(left.longitude, right.longitude) == 0
                && left.type == right.type
                && left.dirType == right.dirType
                && Float.compare(left.direction, right.direction) == 0
                && left.distanceMeters == right.distanceMeters
                && left.reverseDistanceMeters == right.reverseDistanceMeters
                && Float.compare(left.angleDegrees, right.angleDegrees) == 0
                && Float.compare(left.rank, right.rank) == 0
                && left.newbie == right.newbie
                && Objects.equals(left.speedRules, right.speedRules);
    }

    public static final class Result {
        public final List<Long> removeIds;
        public final List<CameraPoint> addOrReplace;
        public final Map<Long, CameraPoint> desired;

        Result(List<Long> removeIds, List<CameraPoint> addOrReplace,
               Map<Long, CameraPoint> desired) {
            this.removeIds = removeIds;
            this.addOrReplace = addOrReplace;
            this.desired = desired;
        }

        public boolean hasMarkerChanges() {
            return !removeIds.isEmpty() || !addOrReplace.isEmpty();
        }
    }
}
