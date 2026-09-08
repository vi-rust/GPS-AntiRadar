package ru.gpsantiradar.app

enum class ZoneDisplayMode(private val title: String) {
    ALL("Все"),
    ACTIVE_ONLY("Только активная"),
    NONE("Не показывать");

    fun title(): String = title

    companion object {
        fun fromStored(value: String?): ZoneDisplayMode =
            entries.firstOrNull { it.name == value } ?: ALL
    }
}
