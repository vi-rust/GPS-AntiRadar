param([string]$RadarBaseFile = "")

$ErrorActionPreference = "Stop"
$Project = $PSScriptRoot
$Build = Join-Path $Project "work\tests"
$JavaHome = if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\javac.exe"))) {
    $env:JAVA_HOME
} else { "C:\Program Files\Java\jdk-21" }
New-Item -ItemType Directory -Force $Build | Out-Null
Remove-Item (Join-Path $Build "*") -Recurse -Force -ErrorAction SilentlyContinue
$GsonJar = Get-ChildItem (Join-Path $env:USERPROFILE ".gradle\caches\modules-2\files-2.1\com.google.code.gson\gson\2.11.0") `
    -Recurse -Filter "gson-2.11.0.jar" -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $GsonJar) { throw "Сначала выполните .\build.ps1, чтобы загрузить Gson" }
$LocalGson = Join-Path $Build "gson-2.11.0.jar"
Copy-Item $GsonJar.FullName $LocalGson -Force
& (Join-Path $JavaHome "bin\javac.exe") -encoding UTF-8 -cp $LocalGson -d $Build `
    (Join-Path $Project "src\ru\hudspeed\pro\CameraPoint.java") `
    (Join-Path $Project "src\ru\hudspeed\pro\Geo.java") `
    (Join-Path $Project "src\ru\hudspeed\pro\RadarBaseTypes.java") `
    (Join-Path $Project "src\ru\hudspeed\pro\SpeedControlRules.java") `
    (Join-Path $Project "src\ru\hudspeed\pro\AppSettings.java") `
    (Join-Path $Project "src\ru\hudspeed\pro\ThemeMode.java") `
    (Join-Path $Project "src\ru\hudspeed\pro\ThemeResolver.java") `
    (Join-Path $Project "src\ru\hudspeed\pro\DrivingSnapshot.java") `
    (Join-Path $Project "src\ru\hudspeed\pro\DrivingHudPresentation.java") `
    (Join-Path $Project "src\ru\hudspeed\pro\StrelkaAlertAlgorithm.java") `
    (Join-Path $Project "src\ru\hudspeed\pro\StrelkaAlertTracker.java") `
    (Join-Path $Project "src\ru\hudspeed\pro\RadarScanGate.java") `
    (Join-Path $Project "src\ru\hudspeed\pro\ReleaseHistory.java") `
    (Join-Path $Project "src\ru\hudspeed\pro\ProcessLaunchGuard.java") `
    (Join-Path $Project "src\ru\hudspeed\pro\MapKitLifecycle.java") `
    (Join-Path $Project "src\ru\hudspeed\pro\CarSurfaceSpec.java") `
    (Join-Path $Project "src\ru\hudspeed\pro\CarSurfaceSpec.java") `
    (Join-Path $Project "src\ru\hudspeed\pro\CarMenuItem.java") `
    (Join-Path $Project "src\ru\hudspeed\pro\RadarBaseUpdateSingleFlight.java") `
    (Join-Path $Project "src\ru\hudspeed\pro\RadarBaseUpdateState.java") `
    (Join-Path $Project "src\ru\hudspeed\pro\RadarBaseUpdateListenerRegistry.java") `
    (Join-Path $Project "src\ru\hudspeed\pro\CameraMarkerDiff.java") `
    (Join-Path $Project "src\ru\hudspeed\pro\MapMarkerLayout.java") `
    (Join-Path $Project "src\ru\hudspeed\pro\MapMarkerEntityDiff.java") `
    (Join-Path $Project "src\ru\hudspeed\pro\MapOrientation.java") `
    (Join-Path $Project "src\ru\hudspeed\pro\HeadingSelection.java") `
    (Join-Path $Project "src\ru\hudspeed\pro\RadarBaseParser.java") `
    (Join-Path $Project "tests\ParserGeoTest.java")
if ($LASTEXITCODE -ne 0) { throw "Tests did not compile" }
if ([string]::IsNullOrWhiteSpace($RadarBaseFile)) {
    & (Join-Path $JavaHome "bin\java.exe") -cp "$Build;$LocalGson" ru.gpsantiradar.app.ParserGeoTest
} else {
    & (Join-Path $JavaHome "bin\java.exe") -cp "$Build;$LocalGson" ru.gpsantiradar.app.ParserGeoTest $RadarBaseFile
}
if ($LASTEXITCODE -ne 0) { throw "Tests failed" }
& (Join-Path $Project "tests\AndroidSourceContractTest.ps1") -Project $Project
