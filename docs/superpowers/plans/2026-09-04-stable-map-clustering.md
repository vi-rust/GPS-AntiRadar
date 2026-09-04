# Stable Map Clustering Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** Preserve existing individual and grouped map icons while changing only entities affected by viewport and zoom changes.

**Architecture:** Replace MapKit full-collection clustering with a deterministic Web Mercator layout and an entity diff. MainActivity applies the diff to one persistent ordinary MapObjectCollection; coverage polygons and the existing 20% SQLite query buffer stay independent.

**Tech Stack:** Java 8, Android SDK 36, Yandex MapKit 4.42 Lite, PowerShell/JVM test harness.

**Spec:** docs/superpowers/specs/2026-09-04-stable-map-clustering-design.md

## Global Constraints

- Preserve the existing 20% padded SQLite query bounds.
- Use individual markers at zoom 14 and above.
- Use a world-anchored 52-pixel Web Mercator grid below zoom 14.
- Never mutate unchanged MapKit entities.
- A changed cluster may update; unrelated entities remain untouched.
- Keep coverage polygons and the GPS marker independent.
- Skip invalid coordinates.
- Release 4.9.3 shows its own description in About.
- Preserve all pre-existing uncommitted user changes.

---

### Task 1: Deterministic Marker Layout

**Files:**
- Create: src/ru/hudspeed/pro/MapMarkerLayout.java
- Modify: test.ps1
- Modify: tests/ParserGeoTest.java

**Interfaces:**
- Consumes CameraPoint records and float zoom.
- Produces MapMarkerLayout.create(List<CameraPoint>, float).
- Produces immutable Entity fields key, cluster, latitude, longitude, memberIds, camera.
- Produces Entity.samePresentation(Entity).

- [ ] **Step 1: Write the failing tests**

Add MapMarkerLayout.java to test.ps1 and add:

~~~java
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
}
~~~

This catches unstable keys, a wrong zoom threshold, and invalid coordinate handling.

- [ ] **Step 2: Verify RED**

Run .\test.ps1. Expected: compilation fails because MapMarkerLayout is absent.

- [ ] **Step 3: Implement MapMarkerLayout**

Use the exact coordinate model:

~~~java
static final float INDIVIDUAL_ZOOM = 14f;
private static final double TILE_SIZE = 256.0;
private static final double CELL_PIXELS = 52.0;
double x = (longitude + 180.0) / 360.0;
double clamped = Math.max(-85.05112878, Math.min(85.05112878, latitude));
double radians = Math.toRadians(clamped);
double y = (1.0 - Math.log(Math.tan(radians)
        + 1.0 / Math.cos(radians)) / Math.PI) / 2.0;
double worldSize = TILE_SIZE * Math.pow(2.0, Math.floor(zoom));
long cellX = (long) Math.floor(x * worldSize / CELL_PIXELS);
long cellY = (long) Math.floor(y * worldSize / CELL_PIXELS);
~~~

Reject non-finite or out-of-range coordinates. At zoom 14 and above return
camera:<id>. Below zoom 14, group in a TreeMap keyed
cluster:<floorZoom>:<cellX>:<cellY>. A one-member cell remains individual.
Sort member IDs and use the geographic centroid. Individual equality includes
every CameraPoint field used by map presentation.

- [ ] **Step 4: Verify GREEN**

Run .\test.ps1. Expected: ParserGeoTest: OK.

- [ ] **Step 5: Review and commit**

Inspect staged tests because they contain earlier approved work.

~~~powershell
git add src/ru/hudspeed/pro/MapMarkerLayout.java test.ps1 tests/ParserGeoTest.java
git commit -m "feat: add stable map marker layout"
~~~

---

### Task 2: Entity Diff

**Files:**
- Create: src/ru/hudspeed/pro/MapMarkerEntityDiff.java
- Modify: test.ps1
- Modify: tests/ParserGeoTest.java

**Interfaces:**
- Consumes rendered Map<String, MapMarkerLayout.Entity> and desired entities.
- Produces Result fields removeKeys, add, update, desired.

- [ ] **Step 1: Write the failing localized-change tests**

Build two clusters from Task 1, add camera 405 near camera 401, then:

~~~java
Map<String, MapMarkerLayout.Entity> rendered = entitiesByKey(
        MapMarkerLayout.create(initialPoints, 10.4f));
