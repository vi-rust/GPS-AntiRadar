param([string]$Project = (Split-Path -Parent $PSScriptRoot))

$ErrorActionPreference = "Stop"

function Assert-Contains([string]$Text, [string]$Pattern, [string]$Message) {
    if ($Text -notmatch $Pattern) { throw $Message }
}

function Test-ActiveGradleDependency(
        [string]$Gradle,
        [string]$Configuration,
        [string]$Coordinate) {
    $activeLinePattern = "(?m)^[ \t]*" + [regex]::Escape($Configuration) +
            "[ \t]+'" + [regex]::Escape($Coordinate) + "'[ \t]*\r?$"
    return $Gradle -cmatch $activeLinePattern
}

$database = Get-Content -Raw -Encoding UTF8 (Join-Path $Project "src\ru\hudspeed\pro\CameraDatabase.java")
$tracking = Get-Content -Raw -Encoding UTF8 (Join-Path $Project "src\ru\hudspeed\pro\TrackingService.java")
$activity = Get-Content -Raw -Encoding UTF8 (Join-Path $Project "src\ru\hudspeed\pro\MainActivity.java")
$application = Get-Content -Raw -Encoding UTF8 (Join-Path $Project "src\ru\hudspeed\pro\GpsAntiRadarApplication.java")
$mapKitLifecycle = Get-Content -Raw -Encoding UTF8 (Join-Path $Project "src\ru\hudspeed\pro\MapKitLifecycle.java")
$updateState = Get-Content -Raw -Encoding UTF8 (Join-Path $Project "src\ru\hudspeed\pro\RadarBaseUpdateState.java")
$updater = Get-Content -Raw -Encoding UTF8 (Join-Path $Project "src\ru\hudspeed\pro\RadarBaseUpdater.java")
$listenerRegistry = Get-Content -Raw -Encoding UTF8 (Join-Path $Project "src\ru\hudspeed\pro\RadarBaseUpdateListenerRegistry.java")
$snapshot = Get-Content -Raw -Encoding UTF8 (Join-Path $Project "src\ru\hudspeed\pro\DrivingSnapshot.java")
$snapshotAdapter = Get-Content -Raw -Encoding UTF8 (Join-Path $Project "src\ru\hudspeed\pro\DrivingSnapshotIntent.java")
$sharedMapLayer = Get-Content -Raw -Encoding UTF8 (Join-Path $Project "src\ru\hudspeed\pro\SharedCameraMapLayer.java")
$manifestPath = Join-Path $Project "AndroidManifest.xml"
$manifest = [xml](Get-Content -Raw -Encoding UTF8 $manifestPath)
$androidNamespace = "http://schemas.android.com/apk/res/android"
$namespaceManager = New-Object System.Xml.XmlNamespaceManager($manifest.NameTable)
$namespaceManager.AddNamespace("android", $androidNamespace)

Assert-Contains $database 'setWriteAheadLoggingEnabled\(true\)' "CameraDatabase must enable WAL for concurrent readers"
Assert-Contains $database 'beginTransactionNonExclusive\(\)' "RadarBase replacement must use a non-exclusive WAL transaction"
Assert-Contains $database 'catch \(SQLiteDatabaseLockedException \| SQLiteTableLockedException contention\)' "nearby reads must handle SQLITE_BUSY/LOCKED without crashing the service"
if ($database -match '\.beginTransaction\(\)') {
    throw "exclusive SQLite transactions are forbidden during RadarBase replacement"
}

$begin = $database.IndexOf("beginTransactionNonExclusive()")
$delete = $database.IndexOf('db.delete("cameras"')
$success = $database.IndexOf("setTransactionSuccessful()")
$end = $database.IndexOf("endTransaction()")
if ($begin -lt 0 -or $delete -lt $begin -or $success -lt $delete -or $end -lt $success) {
    throw "delete/insert replacement must remain inside one atomic transaction"
}

Assert-Contains $tracking 'boolean candidatesUnavailable = candidates == null;' "TrackingService must distinguish transient contention from an empty radar scan"
Assert-Contains $tracking 'boolean scanPerformed = scanDecision\.scanRequested && !candidatesUnavailable;' "transient contention must prevent scan acceptance"
Assert-Contains $tracking 'if \(candidates == null\) candidates = Collections\.emptyList\(\);' "transient contention must not be iterated as a successful empty query"
Assert-Contains $tracking 'radarScanGate\.accept\(scanDecision\)' "gpsRecovered must be consumed only after an accepted radar scan"
$update = $tracking.IndexOf("alertTracker.update(")
$accept = $tracking.IndexOf("radarScanGate.accept(scanDecision)")
if ($update -lt 0 -or $accept -lt $update) {
    throw "radar scan recovery was consumed before the tracker accepted the scan"
}

