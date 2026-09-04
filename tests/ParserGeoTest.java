package ru.gpsantiradar.app;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ParserGeoTest {
    public static void main(String[] args) throws Exception {
        String json = "{\"meta\":{\"ver\":\"3.0\",\"build\":3995,"
                + "\"exportDate\":\"2026-08-28T05:05:00Z\"},"
                + "\"objects\":["
                + "{\"id\":8,\"lat\":45.32478,\"lng\":37.72181,\"type\":1,"
                + "\"dirType\":1,\"dir\":165,\"distance\":400,\"revDistance\":250,"
                + "\"angle\":15,\"rank\":-0.275,\"isNewbie\":false,\"units\":\"kph\","
                + "\"speedControls\":[{\"speed\":110,\"from\":[],\"to\":[],"
                + "\"weekdays\":[],\"months\":[],\"vehicles\":[\"car\"],\"type\":\"current\"},"
                + "{\"speed\":90,\"from\":[],\"to\":[],\"weekdays\":[],\"months\":[],"
                + "\"vehicles\":[\"truck\"],\"type\":\"current\"}]},"
                + "{\"id\":9,\"lat\":55.7,\"lng\":37.6,\"type\":15,\"dirType\":0,"
                + "\"dir\":0,\"distance\":300,\"revDistance\":100,\"angle\":18,"
                + "\"speedControls\":[],\"units\":\"kph\",\"isNewbie\":false},"
                + "{\"id\":10,\"lat\":56.8,\"lng\":60.6,\"type\":16,\"dirType\":2,"
                + "\"dir\":90,\"distance\":500,\"revDistance\":450,\"angle\":10,"
                + "\"speedControls\":[],\"units\":\"kph\",\"isNewbie\":true},"
                + "{\"id\":11,\"lat\":56.8,\"lng\":60.61,\"type\":62,\"dirType\":0,"
                + "\"dir\":0,\"distance\":100,\"speedControls\":[]}] }";

        List<CameraPoint> points = new ArrayList<>();
        RadarBaseParser.Result parsed = RadarBaseParser.parse(new ByteArrayInputStream(
                json.getBytes(StandardCharsets.UTF_8)), points::add);
        check(parsed.count == 4 && points.size() == 4, "all RadarBase object types imported");
        CameraPoint stationary = points.get(0);
        check(stationary.id == 8 && stationary.type == 1, "RadarBase id and type");
        float expectedShift = coordinateShift(3995, 165f);
        check((float) stationary.latitude == (float) 45.32478 - expectedShift
                        && (float) stationary.longitude == (float) 37.72181 - expectedShift,
                "RadarBase coordinate correction");
        check(parsed.build != null && parsed.build == 3995 && parsed.coordinatesCorrected,
                "RadarBase build metadata");
        check(stationary.dirType == 1 && stationary.direction == 165,
                "RadarBase traffic direction");
        check(stationary.distanceMeters == 400 && stationary.reverseDistanceMeters == 250,
                "RadarBase distances");
        check(stationary.angleDegrees == 15, "RadarBase sector angle");
        check(stationary.currentSpeedLimit() == 110, "car-specific speed control");
        check(points.get(1).isObservation() && !points.get(1).isFake(),
                "explicit observation type");
        check(points.get(2).isFake() && points.get(2).hasReverseZone(),
                "fake and bidirectional type");
        check(points.get(2).typeName().equals("Муляж камеры"), "RadarBase type names");
        check(points.get(3).isRoadObject() && points.get(3).typeName().equals("Неровность"),
                "road object classification and name");

        String explicitUnknown = "{\"objects\":[{\"id\":12,\"lat\":55.0,\"lng\":37.0,"
                + "\"type\":0,\"dir\":0,\"speedControls\":[]}]}";
        List<CameraPoint> unknown = new ArrayList<>();
        RadarBaseParser.parse(new ByteArrayInputStream(
                explicitUnknown.getBytes(StandardCharsets.UTF_8)), unknown::add);
        check(unknown.size() == 1 && unknown.get(0).type == 0,
                "explicit type zero imported");

        String noBuild = "{\"meta\":{\"ver\":\"3.0\",\"build\":\"invalid\"},"
                + "\"objects\":[{\"id\":1,\"lat\":55.5,\"lng\":37.5,\"type\":1,"
                + "\"dir\":90,\"speedControls\":[]}]}";
        List<CameraPoint> uncorrected = new ArrayList<>();
        RadarBaseParser.Result noBuildResult = RadarBaseParser.parse(new ByteArrayInputStream(
                noBuild.getBytes(StandardCharsets.UTF_8)), uncorrected::add);
        check(noBuildResult.build == null && !noBuildResult.coordinatesCorrected,
                "invalid build disables correction");
        check(uncorrected.get(0).latitude == 55.5 && uncorrected.get(0).longitude == 37.5,
                "coordinates unchanged without build");

        boolean oldFormatRejected = false;
        try {
            RadarBaseParser.parse(new ByteArrayInputStream(
                    "2|Radars|1251\n18059|RU-4|51.6|39.1|".getBytes(StandardCharsets.UTF_8)),
                    point -> {});
        } catch (IOException expected) {
            oldFormatRejected = true;
        }
        check(oldFormatRejected, "old export format must not be supported");

        double distance = Geo.distanceMeters(56.8, 60.6, 56.8, 60.61);
        check(distance > 600 && distance < 650, "distance");
        check(Geo.angleDifference(350, 10) == 20, "angle wrap");
        check(StrelkaAlertAlgorithm.spokenDistance(951) == 1000
                        && StrelkaAlertAlgorithm.spokenDistance(950) == 900
                        && StrelkaAlertAlgorithm.spokenDistance(15) == 10,
                "Strelka distance voice thresholds");
        check(StrelkaAlertAlgorithm.beepVolume(300) == 1f
                        && StrelkaAlertAlgorithm.beepVolume(1000) == 0.05f,
                "Strelka beep volume ramp");

        CameraPoint directed = new CameraPoint();
        directed.id = 100;
        directed.dirType = 1;
        directed.direction = 0f;
        directed.distanceMeters = 500;
        directed.reverseDistanceMeters = 100;
        directed.angleDegrees = 20f;
        directed.speedRules = SpeedControlRules.encode(60, false,
                -1, -1, 0, 0, SpeedControlRules.CAR);
        check(StrelkaAlertAlgorithm.matchesZone(directed, 400, 0, 0, 800),
                "directional corridor accepts a straight approach");
        check(!StrelkaAlertAlgorithm.matchesZone(directed, 400, 0, 30, 800),
                "directional corridor rejects a side object");
        check(StrelkaAlertAlgorithm.mustDropImmediately(directed, 50, 10, 0, 180),
                "ordinary camera is dropped after passing");
        check(!StrelkaAlertAlgorithm.isOverspeeding(directed, 70f)
                        && StrelkaAlertAlgorithm.isOverspeeding(directed, 71f),
                "beeper starts only above the 10 km/h tolerance");

        CameraPoint rear = new CameraPoint();
        rear.id = 101;
        rear.dirType = 3;
        rear.direction = 0f;
        rear.distanceMeters = 500;
        rear.reverseDistanceMeters = 100;
        rear.angleDegrees = 20f;
        check(StrelkaAlertAlgorithm.matchesZone(rear, 50, 0, 180, 800)
                        && !StrelkaAlertAlgorithm.mustDropImmediately(rear, 50, 50, 0, 180),
                "rear-control zone continues after the object");
        check(StrelkaAlertAlgorithm.mustDropImmediately(rear, 50, 120, 0, 180),
                "rear-control zone ends at reverse distance");

        verifyStrelkaAlertLifecycle();
        verifyKnownReleaseHistory();
        verifyProcessLaunchGuard();
        verifyCameraMarkerDiff();
        verifyStableMapMarkerLayout();
        verifyMapMarkerEntityDiff();

        verifyMapMarkerPresentationEquality();
        if (args.length > 0) {
            final int[] count = {0};
            RadarBaseParser.Result real;
            try (FileInputStream input = new FileInputStream(args[0])) {
                real = RadarBaseParser.parse(input, point -> count[0]++);
            }
            check(real.count == count[0] && real.count > 150000, "real RadarBase count");
            check(real.schemaVersion.startsWith("3."), "real RadarBase schema");
            check(real.build != null && real.coordinatesCorrected,
                    "real RadarBase coordinates corrected");
            System.out.println("Real file: " + real.count + " objects");
        }
        System.out.println("ParserGeoTest: OK");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void verifyStrelkaAlertLifecycle() {
        CameraPoint object = new CameraPoint();
        object.id = 200;
        object.dirType = 0;
        object.distanceMeters = 500;
        StrelkaAlertTracker tracker = new StrelkaAlertTracker();

        StrelkaAlertTracker.Update update = tracker.update(Collections.singletonList(
                observation(object, 500, true, false)), 72, false);
        check(update.closestActive == null, "approach needs confidence");
        update = tracker.update(Collections.singletonList(
                observation(object, 480, true, false)), 72, false);
        check(update.closestActive == null, "approach is not announced too early");
        update = tracker.update(Collections.singletonList(
                observation(object, 450, true, false)), 72, false);
        check(update.pendingVoice != null, "confirmed entry requests one voice alert");
        tracker.markSpoken(object.id);

        update = tracker.update(Collections.singletonList(
                observation(object, 430, false, false)), 72, false);
        check(update.closestActive != null && update.pendingVoice == null,
                "brief zone loss keeps active alert without repeating voice");
        update = tracker.update(Collections.singletonList(
                observation(object, 410, true, false)), 72, false);
        check(update.closestActive != null && update.pendingVoice == null,
                "return to zone preserves spoken state");

        update = tracker.update(Collections.singletonList(
                observation(object, 20, false, true)), 72, false);
        check(update.closestActive == null && update.exited.size() == 1,
                "passing the object fully resets its state");

        tracker.update(Collections.singletonList(
                observation(object, 500, true, false)), 72, false);
        tracker.update(Collections.singletonList(
                observation(object, 480, true, false)), 72, false);
        update = tracker.update(Collections.singletonList(
                observation(object, 450, true, false)), 72, false);
        check(update.pendingVoice != null,
                "new confirmed approach after exit repeats the voice alert");
    }

    private static StrelkaAlertTracker.Observation observation(
            CameraPoint object, int distance, boolean matches, boolean drop) {
        return new StrelkaAlertTracker.Observation(object, distance,
                object.distanceMeters, matches, drop);
    }

    private static void verifyKnownReleaseHistory() {
        List<ReleaseHistory.Entry> releases = ReleaseHistory.entries();
        check(releases.size() == 5, "about dialog contains every known release");
        check(releases.get(0).version.equals("4.9.1")
                        && releases.get(1).version.equals("4.9.0")
                        && releases.get(2).version.equals("4.8.1")
                        && releases.get(3).version.equals("4.8.0")
                        && releases.get(4).version.equals("4.7.1"),
                "release history is newest first");
        for (ReleaseHistory.Entry release : releases) {
            check(release.changes != null && !release.changes.trim().isEmpty(),
                    "every release has a visible change description");
        }
    }

    private static void verifyProcessLaunchGuard() {
        ProcessLaunchGuard guard = new ProcessLaunchGuard();
        check(guard.claim(), "cold process launch starts the RadarBase update");
        check(!guard.claim(), "activity recreation does not repeat the startup update");
    }

    private static void verifyCameraMarkerDiff() {
        CameraPoint retained = marker(301, 56.80, 60.60, 1);
        CameraPoint leaving = marker(302, 56.81, 60.61, 2);
        Map<Long, CameraPoint> rendered = new HashMap<>();
        rendered.put(retained.id, retained);
        rendered.put(leaving.id, leaving);

        CameraPoint unchangedCopy = marker(301, 56.80, 60.60, 1);
        CameraPoint entering = marker(303, 56.82, 60.62, 3);
        CameraMarkerDiff.Result moved = CameraMarkerDiff.between(rendered,
                java.util.Arrays.asList(unchangedCopy, entering));
        check(moved.removeIds.size() == 1 && moved.removeIds.contains(302L),
                "marker leaving the buffered viewport is removed");
        check(moved.addOrReplace.size() == 1 && moved.addOrReplace.get(0).id == 303,
                "only marker entering the buffered viewport is added");

        CameraPoint changed = marker(301, 56.805, 60.60, 1);
        CameraMarkerDiff.Result databaseChanged = CameraMarkerDiff.between(rendered,
                java.util.Arrays.asList(changed, leaving));
        check(databaseChanged.removeIds.size() == 1
                        && databaseChanged.removeIds.contains(301L)
                        && databaseChanged.addOrReplace.size() == 1
                        && databaseChanged.addOrReplace.get(0).id == 301,
                "changed database object is replaced without rebuilding unchanged markers");
    }

    private static void verifyStableMapMarkerLayout() {
        List<CameraPoint> points = java.util.Arrays.asList(
                marker(401, 55.75000, 37.61000, 1),
                marker(402, 55.75005, 37.61005, 2),
                marker(403, 56.83000, 60.60000, 1),
                marker(404, 56.83005, 60.60005, 2));
        List<MapMarkerLayout.Entity> first = MapMarkerLayout.create(points, 10.4f);
        List<MapMarkerLayout.Entity> panned =
                MapMarkerLayout.create(new ArrayList<>(points), 10.4f);
        check(first.size() == 2, "nearby cameras form two stable clusters");
        check(first.get(0).key.equals(panned.get(0).key)
                        && first.get(1).key.equals(panned.get(1).key),
                "same zoom preserves world-anchored cluster keys");

        List<MapMarkerLayout.Entity> individual = MapMarkerLayout.create(
                Collections.singletonList(points.get(0)), 14f);
        check(individual.size() == 1 && !individual.get(0).cluster
                        && individual.get(0).key.equals("camera:401"),
                "zoom 14 displays individual cameras");

        CameraPoint invalid = marker(405, Double.NaN, 37.0, 1);
        check(MapMarkerLayout.create(Collections.singletonList(invalid), 10f).isEmpty(),
                "invalid coordinates are skipped");

        CameraPoint firstCentroidPoint = marker(409, 55.75, 37.61000, 1);
        firstCentroidPoint.latitude = Double.longBitsToDouble(4633042932285308929L);
        CameraPoint secondCentroidPoint = marker(410, 55.75, 37.61000, 1);
        secondCentroidPoint.latitude = Double.longBitsToDouble(4633042932285309051L);
        CameraPoint thirdCentroidPoint = marker(411, 55.75, 37.61000, 1);
        thirdCentroidPoint.latitude = Double.longBitsToDouble(4633042933519876818L);
        List<CameraPoint> exactCentroidOrder = java.util.Arrays.asList(
                firstCentroidPoint, secondCentroidPoint, thirdCentroidPoint);
        List<MapMarkerLayout.Entity> exactOrdered =
                MapMarkerLayout.create(exactCentroidOrder, 10.4f);
        List<MapMarkerLayout.Entity> exactPermuted = MapMarkerLayout.create(
                java.util.Arrays.asList(thirdCentroidPoint, secondCentroidPoint,
                        firstCentroidPoint), 10.4f);
        check(exactOrdered.size() == 1
                        && exactOrdered.get(0).samePresentation(exactPermuted.get(0)),
                "permuted exact cluster inputs preserve the centroid presentation");
    }


    private static void verifyMapMarkerPresentationEquality() {
        CameraPoint camera = marker(420, 55.75000, 37.61000, 1);
        camera.rank = 2.5f;
        camera.newbie = true;
        MapMarkerLayout.Entity baseline = individualEntity(camera);
        check(baseline.samePresentation(individualEntity(copyMarker(camera))),
                "equivalent individual entities share a presentation");

        CameraPoint changed = copyMarker(camera);
        changed.id = 421;
        check(!baseline.samePresentation(individualEntity(changed)), "camera ID changes presentation");
        changed = copyMarker(camera);
        changed.latitude = 55.75001;
        check(!baseline.samePresentation(individualEntity(changed)), "latitude changes presentation");
        changed = copyMarker(camera);
        changed.longitude = 37.61001;
        check(!baseline.samePresentation(individualEntity(changed)), "longitude changes presentation");
        changed = copyMarker(camera);
        changed.type = 2;
        check(!baseline.samePresentation(individualEntity(changed)), "type changes presentation");
        changed = copyMarker(camera);
        changed.dirType = 2;
        check(!baseline.samePresentation(individualEntity(changed)), "direction type changes presentation");
        changed = copyMarker(camera);
        changed.direction = 91f;
        check(!baseline.samePresentation(individualEntity(changed)), "direction changes presentation");
        changed = copyMarker(camera);
        changed.distanceMeters = 501;
        check(!baseline.samePresentation(individualEntity(changed)), "distance changes presentation");
        changed = copyMarker(camera);
        changed.reverseDistanceMeters = 101;
        check(!baseline.samePresentation(individualEntity(changed)),
                "reverse distance changes presentation");
        changed = copyMarker(camera);
        changed.angleDegrees = 21f;
        check(!baseline.samePresentation(individualEntity(changed)), "angle changes presentation");
        changed = copyMarker(camera);
        changed.rank = 3f;
        check(!baseline.samePresentation(individualEntity(changed)), "rank changes presentation");
        changed = copyMarker(camera);
        changed.newbie = false;
        check(!baseline.samePresentation(individualEntity(changed)), "newbie changes presentation");
        changed = copyMarker(camera);
        changed.speedRules = "70";
        check(!baseline.samePresentation(individualEntity(changed)), "speed rules change presentation");
    }

    private static void verifyMapMarkerEntityDiff() {
        List<CameraPoint> initialPoints = java.util.Arrays.asList(
                marker(401, 55.75000, 37.61000, 1),
                marker(402, 55.75005, 37.61005, 2),
                marker(403, 56.83000, 60.60000, 1),
                marker(404, 56.83005, 60.60005, 2));
        Map<String, MapMarkerLayout.Entity> rendered = entitiesByKey(
                MapMarkerLayout.create(initialPoints, 10.4f));
        String yekaterinburgKey = entityKeyContaining(rendered, 403L);
        List<CameraPoint> changedPoints = new ArrayList<>(initialPoints);
        changedPoints.add(marker(405, 55.75003, 37.61003, 3));
        MapMarkerEntityDiff.Result diff = MapMarkerEntityDiff.between(rendered,
                MapMarkerLayout.create(changedPoints, 10.4f));
        check(diff.removeKeys.isEmpty() && diff.add.isEmpty() && diff.update.size() == 1,
                "new member updates only its existing cluster");
        check(diff.update.get(0).memberIds.contains(405L),
                "updated cluster contains the entering camera");
        check(!diff.update.get(0).key.equals(yekaterinburgKey),
                "Yekaterinburg cluster remains unchanged");

        rendered = entitiesByKey(MapMarkerLayout.create(initialPoints, 14f));
        changedPoints = new ArrayList<>(initialPoints);
        changedPoints.set(0, marker(401, 55.75010, 37.61000, 1));
        diff = MapMarkerEntityDiff.between(rendered,
                MapMarkerLayout.create(changedPoints, 14f));
        check(diff.removeKeys.isEmpty() && diff.add.isEmpty() && diff.update.size() == 1,
                "moving an individual camera updates its stable entity");
        check(diff.update.get(0).key.equals("camera:401"),
                "moved camera preserves its individual key");
    }

    private static Map<String, MapMarkerLayout.Entity> entitiesByKey(
            List<MapMarkerLayout.Entity> entities) {
        Map<String, MapMarkerLayout.Entity> byKey = new LinkedHashMap<>();
        for (MapMarkerLayout.Entity entity : entities) byKey.put(entity.key, entity);
        return byKey;
    }

    private static String entityKeyContaining(Map<String, MapMarkerLayout.Entity> entities,
                                              long memberId) {
        for (MapMarkerLayout.Entity entity : entities.values()) {
            if (entity.memberIds.contains(memberId)) return entity.key;
        }
        throw new AssertionError("missing member " + memberId);
    }
    private static MapMarkerLayout.Entity individualEntity(CameraPoint camera) {
        return MapMarkerLayout.create(Collections.singletonList(camera), 14f).get(0);
    }

    private static CameraPoint copyMarker(CameraPoint source) {
        CameraPoint copy = new CameraPoint();
        copy.id = source.id;
        copy.latitude = source.latitude;
        copy.longitude = source.longitude;
        copy.type = source.type;
        copy.dirType = source.dirType;
        copy.direction = source.direction;
        copy.distanceMeters = source.distanceMeters;
        copy.reverseDistanceMeters = source.reverseDistanceMeters;
        copy.angleDegrees = source.angleDegrees;
        copy.rank = source.rank;
        copy.newbie = source.newbie;
        copy.speedRules = source.speedRules;
        return copy;
    }

    private static CameraPoint marker(long id, double latitude, double longitude, int type) {
        CameraPoint point = new CameraPoint();
        point.id = id;
        point.latitude = latitude;
        point.longitude = longitude;
        point.type = type;
        point.dirType = 1;
        point.direction = 90f;
        point.distanceMeters = 500;
        point.reverseDistanceMeters = 100;
        point.angleDegrees = 20f;
        point.speedRules = "60";
        return point;
    }

    private static float coordinateShift(int build, float direction) {
        int phase = build * build;
        phase *= 3;
        float key = (float) (Math.sin((double) build) * Math.cos((double) phase)
                / 180.0 * 0.2);
        return (direction + 360f) * key;
    }
}
