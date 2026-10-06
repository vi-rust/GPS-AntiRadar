param([string]$Project = (Split-Path -Parent $PSScriptRoot))

$ErrorActionPreference = "Stop"

function Read-Source([string]$Name) {
    $Path = Join-Path $Project "src\main\kotlin\ru\gpsantiradar\app\$Name.kt"
    if (-not (Test-Path -LiteralPath $Path)) { throw "Kotlin source is missing: $Name.kt" }
    return Get-Content -Raw -Encoding UTF8 -LiteralPath $Path
}

function Assert-Contains([string]$Text, [string]$Pattern, [string]$Message) {
    if ($Text -notmatch $Pattern) { throw $Message }
}

function Test-ActiveGradleDependency(
        [string]$Gradle,
        [string]$Configuration,
        [string]$Coordinate) {
    $Pattern = '(?m)^[ \t]*' + [regex]::Escape($Configuration) +
            '\("' + [regex]::Escape($Coordinate) + '"\)[ \t]*\r?$'
    return $Gradle -cmatch $Pattern
}

$SourceRoot = Join-Path $Project "src\main\kotlin\ru\gpsantiradar\app"
$SourceTrees = @((Join-Path $Project "src"), (Join-Path $Project "tests"))
$JavaSources = @($SourceTrees | ForEach-Object {
    Get-ChildItem -LiteralPath $_ -Recurse -Filter "*.java"
})
if ($JavaSources.Count -ne 0) {
    throw "Application and test sources must be Kotlin-only: $($JavaSources.FullName -join ', ')"
}
$JvmInteropAnnotations = @($SourceTrees | ForEach-Object {
    Get-ChildItem -LiteralPath $_ -Recurse -Filter "*.kt" |
        Select-String -Pattern '@Jvm(?:Field|Static|Overloads)'
})
if ($JvmInteropAnnotations.Count -ne 0) {
    throw "Obsolete Java interoperability annotations remain: $($JvmInteropAnnotations.Path -join ', ')"
}

$RequiredSources = @(
    "AppSettings", "CameraDatabase", "CameraPoint", "DrivingHudPresentation",
    "DrivingSnapshot", "DrivingSnapshotIntent", "Geo", "GpsAntiRadarApplication",
    "MainActivity", "MapMarkerLayout", "MapVisualStyle", "RadarBaseParser",
    "RadarBaseUpdater", "SharedCameraMapLayer", "StrelkaAlertAlgorithm",
    "StrelkaAlertTracker", "StrelkaSoundPlayer", "TrackingService", "GpsCarAppService", "GpsCarSession",
    "CarSurfaceController", "CarMapPresentation", "CarMapGestureController",
    "CarMapScreen", "CarMenuScreen", "CarValueScreen",
    "CarAboutScreen", "ZoneDisplayMode", "ZoneObjectScope", "CarZoneDisplayScreen", "CarMapCameraState",
    "UserCameraDefaults"
)
foreach ($Name in $RequiredSources) { [void](Read-Source $Name) }

$Database = Read-Source "CameraDatabase"
$Settings = Read-Source "AppSettings"
$Tracking = Read-Source "TrackingService"
$Algorithm = Read-Source "StrelkaAlertAlgorithm"
$SoundPlayer = Read-Source "StrelkaSoundPlayer"
$Activity = Read-Source "MainActivity"
$Application = Read-Source "GpsAntiRadarApplication"
$SharedMapLayer = Read-Source "SharedCameraMapLayer"
$CarSession = Read-Source "GpsCarSession"
$SurfaceController = Read-Source "CarSurfaceController"
$CarPresentation = Read-Source "CarMapPresentation"
$CarGestures = Read-Source "CarMapGestureController"
$CarMenu = Read-Source "CarMenuScreen"
$CarValue = Read-Source "CarValueScreen"
$CarAbout = Read-Source "CarAboutScreen"