Assert-Contains $sharedMapLayer 'double latPadding = \(north - south\) \* 0\.20;' "latitude map-query padding must be exactly 20 percent"
Assert-Contains $sharedMapLayer 'double lonPadding = \(east - west\) \* 0\.20;' "longitude map-query padding must be exactly 20 percent"
if ($sharedMapLayer -match 'Math\.max\(0\.02,') {
    throw "viewport queries must not impose a minimum degree padding"
}
Assert-Contains $sharedMapLayer 'MapMarkerEntityDiff\.between' "shared map rendering must preserve world-anchored entity diffing"
Assert-Contains $sharedMapLayer 'interface Host' "shared map rendering must expose host callbacks"
Assert-Contains $sharedMapLayer 'void onMarkerPresentationChanged\(\)' "marker changes must explicitly invalidate the host presentation"
foreach ($method in @("loadInitial", "refreshVisible", "updateCurrentLocation",
        "moveToCurrentLocation", "zoomBy", "pauseFollowing", "resumeFollowing", "destroy")) {
    Assert-Contains $sharedMapLayer ([regex]::Escape("void $method(")) "SharedCameraMapLayer must expose $method"
}
Assert-Contains $sharedMapLayer 'removeCameraListener' "destroy must remove the layer camera listener"
Assert-Contains $sharedMapLayer 'generation == cameraLoadGeneration && !destroyed' "stale camera loads must not render after replacement or destroy"
Assert-Contains $sharedMapLayer 'this\.context = context\.getApplicationContext\(\);' "async map work must not retain an Activity context"
Assert-Contains $sharedMapLayer 'updateCurrentLocation\(double latitude, double longitude, float speedKmh\)' "location updates must name the movement input speedKmh"
Assert-Contains $sharedMapLayer 'mapCenteredOnGps && speedKmh < 1f' "stationary updates below 1 km/h must not keep moving the followed map"
Assert-Contains $activity 'snapshot\.latitude, snapshot\.longitude, snapshot\.speedKmh' "phone map following must use snapshot speed"
$markerChangesStart = $sharedMapLayer.IndexOf("boolean markerChanges")
$markerRemoveStart = $sharedMapLayer.IndexOf("for (String key", $markerChangesStart)
$markerChangesBlock = if ($markerChangesStart -ge 0 -and $markerRemoveStart -gt $markerChangesStart) {
    $sharedMapLayer.Substring($markerChangesStart, $markerRemoveStart - $markerChangesStart)
} else { "" }
Assert-Contains $markerChangesBlock 'if \(markerChanges\)[\s\S]*onMarkerPresentationChanged\(\)' "only an entity add, update, or removal may invalidate the camera hint"
$activityMapHostStart = $activity.IndexOf("new SharedCameraMapLayer.Host")
$activityMapHostEnd = $activity.IndexOf("});", $activityMapHostStart)
$activityMapHost = if ($activityMapHostStart -ge 0 -and $activityMapHostEnd -gt $activityMapHostStart) {
    $activity.Substring($activityMapHostStart, $activityMapHostEnd - $activityMapHostStart)
} else { "" }
Assert-Contains $activityMapHost 'onMarkerPresentationChanged\(\)[\s\S]*cameraHintView\.setVisibility\(View\.GONE\)[\s\S]*hintGeneration\+\+' "phone marker changes must immediately invalidate the visible hint"
$initialLoadStart = $sharedMapLayer.IndexOf("public void loadInitial")
$refreshVisibleStart = $sharedMapLayer.IndexOf("public void refreshVisible", $initialLoadStart)
$initialLoad = if ($initialLoadStart -ge 0 -and $refreshVisibleStart -gt $initialLoadStart) {
    $sharedMapLayer.Substring($initialLoadStart, $refreshVisibleStart - $initialLoadStart)
} else { "" }
Assert-Contains $initialLoad 'initialLoadGeneration' "the first viewport refresh must not cancel moveToData bounds loading"
if ($initialLoad -match [regex]::Escape("++cameraLoadGeneration")) {
    throw "loadInitial must not share the viewport generation canceled by MainActivity.onStart"
}
if ($sharedMapLayer -match 'cameraMarkerCollection\.clear\(\)|clusterPlacemarks\(') {
    throw "shared camera markers must remain incremental and must not use MapKit clustering"
}
if ($activity -match 'renderedMarkerObjects|renderedMarkerEntities|renderCameraMarkers') {
    throw "MainActivity must not own shared camera marker rendering"
}

