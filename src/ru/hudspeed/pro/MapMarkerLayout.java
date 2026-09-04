package ru.gpsantiradar.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Stable, world-anchored marker entities for a camera query. */
public final class MapMarkerLayout {
    static final float INDIVIDUAL_ZOOM = 14f;
    private static final double TILE_SIZE = 256.0;
    private static final double CELL_PIXELS = 52.0;

    private MapMarkerLayout() {}

    public static List<Entity> create(List<CameraPoint> cameras, float zoom) {
        List<CameraPoint> validCameras = validCameras(cameras);
        if (validCameras.isEmpty()) return Collections.emptyList();

        if (zoom >= INDIVIDUAL_ZOOM) return individualEntities(validCameras);

        long floorZoom = (long) Math.floor(zoom);
        double worldSize = TILE_SIZE * Math.pow(2.0, floorZoom);
        Map<String, List<CameraPoint>> cells = new TreeMap<>();
        for (CameraPoint camera : validCameras) {
            String key = clusterKey(camera, floorZoom, worldSize);
            List<CameraPoint> members = cells.get(key);
            if (members == null) {
                members = new ArrayList<>();
                cells.put(key, members);
            }
            members.add(camera);
        }

        List<Entity> entities = new ArrayList<>();
        for (Map.Entry<String, List<CameraPoint>> entry : cells.entrySet()) {
            List<CameraPoint> members = entry.getValue();
            if (members.size() == 1) {
                CameraPoint camera = members.get(0);
                entities.add(individual(camera));
            } else {
                entities.add(cluster(entry.getKey(), members));
            }
        }
        return Collections.unmodifiableList(entities);
    }

    static List<CameraPoint> validCameras(List<CameraPoint> cameras) {
        if (cameras == null || cameras.isEmpty()) return Collections.emptyList();
        List<CameraPoint> result = new ArrayList<>();
        for (CameraPoint camera : cameras) {
            if (isValid(camera)) result.add(camera);
        }
        return Collections.unmodifiableList(result);
    }

    private static List<Entity> individualEntities(List<CameraPoint> cameras) {
        Map<String, CameraPoint> sorted = new TreeMap<>();
        for (CameraPoint camera : cameras) {
            sorted.put("camera:" + camera.id, camera);
        }
        List<Entity> entities = new ArrayList<>();
        for (CameraPoint camera : sorted.values()) entities.add(individual(camera));
        return Collections.unmodifiableList(entities);
    }

    private static String clusterKey(CameraPoint camera, long floorZoom, double worldSize) {
        double x = (camera.longitude + 180.0) / 360.0;
        double clamped = Math.max(-85.05112878, Math.min(85.05112878, camera.latitude));
        double radians = Math.toRadians(clamped);
        double y = (1.0 - Math.log(Math.tan(radians)
                + 1.0 / Math.cos(radians)) / Math.PI) / 2.0;
        long cellX = (long) Math.floor(x * worldSize / CELL_PIXELS);
        long cellY = (long) Math.floor(y * worldSize / CELL_PIXELS);
        return "cluster:" + floorZoom + ":" + cellX + ":" + cellY;
    }

    private static boolean isValid(CameraPoint camera) {
        return camera != null
                && Double.isFinite(camera.latitude)
                && Double.isFinite(camera.longitude)
                && camera.latitude >= -90.0 && camera.latitude <= 90.0
                && camera.longitude >= -180.0 && camera.longitude <= 180.0;
    }

    private static Entity individual(CameraPoint camera) {
        return new Entity("camera:" + camera.id, false, camera.latitude, camera.longitude,
                Collections.singletonList(camera.id), camera);
    }

    private static Entity cluster(String key, List<CameraPoint> members) {
        Collections.sort(members, new Comparator<CameraPoint>() {
            @Override public int compare(CameraPoint left, CameraPoint right) {
                return Long.compare(left.id, right.id);
            }
        });

        List<Long> memberIds = new ArrayList<>();
        double latitude = 0.0;
        double longitude = 0.0;
        for (CameraPoint member : members) {
            memberIds.add(member.id);
            latitude += member.latitude;
            longitude += member.longitude;
        }
        Collections.sort(memberIds);
        return new Entity(key, true, latitude / members.size(), longitude / members.size(),
                memberIds, null);
    }

    public static final class Entity {
        public final String key;
        public final boolean cluster;
        public final double latitude;
        public final double longitude;
        public final List<Long> memberIds;
        public final CameraPoint camera;

        private Entity(String key, boolean cluster, double latitude, double longitude,
                       List<Long> memberIds, CameraPoint camera) {
            this.key = key;
            this.cluster = cluster;
            this.latitude = latitude;
            this.longitude = longitude;
            this.memberIds = Collections.unmodifiableList(new ArrayList<>(memberIds));
            this.camera = camera;
        }

        public boolean samePresentation(Entity other) {
            return other != null
                    && key.equals(other.key)
                    && cluster == other.cluster
                    && Double.compare(latitude, other.latitude) == 0
                    && Double.compare(longitude, other.longitude) == 0
                    && memberIds.equals(other.memberIds)
                    && sameCameraPresentation(camera, other.camera);
        }

        private static boolean sameCameraPresentation(CameraPoint left, CameraPoint right) {
            return left == right || left != null && right != null
                    && left.id == right.id
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
    }
}