Assert-Contains $Database 'setWriteAheadLoggingEnabled\(true\)' "CameraDatabase must enable WAL"
Assert-Contains $Database 'beginTransactionNonExclusive\(\)' "RadarBase replacement must use a non-exclusive transaction"
Assert-Contains $Database 'CREATE TABLE IF NOT EXISTS user_objects' "User objects must have persistent local storage"
Assert-Contains $Database 'fun addUserObject\(' "User objects must be insertable"
Assert-Contains $Database 'fun updateUserObject\(' "User objects must be editable"
Assert-Contains $Database 'fun deleteUserObject\(' "User objects must be removable"
Assert-Contains $Database 'override fun onOpen\(' "User-object schema must self-repair when opened"
if ($Activity -match '\u041C\u043E\u0438 \u043E\u0431\u044A\u0435\u043A\u0442\u044B') {
    throw "The removed user-object menu item must not return"
}
Assert-Contains $Activity 'RadarBaseTypes\.allTypes\(\)' "User editor must expose every known object type"
Assert-Contains $Activity '\u0420\u0435\u0434\u0430\u043A\u0442\u0438\u0440\u043E\u0432\u0430\u0442\u044C' "User-marker hints must expose editing"
Assert-Contains $Activity '\u0423\u0434\u0430\u043B\u0438\u0442\u044C' "User-marker hints must expose deletion"
Assert-Contains $SharedMapLayer 'addInputListener\(' "Phone map must listen for long presses"
Assert-Contains $SharedMapLayer 'onMapLongTap\(' "Map long presses must reach the host"
Assert-Contains $SharedMapLayer 'MapObjectDragListener' "User markers must support native dragging"
Assert-Contains $SharedMapLayer 'marker\.isDraggable = enabled' "Only configured user markers may be dragged"
Assert-Contains $SharedMapLayer 'onUserCameraMoved\(camera, position\)' "Dropped user markers must be persisted"
Assert-Contains $Activity 'existing\.detachedCopy\(\)' "Editing must not mutate the rendered camera instance"
Assert-Contains $Activity 'val directionControl = steppedSeekControl\(' "Direction must be edited with a slider"
Assert-Contains $Activity 'val speedControl = steppedSeekControl\(' "Speed limit must be edited with a slider"
Assert-Contains $Activity 'val minusButton = sliderStepButton' "Editor sliders must have decrement buttons"
Assert-Contains $Activity 'val plusButton = sliderStepButton' "Editor sliders must have increment buttons"
Assert-Contains $Activity 'private fun sliderWithStepButtons\(' "All phone sliders must share step buttons"
if ($Activity -match 'controls\.addView\(seekBar') {
    throw "A phone settings slider is missing decrement and increment buttons"
}
Assert-Contains $SharedMapLayer 'Color\.rgb\(0, 150, 255\)' "User markers must use the requested blue outline"
Assert-Contains $SharedMapLayer 'strokeWidth = dp\(2\.5f\)' "User marker outline must be 2.5dp"
if ($Database -match '\.beginTransaction\(\)') {
    throw "Exclusive SQLite transactions are forbidden"
}
$Begin = $Database.IndexOf("beginTransactionNonExclusive()")
$Delete = $Database.IndexOf('db.delete("cameras"')
$Success = $Database.IndexOf("setTransactionSuccessful()")
$End = $Database.IndexOf("endTransaction()")
if ($Begin -lt 0 -or $Delete -lt $Begin -or $Success -lt $Delete -or $End -lt $Success) {
    throw "RadarBase delete/insert replacement must remain atomic"
}

