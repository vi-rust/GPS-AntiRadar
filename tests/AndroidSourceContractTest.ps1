param([string]$Project = (Split-Path -Parent $PSScriptRoot))

$ErrorActionPreference = "Stop"

function Assert-Contains([string]$Text, [string]$Pattern, [string]$Message) {
    if ($Text -notmatch $Pattern) { throw $Message }
}

$database = Get-Content -Raw (Join-Path $Project "src\ru\hudspeed\pro\CameraDatabase.java")
$tracking = Get-Content -Raw (Join-Path $Project "src\ru\hudspeed\pro\TrackingService.java")
$activity = Get-Content -Raw (Join-Path $Project "src\ru\hudspeed\pro\MainActivity.java")

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

Assert-Contains $activity '\(north - south\) \* 0\.20' "latitude map-query padding must remain 20 percent"
Assert-Contains $activity '\(east - west\) \* 0\.20' "longitude map-query padding must remain 20 percent"

Write-Output "AndroidSourceContractTest: OK"
