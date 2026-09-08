param([string]$RadarBaseFile = "")

$ErrorActionPreference = "Stop"
$Project = $PSScriptRoot
$Sdk = if ($env:ANDROID_HOME) {
    $env:ANDROID_HOME
} else {
    Join-Path $env:LOCALAPPDATA "Android\Sdk"
}
$env:ANDROID_HOME = $Sdk
$env:ANDROID_SDK_ROOT = $Sdk

$GradleArguments = @("--no-daemon", "testDebugUnitTest")
if (-not [string]::IsNullOrWhiteSpace($RadarBaseFile)) {
    $ResolvedRadarBase = (Resolve-Path -LiteralPath $RadarBaseFile).Path
    $GradleArguments += "-PradarBaseFile=$ResolvedRadarBase"
}

& (Join-Path $Project "gradlew.bat") @GradleArguments
if ($LASTEXITCODE -ne 0) { throw "Gradle tests failed" }

& (Join-Path $Project "tests\AndroidSourceContractTest.ps1") -Project $Project
if ($LASTEXITCODE -ne 0) { throw "Source contract tests failed" }