Assert-Contains $Tracking 'val candidatesUnavailable = candidates == null' "Tracking must distinguish contention from an empty scan"
Assert-Contains $Tracking 'val scanPerformed = scanDecision\.scanRequested && !candidatesUnavailable' "Contention must prevent scan acceptance"
Assert-Contains $Tracking 'radarScanGate\.accept\(scanDecision\)' "Accepted scans must consume the scan gate"
$TrackerUpdate = $Tracking.IndexOf("alertTracker.update(")
$GateAccept = $Tracking.IndexOf("radarScanGate.accept(scanDecision)")
if ($TrackerUpdate -lt 0 -or $GateAccept -lt $TrackerUpdate) {
    throw "The tracker must update before the scan gate is accepted"
}
Assert-Contains $Tracking 'HeadingSelection\.forStrelka\(' "Tracking must separate visual and alert headings"
Assert-Contains $Tracking 'headings\.visualHeading' "Map updates must use the immediate visual heading"
foreach ($LegacyAlertSymbol in @("strelkaAlertsEnabled", "TextToSpeech", "roadAlertDistance", "speakRoadWarning")) {
    if ($Tracking.Contains($LegacyAlertSymbol)) {
        throw "Unused legacy alert code remains: $LegacyAlertSymbol"
    }
}
Assert-Contains $Algorithm 'speedKmh > limit \+ AppSettings\.clampOverspeedThreshold\(thresholdKmh\)' "Overspeed threshold must remain strict and non-inclusive"
Assert-Contains $SoundPlayer 'USAGE_ASSISTANCE_NAVIGATION_GUIDANCE' "Voice alerts must use navigation-guidance audio routing"
Assert-Contains $SoundPlayer 'add\("cam_stop_voice\.mp3", 1f\)[\s\S]*startWorkerIfNeeded\(\)' "The object-finished phrase must follow the common PCM queue rules"
Assert-Contains $SoundPlayer 'AUDIO_ROUTE_WARMUP_MS = 100L' "Cold Automotive audio routes must use the current test warmup"
Assert-Contains $SoundPlayer 'AudioTrack\.MODE_STREAM' "All alerts must share one streaming AudioTrack session"
Assert-Contains $SoundPlayer 'MediaExtractor\(\)' "MP3 assets must be decoded into PCM"
Assert-Contains $SoundPlayer 'MediaCodec\.createDecoderByType' "MP3 decoding must use the platform codec"
Assert-Contains $SoundPlayer 'writeSilence\(track, AUDIO_ROUTE_WARMUP_MS\)' "Route warmup must be written into the same PCM stream"
Assert-Contains $SoundPlayer 'writeSilence\(track, AUDIO_ROUTE_WARMUP_MS\)[\s\S]*?track\.play\(\)' "The Automotive route must be prebuffered before AudioTrack starts"
Assert-Contains $SoundPlayer 'val bufferSize = max\(minimum, warmupBufferSize\)' "Short beeps must not be blocked by a half-second AudioTrack buffer"
Assert-Contains $SoundPlayer 'writeSilence\(track, PLAYBACK_TAIL_DURATION_MS\)' "The playback tail must be written into the same PCM stream"
Assert-Contains $SoundPlayer 'PLAYBACK_TAIL_DURATION_MS = 100L' "The Automotive PCM tail must use the current 100 ms test value"
Assert-Contains $SoundPlayer 'AUDIO_FOCUS_RELEASE_DELAY_MS = 100L' "Audio focus must use the current test release delay"
if (Test-Path -LiteralPath (Join-Path $Project 'assets\sounds\playback_tail.wav')) {
    throw "The obsolete external playback-tail asset must not return"
}