$drivingReceiverStart = $activity.IndexOf("private final BroadcastReceiver receiver")
$drivingReceiverEnd = $activity.IndexOf("private final BroadcastReceiver radarBaseReceiver",
        $drivingReceiverStart)
$drivingReceiver = if ($drivingReceiverStart -ge 0 -and
        $drivingReceiverEnd -gt $drivingReceiverStart) {
    $activity.Substring($drivingReceiverStart, $drivingReceiverEnd - $drivingReceiverStart)
} else { "" }
Assert-Contains $drivingReceiver 'DrivingSnapshotIntent\.from\(intent\)' "MainActivity must parse each driving update through DrivingSnapshotIntent"
if ($drivingReceiver -match 'get(?:Float|Int|Long|Double|String)Extra\(TrackingService\.EXTRA_') {
    throw "MainActivity must not duplicate TrackingService extra parsing"
}
if ($snapshot -match '\b(?:import\s+)?android\.') {
    throw "DrivingSnapshot must remain independent from Android"
}
foreach ($extra in @("EXTRA_SPEED", "EXTRA_ACCURACY", "EXTRA_DISTANCE", "EXTRA_CAMERA",
        "EXTRA_CAMERA_ID", "EXTRA_LIMIT", "EXTRA_ALERT_DISTANCE", "EXTRA_LATITUDE",
        "EXTRA_LONGITUDE", "EXTRA_ALERT_STATE", "EXTRA_ALERT_ALGORITHM")) {
    Assert-Contains $snapshotAdapter ([regex]::Escape("TrackingService.$extra")) "DrivingSnapshotIntent must read $extra"
}
Assert-Contains $snapshotAdapter 'getFloatExtra\(TrackingService\.EXTRA_ACCURACY,\s*Float\.NaN\)' "missing accuracy must remain distinguishable in a driving snapshot"
if ([regex]::Matches($tracking, 'putExtra\(EXTRA_CAMERA_ID').Count -ne 2) {
    throw "TrackingService must include camera id in both update broadcasts"
}
if ([regex]::Matches($tracking, 'putExtra\(EXTRA_ACCURACY').Count -ne 2) {
    throw "TrackingService must include location accuracy in both update broadcasts"
}

if ($activity -match 'databaseView') {
    throw "database information must not remain in the speed HUD"
}
Assert-Contains -Text $activity -Pattern '\u041e\u0431\u044a\u0435\u043a\u0442\u043e\u0432 \u0432 \u0431\u0430\u0437\u0435:' -Message "About must show the database object count"
Assert-Contains -Text $activity -Pattern '\u041f\u043e\u0441\u043b\u0435\u0434\u043d\u044f\u044f \u0443\u0441\u043f\u0435\u0448\u043d\u0430\u044f \u0437\u0430\u0433\u0440\u0443\u0437\u043a\u0430:' -Message "About must show the last successful download"
Assert-Contains -Text $updater -Pattern '\u041e\u0431\u043d\u043e\u0432\u043b\u0435\u043d\u0438\u0435 \u0431\u0430\u0437\u044b RadarBase \u043d\u0430\u0447\u0430\u0442\u043e' -Message "automatic and manual downloads must show the same start feedback"

if ($activity -match 'HttpURLConnection|new URL\(|GZIPInputStream|RADARBASE_URL|importRadarBase\(') {
    throw "MainActivity must not own RadarBase network or import work"
}
Assert-Contains -Text $activity -Pattern 'radarBaseUpdater\(\)\.requestUpdate\(\)' -Message "the phone update action must delegate to the application updater"
Assert-Contains -Text $activity -Pattern 'addListener\(radarBaseUpdateListener,\s*true\)' -Message "onStart must replay the latest update state"
Assert-Contains -Text $activity -Pattern 'removeListener\(radarBaseUpdateListener\)' -Message "onStop must remove the update listener"

