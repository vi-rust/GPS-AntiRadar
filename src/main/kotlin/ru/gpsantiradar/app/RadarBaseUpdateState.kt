package ru.gpsantiradar.app

class RadarBaseUpdateState(
    val sequence: Long,
    val status: Status,
    importedCount: Int,
    coordinatesCorrected: Boolean,
    message: String?
) {
    enum class Status { IDLE, STARTED, UNCHANGED, SUCCESS, ERROR, ALREADY_RUNNING }
    val importedCount = if (status == Status.SUCCESS) importedCount else 0
    val coordinatesCorrected = status == Status.SUCCESS && coordinatesCorrected
    val message = message ?: ""
    fun isTerminal() = status != Status.IDLE && status != Status.STARTED
}
