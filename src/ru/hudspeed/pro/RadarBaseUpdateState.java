package ru.gpsantiradar.app;

public final class RadarBaseUpdateState {
    public enum Status { IDLE, STARTED, UNCHANGED, SUCCESS, ERROR, ALREADY_RUNNING }

    public final long sequence;
    public final Status status;
    public final int importedCount;
    public final boolean coordinatesCorrected;
    public final String message;

    RadarBaseUpdateState(long sequence, Status status, int importedCount,
                         boolean coordinatesCorrected, String message) {
        this.sequence = sequence;
        this.status = status;
        boolean hasImportResult = status == Status.SUCCESS;
        this.importedCount = hasImportResult ? importedCount : 0;
        this.coordinatesCorrected = hasImportResult && coordinatesCorrected;
        this.message = message == null ? "" : message;
    }

    public boolean isTerminal() {
        return status != Status.IDLE && status != Status.STARTED;
    }
}