$onCreateStart = $application.IndexOf("@Override public void onCreate()")
$nextMethod = $application.IndexOf("public static synchronized boolean ensureMapKit", $onCreateStart)
$applicationOnCreate = if ($onCreateStart -ge 0 -and $nextMethod -gt $onCreateStart) {
    $application.Substring($onCreateStart, $nextMethod - $onCreateStart)
} else { "" }
if ([regex]::Matches($applicationOnCreate, 'radarBaseUpdater\.requestUpdate\(\)').Count -ne 1) {
    throw "Application.onCreate must request exactly one cold-process RadarBase update"
}
Assert-Contains -Text $application -Pattern 'new RadarBaseUpdater\(this,\s*radarBaseUpdateGuard\)' -Message "Application must own the process RadarBase updater"
if ($application -match 'ProcessLaunchGuard|claimRadarBaseStartupUpdate|tryStartRadarBaseUpdate|finishRadarBaseUpdate') {
    throw "Application cold-start ownership must not remain split across Activity guards"
}

Assert-Contains -Text $activity -Pattern 'overspeedThreshold\.setMax\(AppSettings\.MAX_OVERSPEED_THRESHOLD_KMH\)' -Message "menu must expose the full 0 to 20 km/h beep range"
Assert-Contains -Text $activity -Pattern 'putInt\(AppSettings\.OVERSPEED_THRESHOLD' -Message "menu must persist the beep tolerance"
Assert-Contains -Text $tracking -Pattern 'getInt\(AppSettings\.OVERSPEED_THRESHOLD' -Message "service must read the persisted beep tolerance"
Assert-Contains -Text $tracking -Pattern 'AppSettings\.DEFAULT_OVERSPEED_THRESHOLD_KMH' -Message "service must use the default beep tolerance"
Assert-Contains -Text $tracking -Pattern 'overspeedCandidate\(' -Message "beep selection must call the configurable candidate API"
Assert-Contains -Text $tracking -Pattern 'speedKmh, overspeedThresholdKmh\)' -Message "beep selection must use the configured tolerance"

Assert-Contains -Text $application -Pattern 'RadarBaseUpdateSingleFlight radarBaseUpdateGuard' -Message "the update guard must live for the whole process"
Assert-Contains -Text $updateState -Pattern 'enum Status \{\s*IDLE,\s*STARTED,\s*UNCHANGED,\s*SUCCESS,\s*ERROR,\s*ALREADY_RUNNING\s*\}' -Message "RadarBase update states must expose the shared lifecycle"
Assert-Contains -Text $listenerRegistry -Pattern 'volatile RadarBaseUpdateState latestState' -Message "the latest update state must be visible process-wide"
Assert-Contains -Text $updater -Pattern 'Handler\(Looper\.getMainLooper\(\)\)' -Message "update listeners must be dispatched on the main thread"
Assert-Contains -Text $updater -Pattern 'interface Listener extends RadarBaseUpdateListenerRegistry\.Listener' -Message "the public updater listener API must use the race-safe registry"
Assert-Contains -Text $updater -Pattern 'if \(!gate\.tryStart\(\)\)' -Message "all update requests must use the process single-flight gate"
Assert-Contains -Text $updater -Pattern 'new Thread\([\s\S]*"radarbase-download"\)\.start\(\)' -Message "RadarBase network work must run on the download thread"

$notModifiedStart = $updater.IndexOf("if (status == HttpURLConnection.HTTP_NOT_MODIFIED)")
$notModifiedEnd = $updater.IndexOf("if (status != HttpURLConnection.HTTP_OK)", $notModifiedStart)
$notModified = if ($notModifiedStart -ge 0 -and $notModifiedEnd -gt $notModifiedStart) {
    $updater.Substring($notModifiedStart, $notModifiedEnd - $notModifiedStart)
} else { "" }
Assert-Contains -Text $notModified -Pattern 'UNCHANGED' -Message "HTTP 304 must publish UNCHANGED"
if ($notModified -match 'LAST_SUCCESSFUL_DOWNLOAD|System\.currentTimeMillis') {
    throw "HTTP 304 must not update the last successful download time"
}

