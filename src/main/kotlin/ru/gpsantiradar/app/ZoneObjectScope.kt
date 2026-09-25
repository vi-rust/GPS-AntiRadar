package ru.gpsantiradar.app

enum class ZoneObjectScope(private val title: String) {
    CAMERAS_ONLY("Только камеры"),
    ALL_OBJECTS("Все типы объектов");

    fun title(): String = title

    fun includes(camera: CameraPoint): Boolean =
        this == ALL_OBJECTS || camera.isCameraOrControl()

    companion object {
        fun fromStored(value: String?): ZoneObjectScope =
            entries.firstOrNull { it.name == value } ?: CAMERAS_ONLY
    }
}
