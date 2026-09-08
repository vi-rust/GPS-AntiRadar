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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class ParserGeoTest {
    public static void main(String[] args) throws Exception {
        verifyCarSurfaceSpec();
        verifyCarStartupDecision();
        verifyThemeResolution();
        verifyMapVisualStyle();
        verifyMapMarkerHitTest();
        verifyCameraHintFormatter();
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
        check(StrelkaAlertAlgorithm.matchesZone(directed, 400, 0, 0),
                "directional corridor accepts a straight approach");
        check(!StrelkaAlertAlgorithm.matchesZone(directed, 400, 0, 30),
                "directional corridor rejects a side object");
        CameraPoint missingDistance = new CameraPoint();
        missingDistance.id = 102;
        missingDistance.dirType = 0;
        check(!StrelkaAlertAlgorithm.matchesZone(missingDistance, 1, 0, 0),
                "objects without database distance never enter the alert zone");
        check(StrelkaAlertAlgorithm.mustDropImmediately(directed, 50, 10, 0, 180),
                "ordinary camera is dropped after passing");
        check(!StrelkaAlertAlgorithm.isOverspeeding(directed, 70f)
                        && StrelkaAlertAlgorithm.isOverspeeding(directed, 71f),
                "beeper starts only above the 10 km/h tolerance");
        check(AppSettings.clampOverspeedThreshold(-1) == 0
                        && AppSettings.clampOverspeedThreshold(7) == 7
                        && AppSettings.clampOverspeedThreshold(21) == 20,
                "beep overspeed tolerance is clamped to the menu range");
        check(!StrelkaAlertAlgorithm.isOverspeeding(directed, 60f, 0)
                        && StrelkaAlertAlgorithm.isOverspeeding(directed, 60.1f, 0)
                        && !StrelkaAlertAlgorithm.isOverspeeding(directed, 80f, 20)
                        && StrelkaAlertAlgorithm.isOverspeeding(directed, 80.1f, 20),
                "beeper uses the configured 0 to 20 km/h tolerance");

        CameraPoint rear = new CameraPoint();
        rear.id = 101;
        rear.dirType = 3;
        rear.direction = 0f;
        rear.distanceMeters = 500;
        rear.reverseDistanceMeters = 100;
        rear.angleDegrees = 20f;
        check(StrelkaAlertAlgorithm.matchesZone(rear, 50, 0, 180)
                        && !StrelkaAlertAlgorithm.mustDropImmediately(rear, 50, 50, 0, 180),
                "rear-control zone continues after the object");
        check(StrelkaAlertAlgorithm.mustDropImmediately(rear, 50, 120, 0, 180),
                "rear-control zone ends at reverse distance");

        verifyStrelkaAlertLifecycle();
        verifyMultiObjectOverspeedSelection();
        verifyRadarScanRecoveryGate();
        verifyKnownReleaseHistory();
        verifyProcessLaunchGuard();
        verifyMapKitLifecycle();
        verifyRadarBaseUpdateSingleFlight();
        verifyRadarBaseUpdateStates();
        verifyRadarBaseUpdateListenerRegistry();
        verifyCameraMarkerDiff();
        verifyStableMapMarkerLayout();
        verifyMapMarkerEntityDiff();
        verifyMapOrientation();
        verifyHeadingChannels();

        verifyMapMarkerPresentationEquality();
        verifyDrivingSnapshotAndHud();
        verifyCarMenuItems();
        check(AppSettings.adjustOverspeedThreshold(0, -1) == 0
                        && AppSettings.adjustOverspeedThreshold(20, 1) == 20,
                "overspeed threshold uses 0..20 with a 1 km/h step");
        check(AppSettings.adjustHudTransparency(0, -1) == 0
                        && AppSettings.adjustHudTransparency(75, 1) == 80
                        && AppSettings.adjustHudTransparency(80, 1) == 80,
                "HUD transparency uses 0..80 with a 5 percent step");
        check(!AppSettings.DEFAULT_AUTO_ROTATE_MAP,
                "map auto-rotation is disabled by default");
        check(AppSettings.clampHudTransparency(-5) == 0
                        && AppSettings.clampHudTransparency(12) == 10
                        && AppSettings.clampHudTransparency(95) == 80
                        && AppSettings.adjustHudTransparency(12, 1) == 15
                        && AppSettings.adjustHudTransparency(12, -1) == 5
                        && AppSettings.adjustHudTransparency(90, -1) == 75,
                "HUD transparency floors to its step grid before adjustment");
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

    private static void verifyMapVisualStyle() {
        MapVisualStyle.Coverage normal = MapVisualStyle.coverage(
                0xFFDC3730, 2481003L, -1L);
        check(normal.fillColor == 0x26DC3730,
                "normal zone fill preserves its type color at 85 percent transparency");
        check(normal.strokeColor == 0x4DDC3730,
                "normal zone border preserves its type color at 70 percent transparency");

        MapVisualStyle.Coverage active = MapVisualStyle.coverage(
                0xFFDC3730, 2481003L, 2481003L);
        check(active.fillColor == 0x4DDC3730,
                "active zone fill preserves its type color at 70 percent transparency");
        check(active.strokeColor == 0x73DC3730,
                "active zone border preserves its type color at 55 percent transparency");

        MapVisualStyle.Coverage other = MapVisualStyle.coverage(
                0xFFDC3730, 2481004L, 2481003L);
        check(other.fillColor == normal.fillColor && other.strokeColor == normal.strokeColor,
                "only the currently controlled object receives active zone colors");
        MapVisualStyle.Coverage blue = MapVisualStyle.coverage(
                0xFF236FDC, 2481004L, 2481003L);
        check(blue.fillColor == 0x26236FDC && blue.strokeColor == 0x4D236FDC,
                "each inactive zone keeps the original color of its object type");
        check(MapVisualStyle.locationPrimaryColor(false) == 0xFF1976D2
                        && MapVisualStyle.locationHighlightColor(false) == 0xFF64B5F6,
                "day current-location arrow uses the blue palette");
        check(MapVisualStyle.locationPrimaryColor(true) == 0xFFFFBE00
                        && MapVisualStyle.locationHighlightColor(true) == 0xFFFFD848,
                "night current-location arrow restores the original yellow palette");
    }

    private static void verifyCarStartupDecision() {
        CarStartupDecision ready = CarStartupDecision.from(true, true);
        check(ready.startTracking && ready.showMap,
                "car starts tracking and shows the map when setup is ready");
        CarStartupDecision mapMissing = CarStartupDecision.from(true, false);
        check(mapMissing.startTracking && !mapMissing.showMap,
                "car keeps radar tracking active when only MapKit is missing");
        CarStartupDecision locationMissing = CarStartupDecision.from(false, true);
        check(!locationMissing.startTracking && !locationMissing.showMap,
                "car does not start tracking without fine location permission");
    }

    private static void verifyMapMarkerHitTest() {
        CameraPoint farther = new CameraPoint();
        farther.id = 10L;
        CameraPoint nearer = new CameraPoint();
        nearer.id = 11L;
        List<MapMarkerHitTest.Candidate> candidates = new ArrayList<>();
        candidates.add(new MapMarkerHitTest.Candidate(farther, 120f, 100f));
        candidates.add(new MapMarkerHitTest.Candidate(nearer, 106f, 102f));

        check(MapMarkerHitTest.nearest(100f, 100f, 28f, candidates) == nearer,
                "marker hit test selects the nearest individual camera");
        check(MapMarkerHitTest.nearest(200f, 200f, 28f, candidates) == null,
                "marker hit test rejects taps outside the marker radius");
    }

    private static void verifyCameraHintFormatter() {
        CameraPoint camera = new CameraPoint();
        camera.type = 1;
        camera.dirType = 1;
        camera.distanceMeters = 650;
        camera.speedRules = SpeedControlRules.encode(
                80, false, -1, -1, 0, 0, SpeedControlRules.CAR);
        String hint = CameraHintFormatter.format(camera);
        check(hint.contains(camera.typeName()) && hint.contains("80 км/ч")
                        && hint.contains("Зона контроля: 650 м")
                        && hint.contains(camera.directionName()),
                "phone and car camera hints share all control details");
    }

    private static void verifyThemeResolution() {
        long equinoxNoonUtc = java.time.Instant.parse("2026-03-20T12:00:00Z").toEpochMilli();
        long equinoxMidnightUtc = java.time.Instant.parse("2026-03-20T00:00:00Z").toEpochMilli();
        check(!ThemeResolver.isDark(ThemeMode.LIGHT, equinoxMidnightUtc,
                        0.0, 0.0, java.util.TimeZone.getTimeZone("UTC")),
                "manual light theme overrides nighttime");
        check(ThemeResolver.isDark(ThemeMode.DARK, equinoxNoonUtc,
                        0.0, 0.0, java.util.TimeZone.getTimeZone("UTC")),
                "manual dark theme overrides daylight");
        check(!ThemeResolver.isDark(ThemeMode.AUTOMATIC, equinoxNoonUtc,
                        0.0, 0.0, java.util.TimeZone.getTimeZone("UTC"))
                        && ThemeResolver.isDark(ThemeMode.AUTOMATIC, equinoxMidnightUtc,
                        0.0, 0.0, java.util.TimeZone.getTimeZone("UTC")),
                "automatic theme follows solar daylight at the equator");

        long tromsoSummerMidnight =
                java.time.Instant.parse("2026-06-20T22:00:00Z").toEpochMilli();
        long tromsoWinterNoon =
                java.time.Instant.parse("2026-12-21T11:00:00Z").toEpochMilli();
        check(!ThemeResolver.isDark(ThemeMode.AUTOMATIC, tromsoSummerMidnight,
                        69.6492, 18.9553, java.util.TimeZone.getTimeZone("Europe/Oslo"))
                        && ThemeResolver.isDark(ThemeMode.AUTOMATIC, tromsoWinterNoon,
                        69.6492, 18.9553, java.util.TimeZone.getTimeZone("Europe/Oslo")),
                "automatic theme handles polar day and polar night");

        long fallbackNight = java.time.Instant.parse("2026-03-20T21:00:00Z").toEpochMilli();
        long fallbackDay = java.time.Instant.parse("2026-03-20T08:00:00Z").toEpochMilli();
        check(ThemeResolver.isDark(ThemeMode.AUTOMATIC, fallbackNight,
                        Double.NaN, Double.NaN, java.util.TimeZone.getTimeZone("UTC"))
                        && !ThemeResolver.isDark(ThemeMode.AUTOMATIC, fallbackDay,
                        Double.NaN, Double.NaN, java.util.TimeZone.getTimeZone("UTC")),
                "automatic theme uses the 20:00 to 07:00 fallback before GPS");
        check(ThemeMode.fromStored("unexpected") == ThemeMode.AUTOMATIC,
                "unknown stored theme defaults to automatic");
    }

    private static void verifyDrivingSnapshotAndHud() {
        check(java.lang.reflect.Modifier.isFinal(DrivingSnapshot.class.getModifiers()),
                "driving snapshot type is immutable");
        for (java.lang.reflect.Field field : DrivingSnapshot.class.getFields()) {
            check(java.lang.reflect.Modifier.isFinal(field.getModifiers()),
                    "driving snapshot field is immutable: " + field.getName());
        }

        DrivingSnapshot idle = DrivingSnapshot.idle();
        check(!idle.hasLocation() && !idle.hasObject()
                        && Float.isNaN(idle.accuracyMeters)
                        && idle.cameraId == -1L && idle.distanceMeters == -1,
                "idle driving snapshot has no location or object");
        DrivingHudPresentation idleHud = DrivingHudPresentation.from(idle);
        check(idleHud.speedText.equals("0") && idleHud.distanceText.equals("—")
                        && idleHud.cameraText.equals("Объектов впереди нет")
                        && idleHud.speedColor == DrivingHudPresentation.COLOR_GREEN
                        && !idleHud.hasActiveObject,
                "idle HUD uses the shared empty state");

        DrivingSnapshot insideZone = new DrivingSnapshot(64.6f, 3.5f, 250,
                "Камера", 42L, 80, 800, 56.84, 60.61, 123f,
                "inside", "diagnostic");
        check(insideZone.hasLocation() && insideZone.hasObject()
                        && insideZone.accuracyMeters == 3.5f
                        && insideZone.cameraId == 42L
                        && insideZone.headingDegrees == 123f
                        && insideZone.alertState.equals("inside")
                        && insideZone.alertAlgorithm.equals("diagnostic"),
                "active driving snapshot preserves shared movement state");
        DrivingHudPresentation insideHud = hud(insideZone, 10);
        check(insideHud.speedText.equals("65") && insideHud.distanceText.equals("250 м")
                        && insideHud.cameraText.equals("Камера  ·  80 км/ч")
                        && insideHud.speedColor == DrivingHudPresentation.COLOR_GREEN
                        && insideHud.hasActiveObject
                        && !insideHud.cameraText.contains("diagnostic"),
                "inside-zone HUD stays green below the speed limit");

        DrivingHudPresentation farHud = hud(new DrivingSnapshot(
                120f, Float.NaN, 1250, "Камера", 43L, 80, 800,
                Double.NaN, Double.NaN, Float.NaN, "", ""), 10);
        check(farHud.distanceText.equals("1.3 км")
                        && farHud.speedColor == DrivingHudPresentation.COLOR_GREEN,
                "HUD stays green outside the object visibility zone");

        DrivingHudPresentation unrestrictedHud = hud(new DrivingSnapshot(
                120f, Float.NaN, 800, "Опасный участок", 44L, 0, 800,
                Double.NaN, Double.NaN, Float.NaN, "", ""), 10);
        check(unrestrictedHud.speedColor == DrivingHudPresentation.COLOR_GREEN,
                "inside-zone HUD stays green without a speed limit");

        DrivingHudPresentation atLimitHud = hud(new DrivingSnapshot(
                80f, Float.NaN, 800, "Камера", 45L, 80, 800,
                Double.NaN, Double.NaN, Float.NaN, "", ""), 5);
        check(atLimitHud.speedColor == DrivingHudPresentation.COLOR_ALERT,
                "inside-zone HUD turns yellow at the speed limit");

        DrivingHudPresentation withinThresholdHud = hud(new DrivingSnapshot(
                84.9f, Float.NaN, 800, "Камера", 46L, 80, 800,
                Double.NaN, Double.NaN, Float.NaN, "", ""), 5);
        check(withinThresholdHud.speedColor == DrivingHudPresentation.COLOR_ALERT,
                "inside-zone HUD stays yellow below the configured threshold boundary");

        DrivingHudPresentation atThresholdHud = hud(new DrivingSnapshot(
                85f, Float.NaN, 800, "Камера", 47L, 80, 800,
                Double.NaN, Double.NaN, Float.NaN, "", ""), 5);
        check(atThresholdHud.speedColor == DrivingHudPresentation.COLOR_OVERSPEED,
                "inside-zone HUD turns red at the configured threshold boundary");
    }

    private static DrivingHudPresentation hud(DrivingSnapshot snapshot, int thresholdKmh) {
        return DrivingHudPresentation.from(snapshot, thresholdKmh);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void verifyCarMenuItems() {
        check(java.util.Arrays.equals(CarMenuItem.values(), new CarMenuItem[] {
                        CarMenuItem.UPDATE_DATABASE,
                        CarMenuItem.OVERSPEED_THRESHOLD,
                        CarMenuItem.HUD_TRANSPARENCY,
                        CarMenuItem.AUTO_ROTATE_MAP,
                        CarMenuItem.THEME,
                        CarMenuItem.MAPKIT_KEY,
                        CarMenuItem.ABOUT,
                        CarMenuItem.EXIT
                }),
                "car menu exposes all eight actions in display order");
    }

    private static void verifyMapOrientation() {
        check(MapOrientation.stableHeading(45f, 370f, 2.9f) == 45f,
                "stationary GPS jitter must not rotate the location arrow");
        check(MapOrientation.stableHeading(45f, 370f, 3f) == 10f,
                "movement heading is normalized for the location arrow");
        check(MapOrientation.stableHeading(45f, Float.NaN, 30f) == 45f,
                "missing movement heading preserves the last arrow direction");
        check(MapOrientation.cameraAzimuth(false, 50f, 120f, 25f) == 25f,
                "disabled auto-rotation preserves the map azimuth");
        check(MapOrientation.cameraAzimuth(true, 2.9f, 120f, 25f) == 25f,
                "auto-rotation ignores stationary heading jitter");
        check(MapOrientation.cameraAzimuth(true, 3f, 120f, 25f) == 120f,
                "enabled auto-rotation follows the movement heading");
    }

    private static void verifyHeadingChannels() {
        HeadingSelection turn = HeadingSelection.forStrelka(
                90f, 0f, 45f, true, true);
        check(turn.visualHeading == 90f,
                "visual heading must follow a turn without radar smoothing delay");
        check(turn.alertHeading == 45f,
                "alert heading keeps the Strelka smoothing used for zone matching");

        HeadingSelection betweenScans = HeadingSelection.forStrelka(
                90f, 0f, 0f, false, true);
        check(betweenScans.visualHeading == 90f && betweenScans.alertHeading == 0f,
                "visual and alert headings stay independent between radar scans");
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

    private static void verifyMultiObjectOverspeedSelection() {
        CameraPoint noLimit = marker(501, 55.75, 37.61, 1);
        noLimit.speedRules = "";
        CameraPoint limited = marker(502, 55.751, 37.611, 1);
        limited.speedRules = SpeedControlRules.encode(60, false,
                -1, -1, 0, 0, SpeedControlRules.CAR);
        StrelkaAlertTracker tracker = activeTracker(java.util.Arrays.asList(
                observation(noLimit, 100, true, false),
                observation(limited, 200, true, false)), 90f);
        StrelkaAlertTracker.State overspeed = tracker.snapshot().overspeedCandidate(90f);
        check(overspeed != null && overspeed.object.id == limited.id,
                "a farther limited object requests a beep when the nearest has no limit");

        CameraPoint lowerId = marker(513, 55.752, 37.612, 1);
        lowerId.speedRules = SpeedControlRules.encode(50, false,
                -1, -1, 0, 0, SpeedControlRules.CAR);
        CameraPoint higherId = marker(529, 55.753, 37.613, 1);
        higherId.speedRules = SpeedControlRules.encode(60, false,
                -1, -1, 0, 0, SpeedControlRules.CAR);
        StrelkaAlertTracker.Observation lower = observation(lowerId, 150, true, false);
        StrelkaAlertTracker.Observation higher = observation(higherId, 150, true, false);
        long forward = activeTracker(java.util.Arrays.asList(higher, lower), 90f)
                .snapshot().overspeedCandidate(90f).object.id;
        long reversed = activeTracker(java.util.Arrays.asList(lower, higher), 90f)
                .snapshot().overspeedCandidate(90f).object.id;
        check(forward == lowerId.id && reversed == lowerId.id,
                "equal-distance overspeed selection is stable across input order");

        CameraPoint highLimit = marker(530, 55.754, 37.614, 1);
        highLimit.speedRules = SpeedControlRules.encode(100, false,
                -1, -1, 0, 0, SpeedControlRules.CAR);
        tracker = activeTracker(java.util.Arrays.asList(
                observation(highLimit, 90, true, false),
                observation(limited, 200, true, false)), 90f);
        overspeed = tracker.snapshot().overspeedCandidate(90f);
        check(overspeed != null && overspeed.object.id == limited.id,
                "a farther object requests a beep when the nearest limit is higher");
        check(tracker.snapshot().overspeedCandidate(80f, 20) == null
                        && tracker.snapshot().overspeedCandidate(81f, 20) != null,
                "multi-object beep selection uses the configured tolerance");
    }

    private static StrelkaAlertTracker activeTracker(
            List<StrelkaAlertTracker.Observation> observations, float speedKmh) {
        StrelkaAlertTracker tracker = new StrelkaAlertTracker();
        StrelkaAlertTracker.Update update = tracker.update(observations, speedKmh, true);
        check(update.activeCount == observations.size(), "test objects become active");
        for (StrelkaAlertTracker.Observation item : observations) {
            tracker.markSpoken(item.object.id);
        }
        return tracker;
    }

    private static void verifyRadarScanRecoveryGate() {
        RadarScanGate gate = new RadarScanGate();
        RadarScanGate.Decision initial = gate.assess(
                1_000L, true, 5f, Double.MAX_VALUE);
        check(initial.scanRequested && !initial.gpsRecovered,
                "the initial accurate fix requests a normal scan");
        gate.accept(initial);

        RadarScanGate.Decision poorAccuracy = gate.assess(
                8_000L, true, 80f, 100d);
        check(!poorAccuracy.scanRequested,
                "a poor-accuracy fix does not request a radar scan");
        RadarScanGate.Decision goodAfterPoor = gate.assess(
                9_000L, true, 5f, 10d);
        check(goodAfterPoor.scanRequested && goodAfterPoor.gpsRecovered,
                "poor accuracy does not consume recovery before a good fix");
        gate.accept(goodAfterPoor);
        check(!gate.assess(10_000L, true, 5f, 10d).gpsRecovered,
                "an accepted radar scan consumes recovery");

        gate = new RadarScanGate();
        initial = gate.assess(1_000L, true, 3f, Double.MAX_VALUE);
        gate.accept(initial);
        RadarScanGate.Decision lowDisplacementNetwork = gate.assess(
                8_000L, true, 3f, 1d);
        check(!lowDisplacementNetwork.scanRequested,
                "a low-displacement network fix does not request a radar scan");
        RadarScanGate.Decision goodAfterNetwork = gate.assess(
                9_000L, true, 3f, 4d);
        check(goodAfterNetwork.scanRequested && goodAfterNetwork.gpsRecovered,
                "a low-displacement network fix does not consume recovery");

        RadarScanGate.Decision retryAfterContention = gate.assess(
                10_000L, true, 3f, 5d);
        check(retryAfterContention.scanRequested && retryAfterContention.gpsRecovered,
                "an unaccepted scan keeps recovery pending through DB contention");

        gate = new RadarScanGate();
        initial = gate.assess(1_000L, true, 3f, Double.MAX_VALUE);
        gate.accept(initial);
        for (long elapsed = 2_000L; elapsed <= 7_000L; elapsed += 1_000L) {
            check(!gate.assess(elapsed, true, 3f, 1d).scanRequested,
                    "continuous low displacement does not request a scan");
        }
        RadarScanGate.Decision movementAfterContinuousFixes = gate.assess(
                8_000L, true, 3f, 4d);
        check(movementAfterContinuousFixes.scanRequested
                        && !movementAfterContinuousFixes.gpsRecovered,
                "continuous GPS fixes do not fabricate a recovery event");
    }

    private static StrelkaAlertTracker.Observation observation(
            CameraPoint object, int distance, boolean matches, boolean drop) {
        return new StrelkaAlertTracker.Observation(object, distance,
                object.distanceMeters, matches, drop);
    }

    private static void verifyKnownReleaseHistory() {
        List<ReleaseHistory.Entry> releases = ReleaseHistory.entries();
        check(releases.size() == 12, "about dialog contains every known release");
        check(releases.get(0).version.equals("4.9.8")
                        && releases.get(1).version.equals("4.9.7")
                        && releases.get(2).version.equals("4.9.6")
                        && releases.get(3).version.equals("4.9.5")
                        && releases.get(4).version.equals("4.9.4")
                        && releases.get(5).version.equals("4.9.3")
                        && releases.get(6).version.equals("4.9.2")
                        && releases.get(7).version.equals("4.9.1")
                        && releases.get(8).version.equals("4.9.0")
                        && releases.get(9).version.equals("4.8.1")
                        && releases.get(10).version.equals("4.8.0")
                        && releases.get(11).version.equals("4.7.1"),
                "release history is newest first");
        for (ReleaseHistory.Entry release : releases) {
            check(release.changes != null && !release.changes.trim().isEmpty(),
                    "every release has a visible change description");
        }
        ReleaseHistory.Entry current = ReleaseHistory.find("4.9.8");
        check(current != null && !current.changes.trim().isEmpty(),
                "current release has a visible change description");
        check(current.changes.contains("телефона")
                        && current.changes.contains("Android Auto")
                        && current.changes.contains("MapKit"),
                "current release describes phone and Android Auto alignment");
        check(ReleaseHistory.find("missing") == null,
                "unknown release has no fabricated description");
    }

    private static void verifyProcessLaunchGuard() {
        ProcessLaunchGuard guard = new ProcessLaunchGuard();
        check(guard.claim(), "cold process launch starts the RadarBase update");
        check(!guard.claim(), "activity recreation does not repeat the startup update");
    }

    private static void verifyMapKitLifecycle() {
        final AtomicInteger starts = new AtomicInteger();
        final AtomicInteger stops = new AtomicInteger();
        MapKitLifecycle lifecycle = new MapKitLifecycle(new MapKitLifecycle.Delegate() {
            @Override public void onStart() {
                starts.incrementAndGet();
            }

            @Override public void onStop() {
                stops.incrementAndGet();
            }
        });
        lifecycle.acquire();
        lifecycle.acquire();
        lifecycle.release();
        lifecycle.release();
        lifecycle.release();
        check(starts.get() == 1 && stops.get() == 1,
                "MapKit starts and stops once for overlapping surfaces");
        lifecycle.acquire();
        lifecycle.release();
        check(starts.get() == 2 && stops.get() == 2,
                "extra MapKit release does not make the lifecycle count negative");
    }

    private static void verifyRadarBaseUpdateSingleFlight() {
        RadarBaseUpdateSingleFlight guard = new RadarBaseUpdateSingleFlight();
        check(guard.tryStart(), "the first database update starts");
        check(!guard.tryStart(), "a concurrent database update is coalesced");
        guard.finish();
        check(guard.tryStart(), "a later database update starts after completion");
        guard.finish();
    }

    private static void verifyRadarBaseUpdateStates() {
        RadarBaseUpdateState started = new RadarBaseUpdateState(1,
                RadarBaseUpdateState.Status.STARTED, 91, true, "");
        check(started.sequence == 1
                        && started.status == RadarBaseUpdateState.Status.STARTED
                        && !started.isTerminal(),
                "RadarBase update starts with a non-terminal STARTED state");
        check(started.importedCount == 0 && !started.coordinatesCorrected,
                "STARTED does not expose import results");

        RadarBaseUpdateState unchanged = new RadarBaseUpdateState(2,
                RadarBaseUpdateState.Status.UNCHANGED, 91, true, "");
        check(unchanged.sequence > started.sequence
                        && unchanged.status == RadarBaseUpdateState.Status.UNCHANGED
                        && unchanged.isTerminal(),
                "RadarBase update transitions from STARTED to UNCHANGED");
        check(unchanged.importedCount == 0 && !unchanged.coordinatesCorrected,
                "UNCHANGED does not expose import results");

        RadarBaseUpdateState success = new RadarBaseUpdateState(3,
                RadarBaseUpdateState.Status.SUCCESS, 91, true, "");
        check(success.sequence > started.sequence
                        && success.status == RadarBaseUpdateState.Status.SUCCESS
                        && success.isTerminal(),
                "RadarBase update transitions from STARTED to SUCCESS");
        check(success.importedCount == 91 && success.coordinatesCorrected,
                "SUCCESS exposes imported object count and coordinate correction");

        RadarBaseUpdateState error = new RadarBaseUpdateState(4,
                RadarBaseUpdateState.Status.ERROR, 91, true, "network error");
        check(error.sequence > started.sequence
                        && error.status == RadarBaseUpdateState.Status.ERROR
                        && error.isTerminal()
                        && error.message.equals("network error"),
                "RadarBase update transitions from STARTED to ERROR");
        check(error.importedCount == 0 && !error.coordinatesCorrected,
                "ERROR does not expose import results");

        RadarBaseUpdateState alreadyRunning = new RadarBaseUpdateState(5,
                RadarBaseUpdateState.Status.ALREADY_RUNNING, 91, true, "");
        check(alreadyRunning.isTerminal() && alreadyRunning.importedCount == 0
                        && !alreadyRunning.coordinatesCorrected,
                "ALREADY_RUNNING is terminal and does not expose import results");
    }

    private static void verifyRadarBaseUpdateListenerRegistry() throws Exception {
        QueuedUpdateDispatcher removeDispatcher = new QueuedUpdateDispatcher();
        RadarBaseUpdateListenerRegistry removeRegistry =
                new RadarBaseUpdateListenerRegistry(removeDispatcher);
        RecordingUpdateListener removed = new RecordingUpdateListener();
        removeRegistry.addListener(removed, false);
        removeRegistry.publish(RadarBaseUpdateState.Status.STARTED, 0, false, "started");
        removeRegistry.removeListener(removed);
        removeDispatcher.runNext();
        check(removed.sequences.isEmpty(),
                "a queued RadarBase callback is rejected after listener removal");

        QueuedUpdateDispatcher readdDispatcher = new QueuedUpdateDispatcher();
        RadarBaseUpdateListenerRegistry readdRegistry =
                new RadarBaseUpdateListenerRegistry(readdDispatcher);
        RecordingUpdateListener readded = new RecordingUpdateListener();
        readdRegistry.addListener(readded, false);
        readdRegistry.publish(RadarBaseUpdateState.Status.STARTED, 0, false, "started");
        readdRegistry.removeListener(readded);
        readdRegistry.addListener(readded, false);
        readdRegistry.publish(RadarBaseUpdateState.Status.UNCHANGED, 0, false, "unchanged");
        readdDispatcher.runNext();
        readdDispatcher.runNext();
        check(readded.sequences.equals(Collections.singletonList(2L)),
                "a callback from an old listener registration is rejected after re-add");

        QueuedUpdateDispatcher reverseDispatcher = new QueuedUpdateDispatcher();
        RadarBaseUpdateListenerRegistry reverseRegistry =
                new RadarBaseUpdateListenerRegistry(reverseDispatcher);
        RecordingUpdateListener reversed = new RecordingUpdateListener();
        reverseRegistry.addListener(reversed, false);
        reverseRegistry.publish(RadarBaseUpdateState.Status.STARTED, 0, false, "started");
        reverseRegistry.publish(RadarBaseUpdateState.Status.UNCHANGED, 0, false, "unchanged");
        reverseDispatcher.runAt(1);
        reverseDispatcher.runAt(0);
        check(reversed.sequences.equals(Collections.singletonList(2L)),
                "an older RadarBase state is rejected after a newer state was delivered");

        BlockingFirstUpdateDispatcher orderedDispatcher =
                new BlockingFirstUpdateDispatcher();
        final RadarBaseUpdateListenerRegistry orderedRegistry =
                new RadarBaseUpdateListenerRegistry(orderedDispatcher);
        final RecordingUpdateListener ordered = new RecordingUpdateListener();
        final CountDownLatch secondPublisherReady = new CountDownLatch(1);
        orderedRegistry.addListener(ordered, false);
        Thread firstPublisher = new Thread(new Runnable() {
            @Override public void run() {
                orderedRegistry.publish(RadarBaseUpdateState.Status.STARTED,
                        0, false, "started");
            }
        }, "registry-publish-1");
        Thread secondPublisher = new Thread(new Runnable() {
            @Override public void run() {
                secondPublisherReady.countDown();
                orderedRegistry.publish(RadarBaseUpdateState.Status.UNCHANGED,
                        0, false, "unchanged");
            }
        }, "registry-publish-2");
        firstPublisher.start();
        check(orderedDispatcher.firstPostEntered.await(2, TimeUnit.SECONDS),
                "first RadarBase callback reaches dispatcher");
        secondPublisher.start();
        check(secondPublisherReady.await(2, TimeUnit.SECONDS),
                "second RadarBase publisher starts");
        check(waitUntilBlockedBeforeSecondPost(secondPublisher, orderedDispatcher),
                "state mutation, snapshot, and enqueue share one registry lock");
        orderedDispatcher.releaseFirstPost.countDown();
        firstPublisher.join(2000);
        secondPublisher.join(2000);
        check(!firstPublisher.isAlive() && !secondPublisher.isAlive(),
                "concurrent RadarBase publishers complete");
        orderedDispatcher.runNext();
        orderedDispatcher.runNext();
        check(ordered.sequences.equals(java.util.Arrays.asList(1L, 2L)),
                "RadarBase callback enqueue order follows publish sequence");
    }

    private static boolean waitUntilBlockedBeforeSecondPost(
            Thread publisher, BlockingFirstUpdateDispatcher dispatcher) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (dispatcher.secondPostEntered.getCount() == 0) return false;
            if (publisher.getState() == Thread.State.BLOCKED) return true;
            Thread.yield();
        }
        return false;
    }

    private static final class RecordingUpdateListener
            implements RadarBaseUpdateListenerRegistry.Listener {
        final List<Long> sequences = new ArrayList<>();

        @Override public void onRadarBaseUpdate(RadarBaseUpdateState state) {
            sequences.add(state.sequence);
        }
    }

    private static class QueuedUpdateDispatcher
            implements RadarBaseUpdateListenerRegistry.Dispatcher {
        final List<Runnable> callbacks = Collections.synchronizedList(new ArrayList<Runnable>());

        @Override public void post(Runnable callback) {
            callbacks.add(callback);
        }

        void runNext() {
            runAt(0);
        }

        void runAt(int index) {
            Runnable callback;
            synchronized (callbacks) {
                callback = callbacks.remove(index);
            }
            callback.run();
        }
    }

    private static final class BlockingFirstUpdateDispatcher extends QueuedUpdateDispatcher {
        final CountDownLatch firstPostEntered = new CountDownLatch(1);
        final CountDownLatch secondPostEntered = new CountDownLatch(1);
        final CountDownLatch releaseFirstPost = new CountDownLatch(1);
        final AtomicInteger postCount = new AtomicInteger();

        @Override public void post(Runnable callback) {
            int call = postCount.incrementAndGet();
            if (call == 1) {
                firstPostEntered.countDown();
                try {
                    if (!releaseFirstPost.await(2, TimeUnit.SECONDS)) {
                        throw new AssertionError("first dispatcher post was not released");
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("dispatcher interrupted", error);
                }
            } else if (call == 2) {
                secondPostEntered.countDown();
            }
            super.post(callback);
        }
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

        List<CameraPoint> validCameras = MapMarkerLayout.validCameras(java.util.Arrays.asList(
                points.get(0),
                null,
                marker(405, Double.NaN, 37.0, 1),
                marker(406, 55.0, Double.POSITIVE_INFINITY, 1),
                marker(407, 90.00001, 37.0, 1),
                marker(408, 55.0, -180.00001, 1)));
        check(validCameras.size() == 1 && validCameras.get(0) == points.get(0),
                "shared map input rejects every invalid coordinate");

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

        MapMarkerLayout.Entity firstIndividual = individualEntity(
                marker(401, 55.75000, 37.61000, 1));
        MapMarkerLayout.Entity secondIndividual = individualEntity(
                marker(402, 55.75005, 37.61005, 2));
        MapMarkerEntityDiff.Result addOnly = MapMarkerEntityDiff.between(
                Collections.<String, MapMarkerLayout.Entity>emptyMap(),
                Collections.singletonList(firstIndividual));
        check(addOnly.removeKeys.isEmpty() && addOnly.update.isEmpty()
                        && addOnly.add.size() == 1 && addOnly.add.get(0) == firstIndividual,
                "new stable entity is added without removals or updates");

        Map<String, MapMarkerLayout.Entity> reverseRendered = new LinkedHashMap<>();
        reverseRendered.put(secondIndividual.key, secondIndividual);
        reverseRendered.put(firstIndividual.key, firstIndividual);
        MapMarkerEntityDiff.Result removeOnly = MapMarkerEntityDiff.between(reverseRendered,

                Collections.<MapMarkerLayout.Entity>emptyList());
        check(removeOnly.add.isEmpty() && removeOnly.update.isEmpty()
                        && removeOnly.removeKeys.equals(java.util.Arrays.asList(
                                "camera:401", "camera:402")),
                "removed keys are sorted independently of rendered map order");

        checkUnsupported(new Runnable() {
            @Override public void run() { addOnly.removeKeys.add("camera:999"); }
        }, "remove keys result is immutable");
        checkUnsupported(new Runnable() {
            @Override public void run() { addOnly.add.clear(); }
        }, "add result is immutable");
        checkUnsupported(new Runnable() {
            @Override public void run() { addOnly.update.add(firstIndividual); }
        }, "update result is immutable");
        checkUnsupported(new Runnable() {
            @Override public void run() { addOnly.desired.clear(); }
        }, "desired result is immutable");
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

    private static void checkUnsupported(Runnable action, String message) {
        boolean unsupported = false;
        try {
            action.run();
        } catch (UnsupportedOperationException expected) {
            unsupported = true;
        }
        check(unsupported, message);
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

    private static void verifyCarSurfaceSpec() {
        CarSurfaceSpec usable = CarSurfaceSpec.from(1280, 720, 240);
        check(usable.isUsable(), "positive car surface dimensions are usable");
        check(usable.equals(CarSurfaceSpec.from(1280, 720, 240))
                        && usable.hashCode() == CarSurfaceSpec.from(1280, 720, 240).hashCode(),
                "car surface value equality includes matching dimensions and dpi");
        check(!usable.equals(CarSurfaceSpec.from(1281, 720, 240))
                        && !usable.equals(CarSurfaceSpec.from(1280, 721, 240))
                        && !usable.equals(CarSurfaceSpec.from(1280, 720, 241)),
                "car surface value equality distinguishes width, height and dpi");
        check(!CarSurfaceSpec.from(0, 720, 240).isUsable()
                        && !CarSurfaceSpec.from(1280, 0, 240).isUsable()
                        && !CarSurfaceSpec.from(1280, 720, 0).isUsable()
                        && !CarSurfaceSpec.from(-1, 720, 240).isUsable(),
                "non-positive car surface dimensions or dpi are rejected");
    }

    private static float coordinateShift(int build, float direction) {
        int phase = build * build;
        phase *= 3;
        float key = (float) (Math.sin((double) build) * Math.cos((double) phase)
                / 180.0 * 0.2);
        return (direction + 360f) * key;
    }
}