$import = $updater.IndexOf("result = db.importRadarBase(source)")
$timestamp = $updater.IndexOf("putLong(AppSettings.RADARBASE_LAST_SUCCESSFUL_DOWNLOAD")
$broadcast = $updater.IndexOf("sendBroadcast(")
$successState = $updater.IndexOf("RadarBaseUpdateState.Status.SUCCESS")
if ($import -lt 0 -or $timestamp -lt $import -or $broadcast -lt $timestamp -or $successState -lt $broadcast) {
    throw "HTTP 200 must import, save time, broadcast, then publish SUCCESS"
}
Assert-Contains -Text $updater -Pattern 'System\.currentTimeMillis\(\)' -Message "the stored successful-import time must be current"
Assert-Contains -Text $updater -Pattern 'new Intent\(ACTION_DATABASE_UPDATED\)\.setPackage\(context\.getPackageName\(\)\)' -Message "database replacement broadcast must remain package-scoped"
Assert-Contains -Text $activity -Pattern 'aboutDatabaseCountView' -Message "the open About count must remain refreshable"
Assert-Contains -Text $activity -Pattern 'aboutLastDownloadView' -Message "the open About timestamp must remain refreshable"
$receiverStart = $activity.IndexOf("private final BroadcastReceiver radarBaseReceiver")
$receiverEnd = $activity.IndexOf("@Override protected void onCreate", $receiverStart)
$receiver = if ($receiverStart -ge 0 -and $receiverEnd -gt $receiverStart) {
    $activity.Substring($receiverStart, $receiverEnd - $receiverStart)
} else { "" }
Assert-Contains -Text $receiver -Pattern 'refreshAboutDatabaseInfo\(\)' -Message "a successful update broadcast must refresh an open About dialog"

Assert-Contains -Text $mapKitLifecycle -Pattern "interface Delegate" -Message "MapKit lifecycle must delegate global start and stop"
Assert-Contains -Text $application -Pattern "MapKitLifecycle mapKitLifecycle" -Message "Application must own the process MapKit lifecycle"
Assert-Contains -Text $application -Pattern "void acquireMapKit" -Message "Application must expose MapKit acquisition"
Assert-Contains -Text $application -Pattern "void releaseMapKit" -Message "Application must expose MapKit release"
Assert-Contains -Text $application -Pattern "MapKitFactory.getInstance...onStart" -Message "Application lifecycle must start MapKit"
Assert-Contains -Text $application -Pattern "getInstance...onStop" -Message "Application lifecycle must stop MapKit"
if ($activity -match "MapKitFactory.getInstance...on(?:Start|Stop)") {
    throw "MainActivity must acquire and release the application-owned MapKit lifecycle"
}
Assert-Contains -Text $activity -Pattern "applyImmersiveMode" -Message "MainActivity must define immersive phone mode"
Assert-Contains -Text $activity -Pattern "onWindowFocusChanged" -Message "MainActivity must restore immersive mode after focus returns"
Assert-Contains -Text $activity -Pattern "hasFocus. applyImmersiveMode" -Message "immersive mode must restore only when focus returns"
Assert-Contains -Text $activity -Pattern "WindowInsets.Type.displayCutout" -Message "HUD must only inset for the display cutout"
$onCreateStart = $activity.IndexOf("@Override protected void onCreate")
$onCreateEnd = $activity.IndexOf("private boolean initializeMapKitSafely", $onCreateStart)
$activityOnCreate = if ($onCreateStart -ge 0 -and $onCreateEnd -gt $onCreateStart) {
    $activity.Substring($onCreateStart, $onCreateEnd - $onCreateStart)
} else { "" }
Assert-Contains -Text $activityOnCreate -Pattern "applyImmersiveMode" -Message "onCreate must apply immersive phone mode"
$onResumeStart = $activity.IndexOf("@Override protected void onResume")
$onResumeEnd = $activity.IndexOf("@Override", $onResumeStart + 1)
$activityOnResume = if ($onResumeStart -ge 0 -and $onResumeEnd -gt $onResumeStart) {
    $activity.Substring($onResumeStart, $onResumeEnd - $onResumeStart)
} else { "" }
Assert-Contains -Text $activityOnResume -Pattern "applyImmersiveMode" -Message "onResume must restore immersive phone mode"