Assert-Contains $SharedMapLayer 'val latPadding = \(north - south\) \* 0\.20' "Latitude viewport padding must remain 20 percent"
Assert-Contains $SharedMapLayer 'val lonPadding = \(east - west\) \* 0\.20' "Longitude viewport padding must remain 20 percent"
Assert-Contains $SharedMapLayer 'MapMarkerEntityDiff\.between' "Map markers must remain incremental"
Assert-Contains $SharedMapLayer 'MapVisualStyle\.coverage\([\s\S]*?camera\.id,[\s\S]*?activeCameraIds' "Coverage style must depend on every confirmed active camera"
Assert-Contains $SharedMapLayer 'ZoneDisplayMode\.ACTIVE_ONLY' "Coverage must support active-only display"
Assert-Contains $SharedMapLayer 'ZoneDisplayMode\.NONE' "Coverage must support hiding all zones"
Assert-Contains $SharedMapLayer 'settings\.objectScope\.includes\(camera\)' "Coverage must support all object types"
Assert-Contains $SharedMapLayer 'AppSettings\.ZONE_TRANSPARENCY' "Coverage must use shared transparency settings"
Assert-Contains $SharedMapLayer 'MapVisualStyle\.locationPrimaryColor\(nightMode\)' "Location arrow must follow the map theme"
Assert-Contains $SharedMapLayer 'AppSettings\.AUTO_ROTATE_MAP' "Map rotation must use the shared setting"
Assert-Contains $SharedMapLayer 'AppSettings\.LOCATION_ARROW_SCALE' "Location arrow must use the shared scale setting"
Assert-Contains $SharedMapLayer 'setScale\(locationArrowScale\(\)\)' "Location arrow scale must be applied to its icon style"
Assert-Contains $SharedMapLayer 'fun cameraState\(\): CarMapCameraState\?' "Shared map must expose restorable camera state"
Assert-Contains $SharedMapLayer 'initialLoadGeneration\+\+' "Restoring a camera must cancel stale initial positioning"
Assert-Contains $SharedMapLayer 'individualMarkerStyle\(\): IconStyle = IconStyle\(\)[\s\S]*?setRotationType\(RotationType\.NO_ROTATION\)[\s\S]*?setFlat\(false\)' "Object icons must stay upright when the map rotates"
Assert-Contains $SharedMapLayer 'locationMarkerStyle\(\): IconStyle = IconStyle\(\)[\s\S]*?setRotationType\(RotationType\.ROTATE\)[\s\S]*?setFlat\(true\)' "The current-location arrow must keep following its heading"
Assert-Contains $SharedMapLayer 'removeCameraListener' "Destroy must remove the camera listener"
Assert-Contains $SharedMapLayer 'MapMarkerHitTest\.nearest' "Projected taps must use the shared hit test"