List<CameraPoint> changedPoints = new ArrayList<>(initialPoints);
changedPoints.add(marker(405, 55.75003, 37.61003, 3));
MapMarkerEntityDiff.Result diff = MapMarkerEntityDiff.between(rendered,
        MapMarkerLayout.create(changedPoints, 10.4f));
check(diff.removeKeys.isEmpty() && diff.add.isEmpty() && diff.update.size() == 1,
        "new member updates only its existing cluster");
check(diff.update.get(0).memberIds.contains(405L),
        "updated cluster contains the entering camera");
~~~

Assert the Yekaterinburg cluster is absent from update. At zoom 14, moving camera
401 must produce one update and no add/remove.

- [ ] **Step 2: Verify RED**

Run .\test.ps1. Expected: compilation fails because MapMarkerEntityDiff is absent.

- [ ] **Step 3: Implement MapMarkerEntityDiff**

~~~java
public static Result between(
        Map<String, MapMarkerLayout.Entity> rendered,
        List<MapMarkerLayout.Entity> requested) {
    Map<String, MapMarkerLayout.Entity> desired = new LinkedHashMap<>();
    for (MapMarkerLayout.Entity entity : requested) desired.put(entity.key, entity);
    // Missing key: removeKeys. New key: add.
    // Same key with changed presentation: update.
    return new Result(removeKeys, add, update, desired);
}
~~~

Update stays separate from remove-plus-add so its PlacemarkMapObject survives.

- [ ] **Step 4: Verify GREEN**

Run .\test.ps1. Expected: ParserGeoTest: OK.

- [ ] **Step 5: Review and commit**

~~~powershell
git add src/ru/hudspeed/pro/MapMarkerEntityDiff.java test.ps1 tests/ParserGeoTest.java
git commit -m "feat: diff stable map entities"
~~~

---

### Task 3: Persistent MapKit Rendering

**Files:**
- Modify: src/ru/hudspeed/pro/MainActivity.java

**Interfaces:**
- Consumes MapMarkerLayout.create(points, zoom).
- Consumes MapMarkerEntityDiff.between(rendered, desired).
- Maintains renderedMarkerObjects and renderedMarkerEntities keyed by String.

- [ ] **Step 1: Confirm the failing behavior**

Confirm renderCameraMarkers calls cameraMarkerCollection.clusterPlacemarks()
whenever any camera enters or leaves. This global cluster rebuild is the defect.

- [ ] **Step 2: Replace clustered collection state**

Remove Cluster, ClusterListener, and ClusterizedPlacemarkCollection. Add:

~~~java
private MapObjectCollection cameraMarkerCollection;
private final Map<String, PlacemarkMapObject> renderedMarkerObjects =
        new HashMap<>();
private final Map<String, MapMarkerLayout.Entity> renderedMarkerEntities =
        new HashMap<>();
private final Map<Integer, ImageProvider> clusterIcons = new HashMap<>();
~~~

Create cameraMarkerCollection with map.getMapObjects().addCollection(). Never
clear the root map collection.

- [ ] **Step 3: Apply only diff operations**

Keep CameraMarkerDiff only for coverage polygons. Compute:

~~~java
List<MapMarkerLayout.Entity> entities =
        MapMarkerLayout.create(points, map.getCameraPosition().getZoom());
MapMarkerEntityDiff.Result markerDiff =
        MapMarkerEntityDiff.between(renderedMarkerEntities, entities);
~~~

Remove only removeKeys. For update, retain the existing PlacemarkMapObject and
change only differing properties. Add only add. Store markerDiff.desired after
successful application. Remove every marker clear() and clusterPlacemarks().

- [ ] **Step 4: Add exact helpers**

~~~java
private PlacemarkMapObject addMarkerEntity(MapMarkerLayout.Entity entity)
private void updateMarkerEntity(PlacemarkMapObject marker,
        MapMarkerLayout.Entity previous, MapMarkerLayout.Entity current)
private IconStyle individualMarkerStyle()
private IconStyle clusterMarkerStyle()
private ImageProvider clusterIcon(int count)
~~~

Clusters use cached createClusterIcon(count), no rotation, z-index 20, and no
tap listener. Individuals reuse iconForCamera(), shootingBearing(), user data,
and the camera tap listener. Unchanged entities receive no MapKit calls.

