param([string]$Project = (Split-Path -Parent $PSScriptRoot))

$ErrorActionPreference = "Stop"

function Assert-Contains([string]$Text, [string]$Pattern, [string]$Message) {
    if ($Text -notmatch $Pattern) { throw $Message }
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
Write-Output "AndroidSourceContractTest: OK"