Assert-Contains $Activity 'DrivingSnapshotIntent\.from\(intent\)' "Phone updates must use DrivingSnapshotIntent"
Assert-Contains $Activity 'layer\.updateActiveCameras\(snapshot\.activeCameraIds\)' "Phone map must receive all confirmed active cameras"
Assert-Contains $Activity 'DrivingHudPresentation\.from' "Phone HUD must use shared presentation rules"
Assert-Contains $Activity 'registerOnSharedPreferenceChangeListener' "Phone HUD must observe shared settings"
Assert-Contains $Activity 'applyImmersiveMode\(\)' "Phone must retain immersive mode"
Assert-Contains $Activity 'radarBaseUpdater\(\)\.requestUpdate\(\)' "Phone update action must use the application updater"
Assert-Contains $Activity 'AppSettings\.ZONE_TRANSPARENCY' "Phone menu must expose zone transparency"
Assert-Contains $Activity 'AppSettings\.ZONE_DISPLAY_MODE' "Phone menu must expose zone visibility"
Assert-Contains $Activity 'AppSettings\.ZONE_OBJECT_SCOPE' "Phone menu must expose zone object scope"
Assert-Contains $Activity 'showMenuDialog\(' "Phone settings must be split into thematic submenus"
Assert-Contains $Activity 'AppSettings\.LOCATION_ARROW_SCALE' "Phone menu must expose location arrow scale"
Assert-Contains $Activity 'AppSettings\.UI_SCALE_PERCENT' "Interface menu must expose in-app UI scale"
Assert-Contains $Activity 'resources\.displayMetrics\.density \* uiScaleFactor' "In-app UI scale must affect dimensions without changing system density"
Assert-Contains $Activity 'textSize = scaledSp\(sp\)' "In-app UI scale must affect application text"
Assert-Contains $Activity 'private fun scaledTextAdapter\(' "System-backed list controls must share the UI text scale"
Assert-Contains $Activity 'override fun getView[\s\S]*?override fun getDropDownView[\s\S]*?scaledSp\(textSp\)' "Spinner values and dropdown rows must both use the UI text scale"
Assert-Contains $Activity 'textSize = scaledSp\(22\)' "Slider step button text must use the UI text scale"
Assert-Contains $Activity 'BUTTON_NEUTRAL\)\?\.textSize = scaledSp\(14\)' "Every dialog action button must use the UI text scale"
Assert-Contains $Activity 'messageId\)\?\.textSize = scaledSp\(16\)' "Dialog messages must use the UI text scale"
if (([regex]::Matches($Activity, 'scaleType = ImageView\.ScaleType\.FIT_CENTER')).Count -lt 2) {
    throw "Main-screen action icons must scale with their button bounds"
}
Assert-Contains $Activity 'mapWindow\.setScaleFactor\(mapScaleFactor\)' "MapKit rendering must use its independent map scale"
Assert-Contains $Settings 'DEFAULT_UI_SCALE_PERCENT = 100' "UI scale must default to 100 percent"
Assert-Contains $Settings 'MIN_UI_SCALE_PERCENT = 100' "UI scale minimum must remain 100 percent"
Assert-Contains $Settings 'MAX_UI_SCALE_PERCENT = 200' "UI scale maximum must remain 200 percent"
Assert-Contains $Settings 'UI_SCALE_STEP_PERCENT = 10' "UI scale step must remain 10 percent"
Assert-Contains $Activity 'private fun uiScaleRow\(\): LinearLayout' "Interface scale must be controlled by an inline slider"
Assert-Contains $Activity 'max = \(AppSettings\.MAX_UI_SCALE_PERCENT - AppSettings\.MIN_UI_SCALE_PERCENT\)' "UI scale slider must cover the configured range"
if ($Activity -match 'showUiScaleDialog') { throw "UI scale must not use a separate selection dialog" }
Assert-Contains $Settings 'DEFAULT_MAP_SCALE_PERCENT = 100' "Map scale must default to MapKit's native 100 percent"
Assert-Contains $Settings 'MIN_MAP_SCALE_PERCENT = 100' "Map scale minimum must remain 100 percent"
Assert-Contains $Settings 'MAX_MAP_SCALE_PERCENT = 500' "Map scale maximum must remain 500 percent"
Assert-Contains $Settings 'MAP_SCALE_STEP_PERCENT = 10' "Map scale step must remain 10 percent"
Assert-Contains $Activity 'private fun mapScaleRow\(\): LinearLayout' "Map menu must expose an independent map scale slider"
Assert-Contains $Activity 'AppSettings\.MAP_SCALE_PERCENT' "Map scale slider must persist independently from UI scale"
Assert-Contains $Activity 'private fun trackSettingsMenuDialog\(' "Settings dialogs must share a menu-session lifecycle"
Assert-Contains $Activity 'settingsMenuDialogCount == 0\) applyScaleSettingsIfChanged\(\)' "Scale settings must be applied only after the complete menu closes"
Assert-Contains $Activity 'private fun applyScaleSettingsIfChanged\(\)[\s\S]*?recreate\(\)' "A changed UI or map scale must recreate the application screen after menu exit"
Assert-Contains $Activity 'Gravity\.TOP or Gravity\.START' "Phone HUD must be placed in the top-left corner"
Assert-Contains $Activity 'FrameLayout\.LayoutParams\(dp\(203\)' "Phone HUD width must be reduced by one quarter"
Assert-Contains $Activity 'setPadding\(dp\(4\), dp\(4\), 0, 0\)' "Phone HUD must use a minimal non-zero inset"

Assert-Contains $Application 'RadarBaseUpdateSingleFlight\(' "Application must own the process update guard"
Assert-Contains $Application 'radarBaseUpdater\.requestUpdate\(\)' "Application must request the cold-start update"
Assert-Contains $Application 'MapKitLifecycle\(' "Application must own MapKit lifecycle"