- [ ] **Step 5: Preserve coverage**

Coverage remains visible at zoom >= COVERAGE_MIN_ZOOM. Threshold changes may
clear only cameraCoverageCollection and cannot mutate markers or the GPS object.

- [ ] **Step 6: Verify and audit**

~~~powershell
.\test.ps1
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
.\gradlew.bat --no-daemon compileReleaseJavaWithJavac
~~~

Expected: ParserGeoTest: OK and BUILD SUCCESSFUL. Confirm no clusterPlacemarks,
no marker collection clear, and isolated coverage clearing.

- [ ] **Step 7: Review and commit**

MainActivity contains earlier approved work; preserve it intact.

~~~powershell
git add src/ru/hudspeed/pro/MainActivity.java
git commit -m "fix: preserve unchanged map markers"
~~~

---

### Task 4: Current Release Description

**Files:**
- Modify: src/ru/hudspeed/pro/ReleaseHistory.java
- Modify: src/ru/hudspeed/pro/MainActivity.java
- Modify: tests/ParserGeoTest.java
- Modify: build.gradle
- Modify: README.md

**Interfaces:**
- Produces ReleaseHistory.find(String), returning Entry or null.
- Consumes BuildConfig.VERSION_NAME in showAboutDialog().

- [ ] **Step 1: Write the failing tests**

~~~java
ReleaseHistory.Entry current = ReleaseHistory.find("4.9.3");
check(current != null && !current.changes.trim().isEmpty(),
        "current release has a visible change description");
check(ReleaseHistory.find("missing") == null,
        "unknown release has no fabricated description");
~~~

Expect versions 4.9.3, 4.9.2, 4.9.1, 4.9.0, 4.8.1, 4.8.0, 4.7.1.

- [ ] **Step 2: Verify RED**

Run .\test.ps1. Expected: missing find() or missing 4.9.3.

- [ ] **Step 3: Add entries and lookup**

~~~java
new Entry("4.9.3", "Устранено моргание значков: неизменившиеся одиночные "
        + "объекты и группы сохраняются при масштабировании и прокрутке карты."),
new Entry("4.9.2", "Добавлена автоматическая проверка RadarBase при холодном "
        + "запуске и дифференциальное обновление объектов карты с 20% буфером."),
~~~

Implement find(String) by iterating the immutable list.

- [ ] **Step 4: Show current changes without duplication**

Below the current version show Изменения текущей версии and the matching
description. Skip that version in the older release loop.

- [ ] **Step 5: Bump, document, verify, and commit**

Set versionCode 38 and versionName 4.9.3. Update README to describe stable
custom clustering. Run .\test.ps1 and expect ParserGeoTest: OK.

~~~powershell
git add src/ru/hudspeed/pro/ReleaseHistory.java src/ru/hudspeed/pro/MainActivity.java tests/ParserGeoTest.java build.gradle README.md
git commit -m "feat: document release 4.9.3"
~~~

---

### Task 5: Release and Device Verification

**Files:**
- Generate: outputs/GPS-AntiRadar.apk

**Interfaces:**
- Produces ru.gpsantiradar.app version 4.9.3.

- [ ] **Step 1: Run complete verification**

~~~powershell
.\test.ps1
git diff --check
.\build.ps1
~~~

Expected: ParserGeoTest: OK, no whitespace errors, BUILD SUCCESSFUL.

- [ ] **Step 2: Install without clearing data**

~~~powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb install -r "outputs\GPS-AntiRadar.apk"
~~~

Expected: Success.

- [ ] **Step 3: Exercise rendering**

Cold-start the Activity. At a location containing groups and individuals,
perform repeated zoom and pan gestures and let new buffered objects enter.
Record the screen during the gestures and inspect frames for disappearance of
unaffected icons. Confirm the Activity remains focused and AndroidRuntime has no
errors. The JVM entity-diff test provides the deterministic assertion that
unchanged keys produce no MapKit operation.

- [ ] **Step 4: Verify About and package**

UI Automator contains Версия 4.9.3, Изменения текущей версии, its description,
and historical Версия 4.9.2. dumpsys package reports versionCode=38 and
versionName=4.9.3.

- [ ] **Step 5: Record artifact**

Report the absolute APK path, byte size, and SHA-256.
