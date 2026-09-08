package ru.gpsantiradar.app

enum class ThemeMode(private val title: String) {
    LIGHT("Светлая"), DARK("Тёмная"), AUTOMATIC("Автоматически");
    fun title(): String = title

    companion object {
        fun fromStored(value: String?): ThemeMode =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: AUTOMATIC
    }
}