Assert-Contains $CarSession 'CarStartupDecision\.from' "Car startup must separate radar and map readiness"
Assert-Contains $CarSession 'TrackingService\.ACTION_START' "Car startup must use the shared tracker"
Assert-Contains $CarSession 'DrivingSnapshotIntent\.from\(intent\)' "Car updates must use DrivingSnapshotIntent"
Assert-Contains $CarSession 'CarRadarBaseUpdateNotifier' "Car session must own update feedback"
if ($CarSession -cmatch '=\s*RadarBaseUpdater\(' -or
        $CarSession -cmatch '=\s*StrelkaAlertTracker\(') {
    throw "Car session must not create a second updater or tracker"
}

Assert-Contains $SurfaceController ': SurfaceCallback' "Car controller must implement SurfaceCallback"
Assert-Contains $SurfaceController 'appManager\.setSurfaceCallback\(this\)' "Car controller must register its callback"
Assert-Contains $SurfaceController 'appManager\.setSurfaceCallback\(null\)' "Car controller must clear its callback"
Assert-Contains $SurfaceController 'createVirtualDisplay' "Car surface must use VirtualDisplay"
Assert-Contains $SurfaceController 'Presentation\(' "Car surface must use Presentation"
Assert-Contains $SurfaceController 'releaseResource\(false\)' "A resized exact Surface wrapper must be preserved for recreation"
Assert-Contains $SurfaceController 'notifySurfaceFailure\(error\)' "Surface failures must be reported"
Assert-Contains $SurfaceController 'existing\?\.cameraState\(\)' "Car surface recreation must retain camera state"
Assert-Contains $SurfaceController 'created\.restoreCameraState\(retainedCameraState\)' "Car surface recreation must restore camera state"

Assert-Contains $CarPresentation 'SharedCameraMapLayer\(' "Car presentation must use the shared map layer"
Assert-Contains $CarPresentation 'DrivingHudPresentation\.from' "Car HUD must use shared presentation rules"
Assert-Contains $CarPresentation 'layer\.updateActiveCameras\(snapshot\.activeCameraIds\)' "Car map must receive all confirmed active cameras"
Assert-Contains $CarPresentation 'databaseEmpty && !presentation\.hasObject' "Car HUD must distinguish an empty database"
Assert-Contains $CarPresentation 'registerOnSharedPreferenceChangeListener' "Car HUD must observe shared settings"
Assert-Contains $CarPresentation 'mapWindow\.focusRect = null' "An empty visible area must clear the focus rect"
Assert-Contains $CarPresentation 'Gravity\.TOP or Gravity\.START' "Car HUD must be placed in the top-left corner"
Assert-Contains $CarPresentation 'dp\(225\)' "Car HUD width must be reduced by one quarter"
Assert-Contains $CarPresentation 'setMargins\(dp\(4\), dp\(4\), 0, 0\)' "Car HUD must use the same minimal inset as the phone"

Assert-Contains $CarGestures 'ln\(scaleFactor\.toDouble\(\)\) / ln\(2\.0\)' "Car pinch must use logarithmic zoom"
Assert-Contains $CarGestures 'target\.tapCameraAt\(x, y\)' "Camera taps must be consumed before background taps"
Assert-Contains $CarMenu 'TrackingService\.requestStop\(carContext\)' "Car exit must stop the shared tracker"
Assert-Contains $CarMenu 'CarMenuGroup\.entries' "Car settings must be split into thematic submenus"
Assert-Contains $CarValue 'AppSettings\.adjustOverspeedThreshold' "Car settings must reuse shared overspeed rules"
Assert-Contains $CarValue 'AppSettings\.adjustLocationArrowScale' "Car settings must reuse shared arrow scale rules"
Assert-Contains $CarValue 'AppSettings\.adjustHudTransparency' "Car settings must reuse shared transparency rules"
Assert-Contains $CarValue 'AppSettings\.adjustZoneTransparency' "Car settings must reuse shared zone transparency rules"
Assert-Contains $CarValue '\.commit\(\)' "Car numeric settings must be synchronous"
Assert-Contains $CarAbout 'LongMessageTemplate\.Builder' "Car About must use LongMessageTemplate"
Assert-Contains $CarAbout 'CameraDatabase\(carContext\)' "Car About must read the shared database"

