package ru.gpsantiradar.app

import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.ArrayList
import java.util.Collections
import java.util.HashMap
import java.util.LinkedHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

 object ParserGeoTest {
@Throws(Exception::class)
 fun main(args:Array<String?>?) {
verifyCarSurfaceSpec()
verifyCarStartupDecision()
verifyThemeResolution()
verifyMapVisualStyle()
verifyMapMarkerHitTest()
verifyCameraHintFormatter()
val json = ("{\"meta\":{\"ver\":\"3.0\",\"build\":3995,"
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
+ "\"dir\":0,\"distance\":100,\"speedControls\":[]}] }")

val points = ArrayList<CameraPoint>()
val parsed = RadarBaseParser.parse(ByteArrayInputStream(
json!!.toByteArray(StandardCharsets.UTF_8)), RadarBaseParser.RecordConsumer { points.add(it) })
check(parsed!!.count == 4 && points.size == 4, "all RadarBase object types imported")
val stationary = points.get(0)
check(stationary!!.id == 8L && stationary!!.type == 1, "RadarBase id and type")
val expectedShift = coordinateShift(3995, 165f)
check((stationary!!.latitude.toFloat() == 45.32478.toFloat() - expectedShift && stationary!!.longitude.toFloat() == 37.72181.toFloat() - expectedShift),
"RadarBase coordinate correction")
check(parsed!!.build !=
null && parsed!!.build == 3995 && parsed!!.coordinatesCorrected,
"RadarBase build metadata")
check(stationary!!.dirType == 1 && stationary!!.direction == 165f,
"RadarBase traffic direction")
check(stationary!!.distanceMeters == 400 && stationary!!.reverseDistanceMeters == 250,
"RadarBase distances")
check(stationary!!.angleDegrees == 15f, "RadarBase sector angle")
check(stationary!!.currentSpeedLimit() == 110, "car-specific speed control")
check(points.get(1).isObservation() && !points.get(1).isFake(),
"explicit observation type")
check(points.get(2).isFake() && points.get(2).hasReverseZone(),
"fake and bidirectional type")
check(points.get(2).typeName().equals("Муляж камеры"), "RadarBase type names")
check(points.get(3).isRoadObject() && points.get(3).typeName().equals("Неровность"),
"road object classification and name")

val explicitUnknown = ("{\"objects\":[{\"id\":12,\"lat\":55.0,\"lng\":37.0," + "\"type\":0,\"dir\":0,\"speedControls\":[]}]}")
val unknown = ArrayList<CameraPoint>()
RadarBaseParser.parse(ByteArrayInputStream(
explicitUnknown!!.toByteArray(StandardCharsets.UTF_8)), RadarBaseParser.RecordConsumer { unknown.add(it) })
check(unknown.size == 1 && unknown.get(0).type == 0,
"explicit type zero imported")

val noBuild = ("{\"meta\":{\"ver\":\"3.0\",\"build\":\"invalid\"},"
+ "\"objects\":[{\"id\":1,\"lat\":55.5,\"lng\":37.5,\"type\":1,"
+ "\"dir\":90,\"speedControls\":[]}]}")
val uncorrected = ArrayList<CameraPoint>()
val noBuildResult = RadarBaseParser.parse(ByteArrayInputStream(
noBuild!!.toByteArray(StandardCharsets.UTF_8)), RadarBaseParser.RecordConsumer { uncorrected.add(it) })
check(noBuildResult!!.build == null && !noBuildResult!!.coordinatesCorrected,
"invalid build disables correction")
check(uncorrected.get(0).latitude == 55.5 && uncorrected.get(0).longitude == 37.5,
"coordinates unchanged without build")

var oldFormatRejected = false
try
{
RadarBaseParser.parse(ByteArrayInputStream(
"2|Radars|1251\n18059|RU-4|51.6|39.1|".toByteArray(StandardCharsets.UTF_8)),
{ point->  })
}
catch (expected:IOException) {
oldFormatRejected = true
}

check(oldFormatRejected, "old export format must not be supported")

val distance = Geo.distanceMeters(56.8, 60.6, 56.8, 60.61)
check(distance > 600 && distance < 650, "distance")
check(Geo.angleDifference(350f, 10f) == 20f, "angle wrap")
check((StrelkaAlertAlgorithm.spokenDistance(951) == 1000
&& StrelkaAlertAlgorithm.spokenDistance(950) == 900
&& StrelkaAlertAlgorithm.spokenDistance(15) == 10),
"Strelka distance voice thresholds")
check((StrelkaAlertAlgorithm.beepVolume(300) == 1f && StrelkaAlertAlgorithm.beepVolume(1000) == 0.05f),
"Strelka beep volume ramp")

val directed = CameraPoint()
directed.id = 100
directed.dirType = 1
directed.direction = 0f
directed.distanceMeters = 500
directed.reverseDistanceMeters = 100
directed.angleDegrees = 20f
directed.speedRules = SpeedControlRules.encode(60, false,
-1, -1, 0, 0, SpeedControlRules.CAR)
check(StrelkaAlertAlgorithm.matchesZone(directed, 400.0, 0f, 0f),
"directional corridor accepts a straight approach")
check(!StrelkaAlertAlgorithm.matchesZone(directed, 400.0, 0f, 30f),
"directional corridor rejects a side object")
val missingDistance = CameraPoint()
missingDistance.id = 102
missingDistance.dirType = 0
check(!StrelkaAlertAlgorithm.matchesZone(missingDistance, 1.0, 0f, 0f),
"objects without database distance never enter the alert zone")
check(StrelkaAlertAlgorithm.mustDropImmediately(directed, 50f, 10.0, 0f, 180f),
"ordinary camera is dropped after passing")
check((!StrelkaAlertAlgorithm.isOverspeeding(directed, 70f) && StrelkaAlertAlgorithm.isOverspeeding(directed, 71f)),
"beeper starts only above the 10 km/h tolerance")
check((AppSettings.clampOverspeedThreshold(-1) == 0
&& AppSettings.clampOverspeedThreshold(7) == 7
&& AppSettings.clampOverspeedThreshold(21) == 20),
"beep overspeed tolerance is clamped to the menu range")
check((!StrelkaAlertAlgorithm.isOverspeeding(directed, 60f, 0)
&& StrelkaAlertAlgorithm.isOverspeeding(directed, 60.1f, 0)
&& !StrelkaAlertAlgorithm.isOverspeeding(directed, 80f, 20)
&& StrelkaAlertAlgorithm.isOverspeeding(directed, 80.1f, 20)),
"beeper uses the configured 0 to 20 km/h tolerance")

val rear = CameraPoint()
rear.id = 101
rear.dirType = 3
rear.direction = 0f
rear.distanceMeters = 500
rear.reverseDistanceMeters = 100
rear.angleDegrees = 20f
check((StrelkaAlertAlgorithm.matchesZone(rear, 50.0, 0f, 180f) && !StrelkaAlertAlgorithm.mustDropImmediately(rear, 50f, 50.0, 0f, 180f)),
"rear-control zone continues after the object")
check(StrelkaAlertAlgorithm.mustDropImmediately(rear, 50f, 120.0, 0f, 180f),
"rear-control zone ends at reverse distance")

verifyStrelkaAlertLifecycle()
verifyMultiObjectOverspeedSelection()
verifyRadarScanRecoveryGate()
verifyKnownReleaseHistory()
verifyProcessLaunchGuard()
verifyMapKitLifecycle()
verifyRadarBaseUpdateSingleFlight()
verifyRadarBaseUpdateStates()
verifyRadarBaseUpdateListenerRegistry()
verifyCameraMarkerDiff()
verifyStableMapMarkerLayout()
verifyMapMarkerEntityDiff()
verifyMapOrientation()
verifyHeadingChannels()

verifyMapMarkerPresentationEquality()
verifyDrivingSnapshotAndHud()
verifyCarMenuItems()
check((AppSettings.adjustOverspeedThreshold(0, -1) == 0 && AppSettings.adjustOverspeedThreshold(20, 1) == 20),
"overspeed threshold uses 0..20 with a 1 km/h step")
check((AppSettings.adjustHudTransparency(0, -1) == 0
&& AppSettings.adjustHudTransparency(75, 1) == 80
&& AppSettings.adjustHudTransparency(80, 1) == 80),
"HUD transparency uses 0..80 with a 5 percent step")
check(!AppSettings.DEFAULT_AUTO_ROTATE_MAP,
"map auto-rotation is disabled by default")
check((AppSettings.clampHudTransparency(-5) == 0
&& AppSettings.clampHudTransparency(12) == 10
&& AppSettings.clampHudTransparency(95) == 80
&& AppSettings.adjustHudTransparency(12, 1) == 15
&& AppSettings.adjustHudTransparency(12, -1) == 5
&& AppSettings.adjustHudTransparency(90, -1) == 75),
"HUD transparency floors to its step grid before adjustment")
if (args!!.size > 0)
{
val count = intArrayOf(0)
val real:RadarBaseParser.Result?
FileInputStream(args!![0]).use({ input-> real = RadarBaseParser.parse(input, { point-> count!![0]++ }) })
check(real!!.count == count!![0] && real!!.count > 150000, "real RadarBase count")
check(real!!.schemaVersion.startsWith("3."), "real RadarBase schema")
check(real!!.build != null && real!!.coordinatesCorrected,
"real RadarBase coordinates corrected")
System.out.println("Real file: " + real!!.count + " objects")
}
System.out.println("ParserGeoTest: OK")
}

private fun verifyMapVisualStyle() {
val normal = MapVisualStyle.coverage(
-0x23c8d0, 2481003L, -1L)
check(normal!!.fillColor == 0x26DC3730,
"normal zone fill preserves its type color at 85 percent transparency")
check(normal!!.strokeColor == 0x4DDC3730,
"normal zone border preserves its type color at 70 percent transparency")

val active = MapVisualStyle.coverage(
-0x23c8d0, 2481003L, 2481003L)
check(active!!.fillColor == 0x4DDC3730,
"active zone fill preserves its type color at 70 percent transparency")
check(active!!.strokeColor == 0x73DC3730,
"active zone border preserves its type color at 55 percent transparency")

val other = MapVisualStyle.coverage(
-0x23c8d0, 2481004L, 2481003L)
check(other!!.fillColor == normal!!.fillColor && other!!.strokeColor == normal!!.strokeColor,
"only the currently controlled object receives active zone colors")
val blue = MapVisualStyle.coverage(
-0xdc9024, 2481004L, 2481003L)
check(blue!!.fillColor == 0x26236FDC && blue!!.strokeColor == 0x4D236FDC,
"each inactive zone keeps the original color of its object type")
check((MapVisualStyle.locationPrimaryColor(false) == -0xe6892e && MapVisualStyle.locationHighlightColor(false) == -0x9b4a0a),
"day current-location arrow uses the blue palette")
check((MapVisualStyle.locationPrimaryColor(true) == -0x4200 && MapVisualStyle.locationHighlightColor(true) == -0x27b8),
"night current-location arrow restores the original yellow palette")
}

private fun verifyCarStartupDecision() {
val ready = CarStartupDecision.from(true, true)
check(ready!!.startTracking && ready!!.showMap,
"car starts tracking and shows the map when setup is ready")
val mapMissing = CarStartupDecision.from(true, false)
check(mapMissing!!.startTracking && !mapMissing!!.showMap,
"car keeps radar tracking active when only MapKit is missing")
val locationMissing = CarStartupDecision.from(false, true)
check(!locationMissing!!.startTracking && !locationMissing!!.showMap,
"car does not start tracking without fine location permission")
}

private fun verifyMapMarkerHitTest() {
val farther = CameraPoint()
farther.id = 10L
val nearer = CameraPoint()
nearer.id = 11L
val candidates = ArrayList<MapMarkerHitTest.Candidate>()
candidates.add(MapMarkerHitTest.Candidate(farther, 120f, 100f))
candidates.add(MapMarkerHitTest.Candidate(nearer, 106f, 102f))

check(MapMarkerHitTest.nearest(100f, 100f, 28f, candidates) == nearer,
"marker hit test selects the nearest individual camera")
check(MapMarkerHitTest.nearest(200f, 200f, 28f, candidates) == null,
"marker hit test rejects taps outside the marker radius")
}

private fun verifyCameraHintFormatter() {
val camera = CameraPoint()
camera.type = 1
camera.dirType = 1
camera.distanceMeters = 650
camera.speedRules = SpeedControlRules.encode(
80, false, -1, -1, 0, 0, SpeedControlRules.CAR)
val hint = CameraHintFormatter.format(camera)
check((hint!!.contains(camera.typeName()) && hint!!.contains("80 км/ч")
&& hint!!.contains("Зона контроля: 650 м")
&& hint!!.contains(camera.directionName())),
"phone and car camera hints share all control details")
}

private fun verifyThemeResolution() {
val equinoxNoonUtc = java.time.Instant.parse("2026-03-20T12:00:00Z").toEpochMilli()
val equinoxMidnightUtc = java.time.Instant.parse("2026-03-20T00:00:00Z").toEpochMilli()
check(!ThemeResolver.isDark(ThemeMode.LIGHT, equinoxMidnightUtc,
0.0, 0.0, java.util.TimeZone.getTimeZone("UTC")),
"manual light theme overrides nighttime")
check(ThemeResolver.isDark(ThemeMode.DARK, equinoxNoonUtc,
0.0, 0.0, java.util.TimeZone.getTimeZone("UTC")),
"manual dark theme overrides daylight")
check((!ThemeResolver.isDark(ThemeMode.AUTOMATIC, equinoxNoonUtc,
0.0, 0.0, java.util.TimeZone.getTimeZone("UTC")) && ThemeResolver.isDark(ThemeMode.AUTOMATIC, equinoxMidnightUtc,
0.0, 0.0, java.util.TimeZone.getTimeZone("UTC"))),
"automatic theme follows solar daylight at the equator")

val tromsoSummerMidnight = java.time.Instant.parse("2026-06-20T22:00:00Z").toEpochMilli()
val tromsoWinterNoon = java.time.Instant.parse("2026-12-21T11:00:00Z").toEpochMilli()
check((!ThemeResolver.isDark(ThemeMode.AUTOMATIC, tromsoSummerMidnight,
69.6492, 18.9553, java.util.TimeZone.getTimeZone("Europe/Oslo")) && ThemeResolver.isDark(ThemeMode.AUTOMATIC, tromsoWinterNoon,
69.6492, 18.9553, java.util.TimeZone.getTimeZone("Europe/Oslo"))),
"automatic theme handles polar day and polar night")

val fallbackNight = java.time.Instant.parse("2026-03-20T21:00:00Z").toEpochMilli()
val fallbackDay = java.time.Instant.parse("2026-03-20T08:00:00Z").toEpochMilli()
check((ThemeResolver.isDark(ThemeMode.AUTOMATIC, fallbackNight,
Double.NaN, Double.NaN, java.util.TimeZone.getTimeZone("UTC")) && !ThemeResolver.isDark(ThemeMode.AUTOMATIC, fallbackDay,
Double.NaN, Double.NaN, java.util.TimeZone.getTimeZone("UTC"))),
"automatic theme uses the 20:00 to 07:00 fallback before GPS")
check(ThemeMode.fromStored("unexpected") == ThemeMode.AUTOMATIC,
"unknown stored theme defaults to automatic")
}

private fun verifyDrivingSnapshotAndHud() {
check(java.lang.reflect.Modifier.isFinal(DrivingSnapshot::class.java!!.getModifiers()),
"driving snapshot type is immutable")
for (field in DrivingSnapshot::class.java!!.getFields())
{
check(java.lang.reflect.Modifier.isFinal(field!!.getModifiers()),
"driving snapshot field is immutable: " + field!!.getName())
}

val idle = DrivingSnapshot.idle()
check((!idle!!.hasLocation() && !idle!!.hasObject()
&& idle!!.accuracyMeters.isNaN()
&& idle!!.cameraId == -1L && idle!!.distanceMeters == -1),
"idle driving snapshot has no location or object")
val idleHud = DrivingHudPresentation.from(idle)
check((idleHud!!.speedText.equals("0") && idleHud!!.distanceText.equals("—")
&& idleHud!!.cameraText.equals("Объектов впереди нет")
&& idleHud!!.speedColor == DrivingHudPresentation.COLOR_GREEN
&& !idleHud!!.hasActiveObject),
"idle HUD uses the shared empty state")

val insideZone = DrivingSnapshot(64.6f, 3.5f, 250,
"Камера", 42L, 80, 800, 56.84, 60.61, 123f,
"inside", "diagnostic")
check((insideZone.hasLocation() && insideZone.hasObject()
&& insideZone.accuracyMeters == 3.5f
&& insideZone.cameraId == 42L
&& insideZone.headingDegrees == 123f
&& insideZone.alertState.equals("inside")
&& insideZone.alertAlgorithm.equals("diagnostic")),
"active driving snapshot preserves shared movement state")
val insideHud = hud(insideZone, 10)
check((insideHud!!.speedText.equals("65") && insideHud!!.distanceText.equals("250 м")
&& insideHud!!.cameraText.equals("Камера  ·  80 км/ч")
&& insideHud!!.speedColor == DrivingHudPresentation.COLOR_GREEN
&& insideHud!!.hasActiveObject
&& !insideHud!!.cameraText.contains("diagnostic")),
"inside-zone HUD stays green below the speed limit")

val farHud = hud(DrivingSnapshot(
120f, Float.NaN, 1250, "Камера", 43L, 80, 800,
Double.NaN, Double.NaN, Float.NaN, "", ""), 10)
check((farHud!!.distanceText.equals("1.3 км") && farHud!!.speedColor == DrivingHudPresentation.COLOR_GREEN),
"HUD stays green outside the object visibility zone")

val unrestrictedHud = hud(DrivingSnapshot(
120f, Float.NaN, 800, "Опасный участок", 44L, 0, 800,
Double.NaN, Double.NaN, Float.NaN, "", ""), 10)
check(unrestrictedHud!!.speedColor == DrivingHudPresentation.COLOR_GREEN,
"inside-zone HUD stays green without a speed limit")

val atLimitHud = hud(DrivingSnapshot(
80f, Float.NaN, 800, "Камера", 45L, 80, 800,
Double.NaN, Double.NaN, Float.NaN, "", ""), 5)
check(atLimitHud!!.speedColor == DrivingHudPresentation.COLOR_ALERT,
"inside-zone HUD turns yellow at the speed limit")

val withinThresholdHud = hud(DrivingSnapshot(
84.9f, Float.NaN, 800, "Камера", 46L, 80, 800,
Double.NaN, Double.NaN, Float.NaN, "", ""), 5)
check(withinThresholdHud!!.speedColor == DrivingHudPresentation.COLOR_ALERT,
"inside-zone HUD stays yellow below the configured threshold boundary")

val atThresholdHud = hud(DrivingSnapshot(
85f, Float.NaN, 800, "Камера", 47L, 80, 800,
Double.NaN, Double.NaN, Float.NaN, "", ""), 5)
check(atThresholdHud!!.speedColor == DrivingHudPresentation.COLOR_OVERSPEED,
"inside-zone HUD turns red at the configured threshold boundary")
}

private fun hud(snapshot:DrivingSnapshot, thresholdKmh:Int):DrivingHudPresentation {
return DrivingHudPresentation.from(snapshot, thresholdKmh)
}

private fun check(condition:Boolean, message:String?) {
if (!condition) throw AssertionError(message)
}

private fun verifyCarMenuItems() {
check(java.util.Arrays.equals(CarMenuItem.values(), arrayOf<CarMenuItem?>(CarMenuItem.UPDATE_DATABASE, CarMenuItem.OVERSPEED_THRESHOLD, CarMenuItem.HUD_TRANSPARENCY, CarMenuItem.AUTO_ROTATE_MAP, CarMenuItem.THEME, CarMenuItem.MAPKIT_KEY, CarMenuItem.ABOUT, CarMenuItem.EXIT)),
"car menu exposes all eight actions in display order")
}

private fun verifyMapOrientation() {
check(MapOrientation.stableHeading(45f, 370f, 2.9f) == 45f,
"stationary GPS jitter must not rotate the location arrow")
check(MapOrientation.stableHeading(45f, 370f, 3f) == 10f,
"movement heading is normalized for the location arrow")
check(MapOrientation.stableHeading(45f, Float.NaN, 30f) == 45f,
"missing movement heading preserves the last arrow direction")
check(MapOrientation.cameraAzimuth(false, 50f, 120f, 25f) == 25f,
"disabled auto-rotation preserves the map azimuth")
check(MapOrientation.cameraAzimuth(true, 2.9f, 120f, 25f) == 25f,
"auto-rotation ignores stationary heading jitter")
check(MapOrientation.cameraAzimuth(true, 3f, 120f, 25f) == 120f,
"enabled auto-rotation follows the movement heading")
}

private fun verifyHeadingChannels() {
val turn = HeadingSelection.forStrelka(
90f, 0f, 45f, true, true)
check(turn!!.visualHeading == 90f,
"visual heading must follow a turn without radar smoothing delay")
check(turn!!.alertHeading == 45f,
"alert heading keeps the Strelka smoothing used for zone matching")

val betweenScans = HeadingSelection.forStrelka(
90f, 0f, 0f, false, true)
check(betweenScans!!.visualHeading == 90f && betweenScans!!.alertHeading == 0f,
"visual and alert headings stay independent between radar scans")
}

private fun verifyStrelkaAlertLifecycle() {
val `object` = CameraPoint()
`object`.id = 200
`object`.dirType = 0
`object`.distanceMeters = 500
val tracker = StrelkaAlertTracker()

var update = tracker.update(Collections.singletonList(
observation(`object`, 500, true, false)), 72f, false)
check(update!!.closestActive == null, "approach needs confidence")
update = tracker.update(Collections.singletonList(
observation(`object`, 480, true, false)), 72f, false)
check(update!!.closestActive == null, "approach is not announced too early")
update = tracker.update(Collections.singletonList(
observation(`object`, 450, true, false)), 72f, false)
check(update!!.pendingVoice != null, "confirmed entry requests one voice alert")
tracker.markSpoken(`object`.id)

update = tracker.update(Collections.singletonList(
observation(`object`, 430, false, false)), 72f, false)
check(update!!.closestActive != null && update!!.pendingVoice == null,
"brief zone loss keeps active alert without repeating voice")
update = tracker.update(Collections.singletonList(
observation(`object`, 410, true, false)), 72f, false)
check(update!!.closestActive != null && update!!.pendingVoice == null,
"return to zone preserves spoken state")

update = tracker.update(Collections.singletonList(
observation(`object`, 20, false, true)), 72f, false)
check(update!!.closestActive == null && update!!.exited.size == 1,
"passing the object fully resets its state")

tracker.update(Collections.singletonList(
observation(`object`, 500, true, false)), 72f, false)
tracker.update(Collections.singletonList(
observation(`object`, 480, true, false)), 72f, false)
update = tracker.update(Collections.singletonList(
observation(`object`, 450, true, false)), 72f, false)
check(update!!.pendingVoice != null,
"new confirmed approach after exit repeats the voice alert")
}

private fun verifyMultiObjectOverspeedSelection() {
val noLimit = marker(501, 55.75, 37.61, 1)
noLimit.speedRules = ""
val limited = marker(502, 55.751, 37.611, 1)
limited.speedRules = SpeedControlRules.encode(60, false,
-1, -1, 0, 0, SpeedControlRules.CAR)
var tracker:StrelkaAlertTracker? = activeTracker(java.util.Arrays.asList(
observation(noLimit, 100, true, false),
observation(limited, 200, true, false)), 90f)
var overspeed = tracker!!.snapshot().overspeedCandidate(90f)
check(overspeed != null && overspeed!!.`object`.id == limited.id,
"a farther limited object requests a beep when the nearest has no limit")

val lowerId = marker(513, 55.752, 37.612, 1)
lowerId.speedRules = SpeedControlRules.encode(50, false,
-1, -1, 0, 0, SpeedControlRules.CAR)
val higherId = marker(529, 55.753, 37.613, 1)
higherId.speedRules = SpeedControlRules.encode(60, false,
-1, -1, 0, 0, SpeedControlRules.CAR)
val lower = observation(lowerId, 150, true, false)
val higher = observation(higherId, 150, true, false)
val forward = activeTracker(java.util.Arrays.asList(higher, lower), 90f)
.snapshot().overspeedCandidate(90f)!!.`object`.id
val reversed = activeTracker(java.util.Arrays.asList(lower, higher), 90f)
.snapshot().overspeedCandidate(90f)!!.`object`.id
check(forward == lowerId.id && reversed == lowerId.id,
"equal-distance overspeed selection is stable across input order")

val highLimit = marker(530, 55.754, 37.614, 1)
highLimit.speedRules = SpeedControlRules.encode(100, false,
-1, -1, 0, 0, SpeedControlRules.CAR)
tracker = activeTracker(java.util.Arrays.asList(
observation(highLimit, 90, true, false),
observation(limited, 200, true, false)), 90f)
overspeed = tracker!!.snapshot().overspeedCandidate(90f)
check(overspeed != null && overspeed!!.`object`.id == limited.id,
"a farther object requests a beep when the nearest limit is higher")
check((tracker!!.snapshot().overspeedCandidate(80f, 20) == null && tracker!!.snapshot().overspeedCandidate(81f, 20) != null),
"multi-object beep selection uses the configured tolerance")
}

private fun activeTracker(
observations:List<StrelkaAlertTracker.Observation>, speedKmh:Float):StrelkaAlertTracker {
val tracker = StrelkaAlertTracker()
val update = tracker.update(observations, speedKmh, true)
check(update!!.activeCount == observations!!.size, "test objects become active")
for (item in observations!!)
{
tracker.markSpoken(item!!.`object`.id)
}
return tracker
}

private fun verifyRadarScanRecoveryGate() {
var gate:RadarScanGate? = RadarScanGate()
var initial = gate!!.assess(
1_000L, true, 5f, Double.MAX_VALUE)
check(initial!!.scanRequested && !initial!!.gpsRecovered,
"the initial accurate fix requests a normal scan")
gate!!.accept(initial)

val poorAccuracy = gate!!.assess(
8_000L, true, 80f, 100.0)
check(!poorAccuracy!!.scanRequested,
"a poor-accuracy fix does not request a radar scan")
val goodAfterPoor = gate!!.assess(
9_000L, true, 5f, 10.0)
check(goodAfterPoor!!.scanRequested && goodAfterPoor!!.gpsRecovered,
"poor accuracy does not consume recovery before a good fix")
gate!!.accept(goodAfterPoor)
check(!gate!!.assess(10_000L, true, 5f, 10.0).gpsRecovered,
"an accepted radar scan consumes recovery")

gate = RadarScanGate()
initial = gate!!.assess(1_000L, true, 3f, Double.MAX_VALUE)
gate!!.accept(initial)
val lowDisplacementNetwork = gate!!.assess(
8_000L, true, 3f, 1.0)
check(!lowDisplacementNetwork!!.scanRequested,
"a low-displacement network fix does not request a radar scan")
val goodAfterNetwork = gate!!.assess(
9_000L, true, 3f, 4.0)
check(goodAfterNetwork!!.scanRequested && goodAfterNetwork!!.gpsRecovered,
"a low-displacement network fix does not consume recovery")

val retryAfterContention = gate!!.assess(
10_000L, true, 3f, 5.0)
check(retryAfterContention!!.scanRequested && retryAfterContention!!.gpsRecovered,
"an unaccepted scan keeps recovery pending through DB contention")

gate = RadarScanGate()
initial = gate!!.assess(1_000L, true, 3f, Double.MAX_VALUE)
gate!!.accept(initial)
var elapsed = 2_000L
while (elapsed <= 7_000L)
{
check(!gate!!.assess(elapsed, true, 3f, 1.0).scanRequested,
"continuous low displacement does not request a scan")
elapsed += 1_000L
}
val movementAfterContinuousFixes = gate!!.assess(
8_000L, true, 3f, 4.0)
check((movementAfterContinuousFixes!!.scanRequested && !movementAfterContinuousFixes!!.gpsRecovered),
"continuous GPS fixes do not fabricate a recovery event")
}

private fun observation(
`object`:CameraPoint, distance:Int, matches:Boolean, drop:Boolean):StrelkaAlertTracker.Observation {
return StrelkaAlertTracker.Observation(`object`, distance,
`object`!!.distanceMeters, matches, drop)
}

private fun verifyKnownReleaseHistory() {
val releases = ReleaseHistory.entries()
check(releases!!.size == 13, "about dialog contains every known release")
check((releases!!.get(0).version.equals("4.9.9")
&& releases!!.get(1).version.equals("4.9.8")
&& releases!!.get(2).version.equals("4.9.7")
&& releases!!.get(3).version.equals("4.9.6")
&& releases!!.get(4).version.equals("4.9.5")
&& releases!!.get(5).version.equals("4.9.4")
&& releases!!.get(6).version.equals("4.9.3")
&& releases!!.get(7).version.equals("4.9.2")
&& releases!!.get(8).version.equals("4.9.1")
&& releases!!.get(9).version.equals("4.9.0")
&& releases!!.get(10).version.equals("4.8.1")
&& releases!!.get(11).version.equals("4.8.0")
&& releases!!.get(12).version.equals("4.7.1")),
"release history is newest first")
for (release in releases!!)
{
check(release!!.changes != null && !release!!.changes.trim().isEmpty(),
"every release has a visible change description")
}
val current = ReleaseHistory.find("4.9.9")
check(current != null && !current!!.changes.trim().isEmpty(),
"current release has a visible change description")
check((current!!.changes.contains("Kotlin")
&& current!!.changes.contains("Gradle Kotlin DSL")
&& current!!.changes.contains("src/main/kotlin")),
"current release describes the Kotlin migration")
check(ReleaseHistory.find("missing") == null,
"unknown release has no fabricated description")
}

private fun verifyProcessLaunchGuard() {
val guard = ProcessLaunchGuard()
check(guard.claim(), "cold process launch starts the RadarBase update")
check(!guard.claim(), "activity recreation does not repeat the startup update")
}

private fun verifyMapKitLifecycle() {
val starts = AtomicInteger()
val stops = AtomicInteger()
val lifecycle = MapKitLifecycle(object:MapKitLifecycle.Delegate {
override fun onStart() {
starts.incrementAndGet()
}

override fun onStop() {
stops.incrementAndGet()
}
})
lifecycle.acquire()
lifecycle.acquire()
lifecycle.release()
lifecycle.release()
lifecycle.release()
check(starts.get() == 1 && stops.get() == 1,
"MapKit starts and stops once for overlapping surfaces")
lifecycle.acquire()
lifecycle.release()
check(starts.get() == 2 && stops.get() == 2,
"extra MapKit release does not make the lifecycle count negative")
}

private fun verifyRadarBaseUpdateSingleFlight() {
val guard = RadarBaseUpdateSingleFlight()
check(guard.tryStart(), "the first database update starts")
check(!guard.tryStart(), "a concurrent database update is coalesced")
guard.finish()
check(guard.tryStart(), "a later database update starts after completion")
guard.finish()
}

private fun verifyRadarBaseUpdateStates() {
val started = RadarBaseUpdateState(1,
RadarBaseUpdateState.Status.STARTED, 91, true, "")
check((started.sequence == 1L
&& started.status == RadarBaseUpdateState.Status.STARTED
&& !started.isTerminal()),
"RadarBase update starts with a non-terminal STARTED state")
check(started.importedCount == 0 && !started.coordinatesCorrected,
"STARTED does not expose import results")

val unchanged = RadarBaseUpdateState(2,
RadarBaseUpdateState.Status.UNCHANGED, 91, true, "")
check((unchanged.sequence > started.sequence
&& unchanged.status == RadarBaseUpdateState.Status.UNCHANGED
&& unchanged.isTerminal()),
"RadarBase update transitions from STARTED to UNCHANGED")
check(unchanged.importedCount == 0 && !unchanged.coordinatesCorrected,
"UNCHANGED does not expose import results")

val success = RadarBaseUpdateState(3,
RadarBaseUpdateState.Status.SUCCESS, 91, true, "")
check((success.sequence > started.sequence
&& success.status == RadarBaseUpdateState.Status.SUCCESS
&& success.isTerminal()),
"RadarBase update transitions from STARTED to SUCCESS")
check(success.importedCount == 91 && success.coordinatesCorrected,
"SUCCESS exposes imported object count and coordinate correction")

val error = RadarBaseUpdateState(4,
RadarBaseUpdateState.Status.ERROR, 91, true, "network error")
check((error.sequence > started.sequence
&& error.status == RadarBaseUpdateState.Status.ERROR
&& error.isTerminal()
&& error.message.equals("network error")),
"RadarBase update transitions from STARTED to ERROR")
check(error.importedCount == 0 && !error.coordinatesCorrected,
"ERROR does not expose import results")

val alreadyRunning = RadarBaseUpdateState(5,
RadarBaseUpdateState.Status.ALREADY_RUNNING, 91, true, "")
check((alreadyRunning.isTerminal() && alreadyRunning.importedCount == 0
&& !alreadyRunning.coordinatesCorrected),
"ALREADY_RUNNING is terminal and does not expose import results")
}

@Throws(Exception::class)
private fun verifyRadarBaseUpdateListenerRegistry() {
val removeDispatcher = QueuedUpdateDispatcher()
val removeRegistry = RadarBaseUpdateListenerRegistry(removeDispatcher)
val removed = RecordingUpdateListener()
removeRegistry.addListener(removed, false)
removeRegistry.publish(RadarBaseUpdateState.Status.STARTED, 0, false, "started")
removeRegistry.removeListener(removed)
removeDispatcher.runNext()
check(removed.sequences.isEmpty(),
"a queued RadarBase callback is rejected after listener removal")

val readdDispatcher = QueuedUpdateDispatcher()
val readdRegistry = RadarBaseUpdateListenerRegistry(readdDispatcher)
val readded = RecordingUpdateListener()
readdRegistry.addListener(readded, false)
readdRegistry.publish(RadarBaseUpdateState.Status.STARTED, 0, false, "started")
readdRegistry.removeListener(readded)
readdRegistry.addListener(readded, false)
readdRegistry.publish(RadarBaseUpdateState.Status.UNCHANGED, 0, false, "unchanged")
readdDispatcher.runNext()
readdDispatcher.runNext()
check(readded.sequences.equals(Collections.singletonList(2L)),
"a callback from an old listener registration is rejected after re-add")

val reverseDispatcher = QueuedUpdateDispatcher()
val reverseRegistry = RadarBaseUpdateListenerRegistry(reverseDispatcher)
val reversed = RecordingUpdateListener()
reverseRegistry.addListener(reversed, false)
reverseRegistry.publish(RadarBaseUpdateState.Status.STARTED, 0, false, "started")
reverseRegistry.publish(RadarBaseUpdateState.Status.UNCHANGED, 0, false, "unchanged")
reverseDispatcher.runAt(1)
reverseDispatcher.runAt(0)
check(reversed.sequences.equals(Collections.singletonList(2L)),
"an older RadarBase state is rejected after a newer state was delivered")

val orderedDispatcher = BlockingFirstUpdateDispatcher()
val orderedRegistry = RadarBaseUpdateListenerRegistry(orderedDispatcher)
val ordered = RecordingUpdateListener()
val secondPublisherReady = CountDownLatch(1)
orderedRegistry.addListener(ordered, false)
val firstPublisher = Thread(object:Runnable {
override fun run() {
orderedRegistry.publish(RadarBaseUpdateState.Status.STARTED,
0, false, "started")
}
}, "registry-publish-1")
val secondPublisher = Thread(object:Runnable {
override fun run() {
secondPublisherReady.countDown()
orderedRegistry.publish(RadarBaseUpdateState.Status.UNCHANGED,
0, false, "unchanged")
}
}, "registry-publish-2")
firstPublisher.start()
check(orderedDispatcher.firstPostEntered.await(2, TimeUnit.SECONDS),
"first RadarBase callback reaches dispatcher")
secondPublisher.start()
check(secondPublisherReady.await(2, TimeUnit.SECONDS),
"second RadarBase publisher starts")
check(waitUntilBlockedBeforeSecondPost(secondPublisher, orderedDispatcher),
"state mutation, snapshot, and enqueue share one registry lock")
orderedDispatcher.releaseFirstPost.countDown()
firstPublisher.join(2000)
secondPublisher.join(2000)
check(!firstPublisher.isAlive() && !secondPublisher.isAlive(),
"concurrent RadarBase publishers complete")
orderedDispatcher.runNext()
orderedDispatcher.runNext()
check(ordered.sequences.equals(java.util.Arrays.asList(1L, 2L)),
"RadarBase callback enqueue order follows publish sequence")
}

private fun waitUntilBlockedBeforeSecondPost(
publisher:Thread?, dispatcher:BlockingFirstUpdateDispatcher?):Boolean {
val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
while (System.nanoTime() < deadline)
{
if (dispatcher!!.secondPostEntered.getCount() == 0L) return false
if (publisher!!.getState() == Thread.State.BLOCKED) return true
Thread.yield()
}
return false
}

private class RecordingUpdateListener:RadarBaseUpdateListenerRegistry.Listener {
 val sequences:MutableList<Long> = ArrayList()

override fun onRadarBaseUpdate(state:RadarBaseUpdateState) {
sequences.add(state.sequence)
}
}

private open class QueuedUpdateDispatcher:RadarBaseUpdateListenerRegistry.Dispatcher {
 val callbacks = Collections.synchronizedList(ArrayList<Runnable>())

override fun post(callback:Runnable) {
callbacks!!.add(callback)
}

fun runNext() {
runAt(0)
}

 fun runAt(index:Int) {
val callback:Runnable
synchronized (callbacks) {
callback = callbacks!!.removeAt(index)
}
callback!!.run()
}
}

private class BlockingFirstUpdateDispatcher:QueuedUpdateDispatcher() {
 val firstPostEntered = CountDownLatch(1)
 val secondPostEntered = CountDownLatch(1)
 val releaseFirstPost = CountDownLatch(1)
 val postCount = AtomicInteger()

override fun post(callback:Runnable) {
val call = postCount.incrementAndGet()
if (call == 1)
{
firstPostEntered.countDown()
try
{
if (!releaseFirstPost.await(2, TimeUnit.SECONDS))
{
throw AssertionError("first dispatcher post was not released")
}
}
catch (error:InterruptedException) {
Thread.currentThread().interrupt()
throw AssertionError("dispatcher interrupted", error)
}

}
else if (call == 2)
{
secondPostEntered.countDown()
}
super.post(callback)
}
}

private fun verifyCameraMarkerDiff() {
val retained = marker(301, 56.80, 60.60, 1)
val leaving = marker(302, 56.81, 60.61, 2)
val rendered = HashMap<Long, CameraPoint>()
rendered.put(retained.id, retained)
rendered.put(leaving.id, leaving)

val unchangedCopy = marker(301, 56.80, 60.60, 1)
val entering = marker(303, 56.82, 60.62, 3)
val moved = CameraMarkerDiff.between(rendered,
java.util.Arrays.asList(unchangedCopy, entering))
check(moved!!.removeIds.size == 1 && moved!!.removeIds.contains(302L),
"marker leaving the buffered viewport is removed")
check(moved!!.addOrReplace.size == 1 && moved!!.addOrReplace.get(0).id == 303L,
"only marker entering the buffered viewport is added")

val changed = marker(301, 56.805, 60.60, 1)
val databaseChanged = CameraMarkerDiff.between(rendered,
java.util.Arrays.asList(changed, leaving))
check((databaseChanged!!.removeIds.size == 1
&& databaseChanged!!.removeIds.contains(301L)
&& databaseChanged!!.addOrReplace.size == 1
&& databaseChanged!!.addOrReplace.get(0).id == 301L),
"changed database object is replaced without rebuilding unchanged markers")
}

private fun verifyStableMapMarkerLayout() {
val points = java.util.Arrays.asList(
marker(401, 55.75000, 37.61000, 1),
marker(402, 55.75005, 37.61005, 2),
marker(403, 56.83000, 60.60000, 1),
marker(404, 56.83005, 60.60005, 2))
val first = MapMarkerLayout.create(points, 10.4f)
val panned = MapMarkerLayout.create(ArrayList(points), 10.4f)
check(first!!.size == 2, "nearby cameras form two stable clusters")
check((first!!.get(0).key.equals(panned!!.get(0).key) && first!!.get(1).key.equals(panned!!.get(1).key)),
"same zoom preserves world-anchored cluster keys")

val individual = MapMarkerLayout.create(
Collections.singletonList(points!!.get(0)), 14f)
check((individual!!.size == 1 && !individual!!.get(0).cluster
&& individual!!.get(0).key.equals("camera:401")),
"zoom 14 displays individual cameras")

val invalid = marker(405, Double.NaN, 37.0, 1)
check(MapMarkerLayout.create(Collections.singletonList(invalid), 10f).isEmpty(),
"invalid coordinates are skipped")

val validCameras = MapMarkerLayout.validCameras(java.util.Arrays.asList(
points!!.get(0), null,
marker(405, Double.NaN, 37.0, 1),
marker(406, 55.0, Double.POSITIVE_INFINITY, 1),
marker(407, 90.00001, 37.0, 1),
marker(408, 55.0, -180.00001, 1)))
check(validCameras!!.size == 1 && validCameras!!.get(0) == points!!.get(0),
"shared map input rejects every invalid coordinate")

val firstCentroidPoint = marker(409, 55.75, 37.61000, 1)
firstCentroidPoint.latitude = Double.fromBits(4633042932285308929L)
val secondCentroidPoint = marker(410, 55.75, 37.61000, 1)
secondCentroidPoint.latitude = Double.fromBits(4633042932285309051L)
val thirdCentroidPoint = marker(411, 55.75, 37.61000, 1)
thirdCentroidPoint.latitude = Double.fromBits(4633042933519876818L)
val exactCentroidOrder = java.util.Arrays.asList(
firstCentroidPoint, secondCentroidPoint, thirdCentroidPoint)
val exactOrdered = MapMarkerLayout.create(exactCentroidOrder, 10.4f)
val exactPermuted = MapMarkerLayout.create(
java.util.Arrays.asList(thirdCentroidPoint, secondCentroidPoint,
firstCentroidPoint), 10.4f)
check((exactOrdered!!.size == 1 && exactOrdered!!.get(0).samePresentation(exactPermuted!!.get(0))),
"permuted exact cluster inputs preserve the centroid presentation")
}


private fun verifyMapMarkerPresentationEquality() {
val camera = marker(420, 55.75000, 37.61000, 1)
camera.rank = 2.5f
camera.newbie = true
val baseline = individualEntity(camera)
check(baseline!!.samePresentation(individualEntity(copyMarker(camera))),
"equivalent individual entities share a presentation")

var changed:CameraPoint? = copyMarker(camera)
changed!!.id = 421
check(!baseline!!.samePresentation(individualEntity(changed)), "camera ID changes presentation")
changed = copyMarker(camera)
changed!!.latitude = 55.75001
check(!baseline!!.samePresentation(individualEntity(changed)), "latitude changes presentation")
changed = copyMarker(camera)
changed!!.longitude = 37.61001
check(!baseline!!.samePresentation(individualEntity(changed)), "longitude changes presentation")
changed = copyMarker(camera)
changed!!.type = 2
check(!baseline!!.samePresentation(individualEntity(changed)), "type changes presentation")
changed = copyMarker(camera)
changed!!.dirType = 2
check(!baseline!!.samePresentation(individualEntity(changed)), "direction type changes presentation")
changed = copyMarker(camera)
changed!!.direction = 91f
check(!baseline!!.samePresentation(individualEntity(changed)), "direction changes presentation")
changed = copyMarker(camera)
changed!!.distanceMeters = 501
check(!baseline!!.samePresentation(individualEntity(changed)), "distance changes presentation")
changed = copyMarker(camera)
changed!!.reverseDistanceMeters = 101
check(!baseline!!.samePresentation(individualEntity(changed)),
"reverse distance changes presentation")
changed = copyMarker(camera)
changed!!.angleDegrees = 21f
check(!baseline!!.samePresentation(individualEntity(changed)), "angle changes presentation")
changed = copyMarker(camera)
changed!!.rank = 3f
check(!baseline!!.samePresentation(individualEntity(changed)), "rank changes presentation")
changed = copyMarker(camera)
changed!!.newbie = false
check(!baseline!!.samePresentation(individualEntity(changed)), "newbie changes presentation")
changed = copyMarker(camera)
changed!!.speedRules = "70"
check(!baseline!!.samePresentation(individualEntity(changed)), "speed rules change presentation")
}

private fun verifyMapMarkerEntityDiff() {
val initialPoints = java.util.Arrays.asList(
marker(401, 55.75000, 37.61000, 1),
marker(402, 55.75005, 37.61005, 2),
marker(403, 56.83000, 60.60000, 1),
marker(404, 56.83005, 60.60005, 2))
var rendered:Map<String, MapMarkerLayout.Entity> = entitiesByKey(
MapMarkerLayout.create(initialPoints, 10.4f))
val yekaterinburgKey = entityKeyContaining(rendered!!, 403L)
var changedPoints:MutableList<CameraPoint> = ArrayList(initialPoints)
changedPoints!!.add(marker(405, 55.75003, 37.61003, 3))
var diff = MapMarkerEntityDiff.between(rendered,
MapMarkerLayout.create(changedPoints, 10.4f))
check(diff!!.removeKeys.isEmpty() && diff!!.add.isEmpty() && diff!!.update.size == 1,
"new member updates only its existing cluster")
check(diff!!.update.get(0).memberIds.contains(405L),
"updated cluster contains the entering camera")
check(!diff!!.update.get(0).key.equals(yekaterinburgKey),
"Yekaterinburg cluster remains unchanged")

rendered = entitiesByKey(MapMarkerLayout.create(initialPoints, 14f))
changedPoints = ArrayList(initialPoints)
changedPoints!!.set(0, marker(401, 55.75010, 37.61000, 1))
diff = MapMarkerEntityDiff.between(rendered,
MapMarkerLayout.create(changedPoints, 14f))
check(diff!!.removeKeys.isEmpty() && diff!!.add.isEmpty() && diff!!.update.size == 1,
"moving an individual camera updates its stable entity")
check(diff!!.update.get(0).key.equals("camera:401"),
"moved camera preserves its individual key")

val firstIndividual = individualEntity(
marker(401, 55.75000, 37.61000, 1))
val secondIndividual = individualEntity(
marker(402, 55.75005, 37.61005, 2))
val addOnly = MapMarkerEntityDiff.between(
Collections.emptyMap<String, MapMarkerLayout.Entity>(),
Collections.singletonList(firstIndividual))
check((addOnly!!.removeKeys.isEmpty() && addOnly!!.update.isEmpty()
&& addOnly!!.add.size == 1 && addOnly!!.add.get(0) == firstIndividual),
"new stable entity is added without removals or updates")

val reverseRendered = LinkedHashMap<String, MapMarkerLayout.Entity>()
reverseRendered.put(secondIndividual!!.key, secondIndividual)
reverseRendered.put(firstIndividual!!.key, firstIndividual)
val removeOnly = MapMarkerEntityDiff.between(reverseRendered,

Collections.emptyList<MapMarkerLayout.Entity>())
check((removeOnly!!.add.isEmpty() && removeOnly!!.update.isEmpty()
&& removeOnly!!.removeKeys.equals(java.util.Arrays.asList(
"camera:401", "camera:402"))),
"removed keys are sorted independently of rendered map order")

checkUnsupported(object:Runnable {
override fun run() {
addOnly!!.removeKeys.add("camera:999")
}
}, "remove keys result is immutable")
checkUnsupported(object:Runnable {
override fun run() {
addOnly!!.add.clear()
}
}, "add result is immutable")
checkUnsupported(object:Runnable {
override fun run() {
addOnly!!.update.add(firstIndividual)
}
}, "update result is immutable")
checkUnsupported(object:Runnable {
override fun run() {
addOnly!!.desired.clear()
}
}, "desired result is immutable")
}

private fun entitiesByKey(
entities:List<MapMarkerLayout.Entity>):Map<String, MapMarkerLayout.Entity> {
val byKey = LinkedHashMap<String, MapMarkerLayout.Entity>()
for (entity in entities) byKey.put(entity!!.key, entity)
return byKey
}

private fun entityKeyContaining(entities:Map<String, MapMarkerLayout.Entity>,
memberId:Long):String {
for (entity in entities.values)
{
if (entity!!.memberIds.contains(memberId)) return entity!!.key
}
throw AssertionError("missing member " + memberId)
}

private fun checkUnsupported(action:Runnable, message:String?) {
var unsupported = false
try
{
action.run()
}
catch (expected:UnsupportedOperationException) {
unsupported = true
}

check(unsupported, message)
}
private fun individualEntity(camera:CameraPoint?):MapMarkerLayout.Entity? {
return MapMarkerLayout.create(Collections.singletonList(camera), 14f).get(0)
}

private fun copyMarker(source:CameraPoint):CameraPoint {
val copy = CameraPoint()
copy.id = source.id
copy.latitude = source.latitude
copy.longitude = source.longitude
copy.type = source.type
copy.dirType = source.dirType
copy.direction = source.direction
copy.distanceMeters = source.distanceMeters
copy.reverseDistanceMeters = source.reverseDistanceMeters
copy.angleDegrees = source.angleDegrees
copy.rank = source.rank
copy.newbie = source.newbie
copy.speedRules = source.speedRules
return copy
}

private fun marker(id:Long, latitude:Double, longitude:Double, type:Int):CameraPoint {
val point = CameraPoint()
point.id = id
point.latitude = latitude
point.longitude = longitude
point.type = type
point.dirType = 1
point.direction = 90f
point.distanceMeters = 500
point.reverseDistanceMeters = 100
point.angleDegrees = 20f
point.speedRules = "60"
return point
}

private fun verifyCarSurfaceSpec() {
val usable = CarSurfaceSpec.from(1280, 720, 240)
check(usable!!.isUsable(), "positive car surface dimensions are usable")
check((usable!!.equals(CarSurfaceSpec.from(1280, 720, 240)) && usable!!.hashCode() == CarSurfaceSpec.from(1280, 720, 240).hashCode()),
"car surface value equality includes matching dimensions and dpi")
check((!usable!!.equals(CarSurfaceSpec.from(1281, 720, 240))
&& !usable!!.equals(CarSurfaceSpec.from(1280, 721, 240))
&& !usable!!.equals(CarSurfaceSpec.from(1280, 720, 241))),
"car surface value equality distinguishes width, height and dpi")
check((!CarSurfaceSpec.from(0, 720, 240).isUsable()
&& !CarSurfaceSpec.from(1280, 0, 240).isUsable()
&& !CarSurfaceSpec.from(1280, 720, 0).isUsable()
&& !CarSurfaceSpec.from(-1, 720, 240).isUsable()),
"non-positive car surface dimensions or dpi are rejected")
}

private fun coordinateShift(build:Int, direction:Float):Float {
var phase = build * build
phase *= 3
val key = ((Math.sin(build.toDouble()) * Math.cos(phase.toDouble()) / 180.0) * 0.2).toFloat()
return (direction + 360f) * key
}
}