$buildGradle = Get-Content -Raw -Encoding UTF8 (Join-Path $Project "build.gradle")
Assert-Contains $buildGradle 'androidComponents\s*\{[\s\S]*beforeVariants\(selector\(\)\.withBuildType\("release"\)\)[\s\S]*enableUnitTest\s*=\s*true' "AGP must create a real release unit-test variant"
foreach ($dependency in @(
        [pscustomobject]@{ Configuration = "implementation"; Coordinate = "androidx.car.app:app:1.7.0" },
        [pscustomobject]@{ Configuration = "implementation"; Coordinate = "androidx.car.app:app-projected:1.7.0" },
        [pscustomobject]@{ Configuration = "testImplementation"; Coordinate = "androidx.car.app:app-testing:1.7.0" },
        [pscustomobject]@{ Configuration = "testImplementation"; Coordinate = "androidx.test:core:1.6.1" },
        [pscustomobject]@{ Configuration = "testImplementation"; Coordinate = "junit:junit:4.13.2" },
        [pscustomobject]@{ Configuration = "testImplementation"; Coordinate = "org.robolectric:robolectric:4.16.1" })) {
    if (-not (Test-ActiveGradleDependency $buildGradle $dependency.Configuration $dependency.Coordinate)) {
        throw "Android Auto dependency is missing: $($dependency.Configuration) '$($dependency.Coordinate)'"
    }
}
$inactiveAndroidAutoDependency = "// implementation 'androidx.car.app:app:1.7.0'"
if (Test-ActiveGradleDependency $inactiveAndroidAutoDependency "implementation" "androidx.car.app:app:1.7.0") {
    throw "commented Android Auto dependencies must not satisfy the active dependency contract"
}
$wrongScopeAndroidAutoDependency = "testImplementation 'androidx.car.app:app:1.7.0'"
if (Test-ActiveGradleDependency $wrongScopeAndroidAutoDependency "implementation" "androidx.car.app:app:1.7.0") {
    throw "Android Auto dependencies must use their required Gradle configuration"
}

$automotiveDescriptorPath = Join-Path $Project "res\xml\automotive_app_desc.xml"
if (-not (Test-Path $automotiveDescriptorPath)) {
    throw "Android Auto automotive descriptor is missing"
}
$automotiveDescriptor = [xml](Get-Content -Raw -Encoding UTF8 $automotiveDescriptorPath)
if ($automotiveDescriptor.automotiveApp.uses.name -ne "template") {
    throw "Android Auto descriptor must declare the template app category"
}

foreach ($permission in @(
        "androidx.car.app.ACCESS_SURFACE",
        "androidx.car.app.NAVIGATION_TEMPLATES")) {
    $permissionNode = $manifest.SelectSingleNode(
            "/manifest/uses-permission[@android:name='$permission']", $namespaceManager)
    if ($null -eq $permissionNode) {
        throw "Android Auto manifest permission is missing: $permission"
    }
}

$applicationNode = $manifest.SelectSingleNode("/manifest/application", $namespaceManager)
$minCarApi = $applicationNode.SelectSingleNode(
        "meta-data[@android:name='androidx.car.app.minCarApiLevel']", $namespaceManager)
if ($null -eq $minCarApi -or $minCarApi.GetAttribute("value", $androidNamespace) -ne "2") {
    throw "Android Auto minimum Car API metadata must be 2"
}
$descriptorMetadata = $applicationNode.SelectSingleNode(
        "meta-data[@android:name='com.google.android.gms.car.application']", $namespaceManager)
if ($null -eq $descriptorMetadata -or
        $descriptorMetadata.GetAttribute("resource", $androidNamespace) -ne
                "@xml/automotive_app_desc") {
    throw "Android Auto descriptor metadata is missing"
}
$carServiceNode = $applicationNode.SelectSingleNode(
        "service[@android:name='.GpsCarAppService']", $namespaceManager)
if ($null -eq $carServiceNode -or
        $carServiceNode.GetAttribute("exported", $androidNamespace) -ne "true") {
    throw "GpsCarAppService must be exported"
}
$serviceAction = $carServiceNode.SelectSingleNode(
        "intent-filter/action[@android:name='androidx.car.app.CarAppService']",
        $namespaceManager)
$serviceCategory = $carServiceNode.SelectSingleNode(
        "intent-filter/category[@android:name='androidx.car.app.category.NAVIGATION']",
        $namespaceManager)
if ($null -eq $serviceAction -or $null -eq $serviceCategory) {
    throw "GpsCarAppService must declare the CarAppService action and navigation category"
}

$carSourceRoot = Join-Path $Project "src\ru\hudspeed\pro"
$requiredCarSources = @(
        "GpsCarAppService.java", "GpsCarSession.java", "CarSetupScreen.java",
        "CarSurfaceSpec.java", "CarSurfaceController.java", "CarMapPresentation.java",
        "CarMapScreen.java", "CarMapGestureController.java")
