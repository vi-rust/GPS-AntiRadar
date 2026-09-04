# Final Fix Report

Date: 2026-09-04
Release: GPS AntiRadar 4.9.3

## Files

- src/ru/hudspeed/pro/CameraDatabase.java
- src/ru/hudspeed/pro/RadarScanGate.java
- src/ru/hudspeed/pro/StrelkaAlertTracker.java
- src/ru/hudspeed/pro/TrackingService.java
- tests/ParserGeoTest.java
- tests/AndroidSourceContractTest.ps1
- test.ps1

## RED

1. powershell -NoProfile -ExecutionPolicy Bypass -File .\test.ps1
   Result: failed to compile with four cannot find symbol errors for
   Update.overspeedCandidate(float).
2. powershell -NoProfile -ExecutionPolicy Bypass -File .\test.ps1
   Result: failed to compile because
   src/ru/hudspeed/pro/RadarScanGate.java did not exist.
3. powershell -NoProfile -ExecutionPolicy Bypass -File .\tests\AndroidSourceContractTest.ps1 -Project .
   Result: failed with
   CameraDatabase must enable WAL for concurrent readers.
4. powershell -NoProfile -ExecutionPolicy Bypass -File .\test.ps1
   Result: failed with
   AssertionError: continuous GPS fixes do not fabricate a recovery event.

## GREEN

1. powershell -NoProfile -ExecutionPolicy Bypass -File .\test.ps1
   Result: ParserGeoTest: OK and AndroidSourceContractTest: OK.
2. Set ANDROID_HOME and ANDROID_SDK_ROOT to
   $env:LOCALAPPDATA\Android\Sdk, then run
   .\gradlew.bat --no-daemon compileReleaseJavaWithJavac.
   Result: BUILD SUCCESSFUL.
3. git -c safe.directory='E:/Work/GIT/GPS AntiRadar/GPS-AntiRadar' diff --check
   Result: exit code 0 with no output.

## Design Rationale

StrelkaAlertTracker.Update now owns a stable distance/ID ordered active
snapshot. Voice and closest-zone state use the same ordering, while
overspeedCandidate() scans every spoken active object and selects the first
qualifying object deterministically. A nearer object with no limit or a higher
limit therefore cannot suppress the beep required by another active zone.

CameraDatabase enables WAL on every helper instance and performs the
delete/insert replacement in one non-exclusive transaction. Readers see either
the old committed database or the new committed database while the import
remains atomic. nearby() converts only transient SQLITE_BUSY and SQLITE_LOCKED
failures to an unavailable result. TrackingService treats that result as a
skipped scan, retains tracker state, and returns immediately from the legacy
path. The existing 20 percent visible-map padding is unchanged.

RadarScanGate separates fix observation from scan acceptance. A real GPS gap
latches recovery, poor-accuracy and low-displacement fixes cannot consume it,
and only a successful database read followed by alertTracker.update() calls
accept(). Continuous low-displacement fixes update the observation clock and
do not fabricate recovery.

## Commit

Included in the focused commit: fix: harden radar scans and database replacement.
The final commit hash is recorded in the handoff because a commit cannot embed
its own hash.

## Concerns

No device/emulator contention run was performed in this fix wave. JVM behavior,
Android source contracts, and SDK 36 release compilation cover the change; WAL
reader/writer coexistence still depends on Android's platform SQLite runtime.
