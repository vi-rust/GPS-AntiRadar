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
    "StrelkaAlertTracker", "TrackingService", "GpsCarAppService", "GpsCarSession",
    "CarSurfaceController", "CarMapPresentation", "CarMapGestureController",
    "CarMapScreen", "CarMenuScreen", "CarValueScreen", "CarMapKeyScreen",
    "CarAboutScreen"
)
foreach ($Name in $RequiredSources) { [void](Read-Source $Name) }

$Database = Read-Source "CameraDatabase"
$Tracking = Read-Source "TrackingService"
$Algorithm = Read-Source "StrelkaAlertAlgorithm"
$Activity = Read-Source "MainActivity"
$Application = Read-Source "GpsAntiRadarApplication"
$SharedMapLayer = Read-Source "SharedCameraMapLayer"
$CarSession = Read-Source "GpsCarSession"
$SurfaceController = Read-Source "CarSurfaceController"
$CarPresentation = Read-Source "CarMapPresentation"
$CarGestures = Read-Source "CarMapGestureController"
$CarMenu = Read-Source "CarMenuScreen"
$CarValue = Read-Source "CarValueScreen"
$CarMapKey = Read-Source "CarMapKeyScreen"
$CarAbout = Read-Source "CarAboutScreen"

Assert-Contains $Database 'setWriteAheadLoggingEnabled\(true\)' "CameraDatabase must enable WAL"
Assert-Contains $Database 'beginTransactionNonExclusive\(\)' "RadarBase replacement must use a non-exclusive transaction"
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
Assert-Contains $Algorithm 'speedKmh > limit \+ AppSettings\.clampOverspeedThreshold\(thresholdKmh\)' "Overspeed threshold must remain strict and non-inclusive"

Assert-Contains $SharedMapLayer 'val latPadding = \(north - south\) \* 0\.20' "Latitude viewport padding must remain 20 percent"
Assert-Contains $SharedMapLayer 'val lonPadding = \(east - west\) \* 0\.20' "Longitude viewport padding must remain 20 percent"
Assert-Contains $SharedMapLayer 'MapMarkerEntityDiff\.between' "Map markers must remain incremental"
Assert-Contains $SharedMapLayer 'MapVisualStyle\.coverage\(baseColor, camera\.id, activeCameraId\)' "Coverage style must depend on the active camera"
Assert-Contains $SharedMapLayer 'refreshCoverageStyle\(previousActiveCameraId\)[\s\S]*refreshCoverageStyle\(activeCameraId\)' "Old and new active coverage must both be restyled"
Assert-Contains $SharedMapLayer 'MapVisualStyle\.locationPrimaryColor\(nightMode\)' "Location arrow must follow the map theme"
Assert-Contains $SharedMapLayer 'AppSettings\.AUTO_ROTATE_MAP' "Map rotation must use the shared setting"
Assert-Contains $SharedMapLayer 'removeCameraListener' "Destroy must remove the camera listener"
Assert-Contains $SharedMapLayer 'MapMarkerHitTest\.nearest' "Projected taps must use the shared hit test"

Assert-Contains $Activity 'DrivingSnapshotIntent\.from\(intent\)' "Phone updates must use DrivingSnapshotIntent"
Assert-Contains $Activity 'layer\.updateActiveCamera\(snapshot\.cameraId\)' "Phone map must receive the active camera"
Assert-Contains $Activity 'DrivingHudPresentation\.from' "Phone HUD must use shared presentation rules"
Assert-Contains $Activity 'registerOnSharedPreferenceChangeListener' "Phone HUD must observe shared settings"
Assert-Contains $Activity 'applyImmersiveMode\(\)' "Phone must retain immersive mode"
Assert-Contains $Activity 'radarBaseUpdater\(\)\.requestUpdate\(\)' "Phone update action must use the application updater"

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

Assert-Contains $CarPresentation 'SharedCameraMapLayer\(' "Car presentation must use the shared map layer"
Assert-Contains $CarPresentation 'DrivingHudPresentation\.from' "Car HUD must use shared presentation rules"
Assert-Contains $CarPresentation 'layer\.updateActiveCamera\(snapshot\.cameraId\)' "Car map must receive the active camera"
Assert-Contains $CarPresentation 'databaseEmpty && !presentation\.hasActiveObject' "Car HUD must distinguish an empty database"
Assert-Contains $CarPresentation 'registerOnSharedPreferenceChangeListener' "Car HUD must observe shared settings"
Assert-Contains $CarPresentation 'mapWindow\.focusRect = null' "An empty visible area must clear the focus rect"

Assert-Contains $CarGestures 'ln\(scaleFactor\.toDouble\(\)\) / ln\(2\.0\)' "Car pinch must use logarithmic zoom"
Assert-Contains $CarGestures 'target\.tapCameraAt\(x, y\)' "Camera taps must be consumed before background taps"
Assert-Contains $CarMenu 'TrackingService\.requestStop\(carContext\)' "Car exit must stop the shared tracker"
Assert-Contains $CarValue 'AppSettings\.adjustOverspeedThreshold' "Car settings must reuse shared overspeed rules"
Assert-Contains $CarValue 'AppSettings\.adjustHudTransparency' "Car settings must reuse shared transparency rules"
Assert-Contains $CarValue '\.commit\(\)' "Car numeric settings must be synchronous"
Assert-Contains $CarMapKey 'SearchTemplate\.Builder' "Car MapKit key input must use SearchTemplate"
Assert-Contains $CarMapKey '\.trim\(\)' "Car MapKit key must be trimmed"
Assert-Contains $CarAbout 'LongMessageTemplate\.Builder' "Car About must use LongMessageTemplate"
Assert-Contains $CarAbout 'CameraDatabase\(carContext\)' "Car About must read the shared database"

$BuildGradle = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $Project "build.gradle.kts")
Assert-Contains $BuildGradle "plugins\s*\{[\s\S]*com\.android\.application.*9\.0\.1" "AGP 9.0.1 must provide built-in Kotlin"
Assert-Contains $BuildGradle 'kotlin\.directories\.add\("src/test/kotlin"\)' "Test Kotlin source directory is missing"
if ($BuildGradle -match 'src/ru') {
    throw "Legacy production source directory must not be configured"
}
Assert-Contains $BuildGradle 'versionCode\s*=\s*44' "Release versionCode changed unexpectedly"
Assert-Contains $BuildGradle 'versionName\s*=\s*"4\.9\.9"' "Release versionName changed unexpectedly"
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
        "ic_car_zoom_in.xml", "ic_car_zoom_out.xml", "ic_car_pan.xml",
        "ic_car_location.xml", "ic_car_menu.xml")) {
    $IconPath = Join-Path $Project "res\drawable\$IconName"
    if (-not (Test-Path -LiteralPath $IconPath)) { throw "Android Auto icon is missing: $IconName" }
}

Write-Output "AndroidSourceContractTest: OK"