foreach ($sourceName in $requiredCarSources) {
    $sourcePath = Join-Path $carSourceRoot $sourceName
    if (-not (Test-Path $sourcePath)) {
        throw "Android Auto source is missing: $sourceName"
    }
}
$carService = Get-Content -Raw -Encoding UTF8 (Join-Path $carSourceRoot "GpsCarAppService.java")
$carSession = Get-Content -Raw -Encoding UTF8 (Join-Path $carSourceRoot "GpsCarSession.java")
$surfaceController = Get-Content -Raw -Encoding UTF8 (
        Join-Path $carSourceRoot "CarSurfaceController.java")
$carPresentation = Get-Content -Raw -Encoding UTF8 (
        Join-Path $carSourceRoot "CarMapPresentation.java")
$carSetupScreen = Get-Content -Raw -Encoding UTF8 (
        Join-Path $carSourceRoot "CarSetupScreen.java")
$carMapScreen = Get-Content -Raw -Encoding UTF8 (Join-Path $carSourceRoot "CarMapScreen.java")
$carGestures = Get-Content -Raw -Encoding UTF8 (
        Join-Path $carSourceRoot "CarMapGestureController.java")

Assert-Contains $carService 'addAllowedHosts\(androidx\.car\.app\.R\.array\.hosts_allowlist_sample\)' "Car service must load the official projected-host allowlist"
Assert-Contains $carService 'new GpsCarSession\(\)' "Car service must create GpsCarSession"
Assert-Contains $carSession 'ACCESS_FINE_LOCATION' "Car session must require fine location"
Assert-Contains $carSession 'ensureMapKit' "Car session must require a ready MapKit"
Assert-Contains $carSession 'TrackingService\.ACTION_START' "Car session must start the shared tracker"
if ($carSession -match 'new\s+RadarBaseUpdater|new\s+StrelkaAlertTracker') {
    throw "Car session must not create a second updater or alert tracker"
}
Assert-Contains $carSession 'DrivingSnapshotIntent\.from' "Car session must decode shared tracking snapshots"
Assert-Contains $carSession 'unregisterReceiver' "Car session must unregister its update receiver"
Assert-Contains $carSession 'getCarService\(ScreenManager\.class\)\.push\(' "surface failures must open a setup MessageTemplate"
Assert-Contains $carSession 'new CarSetupScreen\(getCarContext\(\),\s*true,\s*true,\s*message\)' "surface failure details must reach CarSetupScreen"
Assert-Contains $carSetupScreen 'surfaceError' "CarSetupScreen must render surface initialization errors"
if ($carSession -match 'stopService') {
    throw "Car session destruction must not stop the shared TrackingService"
}
Assert-Contains $surfaceController 'implements SurfaceCallback' "Car controller must implement SurfaceCallback"
Assert-Contains $surfaceController 'setSurfaceCallback\(this\)' "Car controller must register its surface callback"
Assert-Contains $surfaceController 'setSurfaceCallback\(null\)' "Car controller destroy must clear its callback"
Assert-Contains $surfaceController 'Surface::release' "production must release every host Surface"
if ($surfaceController -match 'IdentityHashMap|IdentityHashSet|releasedSurfaces|surfaceTokens') {
    throw "surface ownership must not retain wrapper references in identity collections"
}
Assert-Contains $surfaceController 'surfaceResource != null && activeSurface == surface' "an exact repeated wrapper must not be released and passed back to the factory"
Assert-Contains $surfaceController 'Surface ownedSurface = activeSurface' "surface destruction must follow the ordered callback contract"
Assert-Contains $surfaceController 'callbackSurface != ownedSurface' "a distinct destroy wrapper must also be released"
Assert-Contains $surfaceController '!activeSpec\.equals\(callbackSpec\)' "different-spec stale destroy must not release the replacement"
Assert-Contains $surfaceController 'catch \(RuntimeException \| LinkageError error\)' "surface creation failures must be contained"
Assert-Contains $surfaceController 'notifySurfaceFailure\(error\)' "surface creation failures must notify the session"
if ($surfaceController -match 'private Rect (?:stableArea|visibleArea)') {
    throw "safe-area rectangles must not survive their surface generation"
}
if ([regex]::Matches($surfaceController,
        'area == null \|\| area\.isEmpty\(\) \? null : new Rect\(area\)').Count -lt 2) {
    throw "empty stable and visible rectangles must clear the active generation"
}
$releaseIndex = $surfaceController.IndexOf("releaseSurface()")
$createIndex = $surfaceController.IndexOf("surfaceFactory.create", $releaseIndex)
if ($releaseIndex -lt 0 -or $createIndex -lt $releaseIndex) {
    throw "Existing car surface must be released before creating its replacement"
}
Assert-Contains $surfaceController 'createVirtualDisplay' "Car surface must create a VirtualDisplay"
Assert-Contains $surfaceController 'new Presentation' "Car surface must use Presentation"
Assert-Contains $surfaceController 'presentation\.dismiss\(\)' "Car Presentation must be dismissed"
Assert-Contains $surfaceController 'virtualDisplay\.release\(\)' "Car VirtualDisplay must be released"
$contentConstruction = $surfaceController.IndexOf("content = new CarMapPresentation")
$contentStart = $surfaceController.IndexOf("content.start()", $contentConstruction)
$contentAttach = $surfaceController.IndexOf(
        "presentation.setContentView(content.rootView())", $contentStart)