$BuildGradle = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $Project "build.gradle.kts")
Assert-Contains $BuildGradle "plugins\s*\{[\s\S]*com\.android\.application.*9\.0\.1" "AGP 9.0.1 must provide built-in Kotlin"
Assert-Contains $BuildGradle 'val mapkitApiKey = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"' "MapKit key must be embedded at build time"
if ($Activity -match 'showMapKeyDialog' -or $CarMenu -match 'CarMapKeyScreen') {
    throw "MapKit key input must not be exposed in application settings"
}
Assert-Contains $BuildGradle 'kotlin\.directories\.add\("src/test/kotlin"\)' "Test Kotlin source directory is missing"
if ($BuildGradle -match 'src/ru') {
    throw "Legacy production source directory must not be configured"
}
Assert-Contains $BuildGradle 'versionCode\s*=\s*52' "Release versionCode changed unexpectedly"
Assert-Contains $BuildGradle 'appVersionName\s*=\s*"4\.9\.17"' "Release versionName changed unexpectedly"
foreach ($Dependency in @(
        [pscustomobject]@{ Configuration = "implementation"; Coordinate = "androidx.car.app:app:1.7.0" },
        [pscustomobject]@{ Configuration = "implementation"; Coordinate = "androidx.car.app:app-projected:1.7.0" },
        [pscustomobject]@{ Configuration = "testImplementation"; Coordinate = "junit:junit:4.13.2" },
        [pscustomobject]@{ Configuration = "testImplementation"; Coordinate = "org.robolectric:robolectric:4.16.1" })) {
    if (-not (Test-ActiveGradleDependency $BuildGradle $Dependency.Configuration $Dependency.Coordinate)) {
        throw "Gradle dependency is missing: $($Dependency.Coordinate)"
    }
}

$ManifestPath = Join-Path $Project "AndroidManifest.xml"
$Manifest = [xml](Get-Content -Raw -Encoding UTF8 -LiteralPath $ManifestPath)
$AndroidNamespace = "http://schemas.android.com/apk/res/android"
$Namespaces = New-Object System.Xml.XmlNamespaceManager($Manifest.NameTable)
$Namespaces.AddNamespace("android", $AndroidNamespace)
$ApplicationNode = $Manifest.SelectSingleNode("/manifest/application", $Namespaces)
$TrackingServiceNode = $ApplicationNode.SelectSingleNode(
        "service[@android:name='.TrackingService']", $Namespaces)
if ($null -eq $TrackingServiceNode -or
        $TrackingServiceNode.GetAttribute("stopWithTask", $AndroidNamespace) -ne "false") {
    throw "TrackingService must survive removal of the phone task"
}
$CarServiceNode = $ApplicationNode.SelectSingleNode(
        "service[@android:name='.GpsCarAppService']", $Namespaces)
if ($null -eq $CarServiceNode -or
        $CarServiceNode.GetAttribute("exported", $AndroidNamespace) -ne "true") {
    throw "GpsCarAppService must remain exported"
}

$Readme = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $Project "README.md")
Assert-Contains $Readme 'Android Auto' "README must document Android Auto"
Assert-Contains $Readme 'DHU' "README must document DHU testing"
foreach ($IconName in @(
        "ic_car_zoom_in.xml", "ic_car_zoom_out.xml",
        "ic_car_location.xml", "ic_car_menu.xml")) {
    $IconPath = Join-Path $Project "res\drawable\$IconName"
    if (-not (Test-Path -LiteralPath $IconPath)) { throw "Android Auto icon is missing: $IconName" }
}

Write-Output "AndroidSourceContractTest: OK"
