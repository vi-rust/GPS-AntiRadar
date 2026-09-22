param(
    [string]$Sdk = ""
)

$ErrorActionPreference = "Stop"
$Project = $PSScriptRoot
$Output = Join-Path $Project "outputs"

if ([string]::IsNullOrWhiteSpace($Sdk)) {
    $SdkCandidates = @(
        (Join-Path $Project "work\android-sdk-36"),
        $env:ANDROID_SDK_ROOT,
        $env:ANDROID_HOME,
        (Join-Path $env:LOCALAPPDATA "Android\Sdk")
    ) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
    $Sdk = $SdkCandidates | Where-Object {
        Test-Path (Join-Path $_ "platforms\android-36\android.jar")
    } | Select-Object -First 1
}
if ([string]::IsNullOrWhiteSpace($Sdk)) {
    throw "Android SDK 36 was not found. Pass its path: .\build.ps1 -Sdk <path>"
}

$JavaHome = if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
    $env:JAVA_HOME
} else { "C:\Program Files\Java\jdk-21" }
$Gradle = Join-Path $Project "gradlew.bat"
if (-not (Test-Path $Gradle)) { throw "gradlew.bat was not found" }
$Keytool = Join-Path $JavaHome "bin\keytool.exe"
$Keystore = Join-Path $Project "work\gps-antiradar-debug.keystore"
if (-not (Test-Path $Keystore)) {
    New-Item -ItemType Directory -Force (Split-Path $Keystore) | Out-Null
    & $Keytool -genkeypair -keystore $Keystore -storepass android -keypass android `
        -alias gpsantiradar -keyalg RSA -keysize 2048 -validity 10000 `
        -dname "CN=GPS AntiRadar Debug,O=Local Development,C=RU"
    if ($LASTEXITCODE -ne 0) { throw "Failed to create the signing key" }
}

$env:JAVA_HOME = $JavaHome
$env:ANDROID_HOME = $Sdk
$env:ANDROID_SDK_ROOT = $Sdk
New-Item -ItemType Directory -Force $Output | Out-Null

& $Gradle --no-daemon clean assembleRelease
if ($LASTEXITCODE -ne 0) { throw "Android build failed" }

$Abis = @("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
$LegacyApk = Join-Path $Output "GPS-AntiRadar.apk"
if (Test-Path $LegacyApk) {
    Remove-Item $LegacyApk -Force
}
foreach ($Abi in $Abis) {
    $UnversionedApk = Join-Path $Output "GPS-AntiRadar-$Abi.apk"
    if (Test-Path $UnversionedApk) {
        Remove-Item $UnversionedApk -Force
    }
}

foreach ($Abi in $Abis) {
    $BuiltApks = @(
        Get-ChildItem (Join-Path $Project "build\outputs\apk\release") `
            -Filter "GPS-AntiRadar-*-$Abi-release.apk"
    )
    if ($BuiltApks.Count -ne 1) {
        throw "Gradle did not create exactly one versioned APK for ABI $Abi"
    }

    $FinalName = $BuiltApks[0].Name -replace "-release\.apk$", ".apk"
    $FinalApk = Join-Path $Output $FinalName
    Copy-Item $BuiltApks[0].FullName $FinalApk -Force
    Write-Output "Ready: $FinalApk"
}
