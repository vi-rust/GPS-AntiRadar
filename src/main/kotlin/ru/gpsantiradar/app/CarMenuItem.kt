package ru.gpsantiradar.app

enum class CarMenuItem {
    UPDATE_DATABASE,
    OVERSPEED_THRESHOLD,
    LOCATION_ARROW_SCALE,
    HUD_TRANSPARENCY,
    ZONE_TRANSPARENCY,
    ACTIVE_ZONE_TRANSPARENCY,
    ZONE_DISPLAY,
    AUTO_ROTATE_MAP,
    THEME,
    ABOUT,
    EXIT
}

enum class CarMenuGroup(val items: List<CarMenuItem>) {
    ALERTS(listOf(CarMenuItem.OVERSPEED_THRESHOLD)),
    MAP(
        listOf(
            CarMenuItem.LOCATION_ARROW_SCALE,
            CarMenuItem.AUTO_ROTATE_MAP,
            CarMenuItem.ZONE_DISPLAY,
            CarMenuItem.ZONE_TRANSPARENCY,
            CarMenuItem.ACTIVE_ZONE_TRANSPARENCY,
        ),
    ),
    INTERFACE(listOf(CarMenuItem.HUD_TRANSPARENCY, CarMenuItem.THEME)),
    APPLICATION(
        listOf(
            CarMenuItem.UPDATE_DATABASE,
            CarMenuItem.ABOUT,
        ),
    ),
}