if ($contentConstruction -lt 0 -or $contentStart -lt $contentConstruction -or
        $contentAttach -lt $contentStart) {
    throw "CarMapPresentation must be assigned before native start and view attachment"
}
$presentationConstructor = $carPresentation.IndexOf("public CarMapPresentation(")
$presentationStart = $carPresentation.IndexOf("public void start()", $presentationConstructor)
$constructorBody = if ($presentationConstructor -ge 0 -and
        $presentationStart -gt $presentationConstructor) {
    $carPresentation.Substring(
            $presentationConstructor, $presentationStart - $presentationConstructor)
} else { "" }
if ($constructorBody -match 'new MapView|startMap\(') {
    throw "CarMapPresentation construction must not start native MapKit resources"
}
Assert-Contains $carPresentation 'new SharedCameraMapLayer' "Car presentation must use the shared camera layer"
Assert-Contains $carPresentation 'DrivingHudPresentation\.from' "Car HUD must use shared presentation rules"
if ($carPresentation -match 'alertAlgorithm') {
    throw "Algorithm details must not be rendered in the car speed HUD"
}
Assert-Contains $carPresentation 'onStableAreaChanged' "Car HUD must respond to stable-area changes"
Assert-Contains $carPresentation 'onVisibleAreaChanged' "Car map must respond to visible-area changes"
if ([regex]::Matches($carPresentation,
        'area == null \|\| area\.isEmpty\(\) \? null : new Rect\(area\)').Count -lt 2) {
    throw "Car presentation must clear empty safe-area rectangles"
}
Assert-Contains $carPresentation 'params\.leftMargin = dp\(8\)' "empty stable area must restore the default HUD inset"
Assert-Contains $carPresentation 'params\.bottomMargin = dp\(8\)' "empty stable area must restore the default HUD bottom inset"
Assert-Contains $carPresentation 'params\.width = dp\(300\)' "empty stable area must restore the default HUD width"
Assert-Contains $carPresentation 'mapWindow\.setFocusRect\(null\)' "empty visible area must clear the MapKit focus rect"
Assert-Contains $carPresentation 'isDarkMode' "Car HUD must follow car dark mode"
Assert-Contains $carMapScreen 'NavigationTemplate\.Builder' "Car map must use NavigationTemplate"
Assert-Contains $carMapScreen 'Action\.PAN' "Car map must expose the standard PAN action"
Assert-Contains $carMapScreen 'setPanModeListener' "Car map must forward pan mode changes"
Assert-Contains $carGestures 'Math\.log\(scaleFactor\).*Math\.log\(2' "Car scale must use logarithmic zoom"
Assert-Contains $carGestures 'screenToWorld' "Car pan/click gestures must convert screen coordinates"
Assert-Contains $carGestures 'Animation\.Type\.SMOOTH' "Car fling must use a short smooth map animation"

foreach ($iconName in @(
        "ic_car_zoom_in.xml", "ic_car_zoom_out.xml",
        "ic_car_pan.xml", "ic_car_location.xml")) {
    $iconPath = Join-Path $Project "res\drawable\$iconName"
    if (-not (Test-Path $iconPath)) { throw "Android Auto icon is missing: $iconName" }
    $icon = [xml](Get-Content -Raw -Encoding UTF8 $iconPath)
    if ($icon.vector.path.Count -gt 1) {
        throw "Android Auto icon must be a simple monochrome vector: $iconName"
    }
}

Write-Output "AndroidSourceContractTest: OK"
