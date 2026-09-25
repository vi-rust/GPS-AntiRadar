package ru.gpsantiradar.app

import android.content.Context
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.model.Toggle
import androidx.core.graphics.drawable.IconCompat

class CarMenuScreen internal constructor(
    carContext: CarContext,
    private val surfaceController: CarSurfaceController?,
    private val updateController: UpdateController,
    private val exitAction: ExitAction,
    private val group: CarMenuGroup? = null,
) : Screen(carContext) {
    fun interface UpdateController {
        fun requestUpdate()
    }

    fun interface ExitAction {
        fun exit()
    }

    constructor(carContext: CarContext, surfaceController: CarSurfaceController?) : this(
        carContext,
        surfaceController,
        SharedUpdateController(application(carContext).radarBaseUpdater()),
        ExitAction {
            TrackingService.requestStop(carContext)
            carContext.getCarService(ScreenManager::class.java).popToRoot()
            carContext.finishCarApp()
        },
    )

    override fun onGetTemplate(): Template {
        val items = ItemList.Builder()
        if (group == null) {
            for (menuGroup in CarMenuGroup.entries) items.addItem(row(menuGroup))
            items.addItem(row(CarMenuItem.EXIT))
        } else {
            for (item in group.items) items.addItem(row(item))
        }
        return ListTemplate.Builder()
            .setTitle(group?.let(::title) ?: "Меню")
            .setHeaderAction(Action.BACK)
            .setSingleList(items.build())
            .build()
    }

    private fun row(group: CarMenuGroup): Row = Row.Builder()
        .setTitle(title(group))
        .setImage(icon(iconResource(group)), Row.IMAGE_TYPE_ICON)
        .setBrowsable(true)
        .setOnClickListener {
            screenManager.push(
                CarMenuScreen(
                    carContext,
                    surfaceController,
                    updateController,
                    exitAction,
                    group,
                ),
            )
        }
        .build()

    private fun row(item: CarMenuItem): Row {
        val row = Row.Builder()
            .setTitle(
                when (item) {
                    CarMenuItem.THEME -> "Тема: ${ThemeSettings.mode(carContext).title()}"
                    CarMenuItem.ZONE_DISPLAY -> {
                        val stored = carContext.getSharedPreferences(
                            AppSettings.PREFERENCES,
                            Context.MODE_PRIVATE,
                        ).getString(AppSettings.ZONE_DISPLAY_MODE, ZoneDisplayMode.ALL.name)
                        val storedScope = carContext.getSharedPreferences(
                            AppSettings.PREFERENCES,
                            Context.MODE_PRIVATE,
                        ).getString(
                            AppSettings.ZONE_OBJECT_SCOPE,
                            ZoneObjectScope.CAMERAS_ONLY.name,
                        )
                        "Отображение зон: ${ZoneDisplayMode.fromStored(stored).title()} · " +
                            ZoneObjectScope.fromStored(storedScope).title()
                    }
                    else -> title(item)
                },
            )
            .setImage(icon(iconResource(item)), Row.IMAGE_TYPE_ICON)
        if (item == CarMenuItem.AUTO_ROTATE_MAP) {
            val enabled = carContext.getSharedPreferences(
                AppSettings.PREFERENCES,
                Context.MODE_PRIVATE,
            ).getBoolean(AppSettings.AUTO_ROTATE_MAP, AppSettings.DEFAULT_AUTO_ROTATE_MAP)
            row.setToggle(
                Toggle.Builder { checked ->
                    carContext.getSharedPreferences(AppSettings.PREFERENCES, Context.MODE_PRIVATE)
                        .edit().putBoolean(AppSettings.AUTO_ROTATE_MAP, checked).apply()
                    invalidate()
                }.setChecked(enabled).build(),
            )
        } else {
            row.setBrowsable(item != CarMenuItem.UPDATE_DATABASE && item != CarMenuItem.EXIT)
                .setOnClickListener { select(item) }
        }
        return row.build()
    }

    private fun select(item: CarMenuItem) {
        when (item) {
            CarMenuItem.UPDATE_DATABASE -> updateController.requestUpdate()
            CarMenuItem.OVERSPEED_THRESHOLD -> screenManager.push(
                CarValueScreen(carContext, CarValueScreen.Setting.OVERSPEED_THRESHOLD, surfaceController),
            )
            CarMenuItem.LOCATION_ARROW_SCALE -> screenManager.push(
                CarValueScreen(carContext, CarValueScreen.Setting.LOCATION_ARROW_SCALE, surfaceController),
            )
            CarMenuItem.HUD_TRANSPARENCY -> screenManager.push(
                CarValueScreen(carContext, CarValueScreen.Setting.HUD_TRANSPARENCY, surfaceController),
            )
            CarMenuItem.ZONE_TRANSPARENCY -> screenManager.push(
                CarValueScreen(carContext, CarValueScreen.Setting.ZONE_TRANSPARENCY, surfaceController),
            )
            CarMenuItem.ACTIVE_ZONE_TRANSPARENCY -> screenManager.push(
                CarValueScreen(
                    carContext,
                    CarValueScreen.Setting.ACTIVE_ZONE_TRANSPARENCY,
                    surfaceController,
                ),
            )
            CarMenuItem.ZONE_DISPLAY -> screenManager.push(
                CarZoneDisplayScreen(carContext, surfaceController),
            )
            CarMenuItem.AUTO_ROTATE_MAP -> Unit
            CarMenuItem.THEME -> screenManager.push(CarThemeScreen(carContext, surfaceController))
            CarMenuItem.ABOUT -> screenManager.push(CarAboutScreen(carContext))
            CarMenuItem.EXIT -> exitAction.exit()
        }
    }

    private fun icon(resourceId: Int): CarIcon = CarIcon.Builder(
        IconCompat.createWithResource(carContext, resourceId),
    ).build()

    private class SharedUpdateController(private val updater: RadarBaseUpdater) : UpdateController {
        override fun requestUpdate() {
            updater.requestUpdate()
        }
    }

    companion object {
        private fun iconResource(item: CarMenuItem): Int = when (item) {
            CarMenuItem.UPDATE_DATABASE -> R.drawable.ic_refresh
            CarMenuItem.OVERSPEED_THRESHOLD -> R.drawable.ic_speed_limit
            CarMenuItem.LOCATION_ARROW_SCALE -> R.drawable.ic_navigation
            CarMenuItem.HUD_TRANSPARENCY -> R.drawable.ic_opacity
            CarMenuItem.ZONE_TRANSPARENCY,
            CarMenuItem.ACTIVE_ZONE_TRANSPARENCY,
            -> R.drawable.ic_opacity
            CarMenuItem.ZONE_DISPLAY -> R.drawable.ic_zones
            CarMenuItem.AUTO_ROTATE_MAP -> R.drawable.ic_navigation
            CarMenuItem.THEME -> R.drawable.ic_theme
            CarMenuItem.ABOUT -> R.drawable.ic_info
            CarMenuItem.EXIT -> R.drawable.ic_exit
        }

        private fun title(item: CarMenuItem): String = when (item) {
            CarMenuItem.UPDATE_DATABASE -> "Обновить базу"
            CarMenuItem.OVERSPEED_THRESHOLD -> "Предел превышения скорости"
            CarMenuItem.LOCATION_ARROW_SCALE -> "Размер стрелки"
            CarMenuItem.HUD_TRANSPARENCY -> "Прозрачность HUD"
            CarMenuItem.ZONE_TRANSPARENCY -> "Прозрачность зон"
            CarMenuItem.ACTIVE_ZONE_TRANSPARENCY -> "Прозрачность активной зоны"
            CarMenuItem.ZONE_DISPLAY -> "Отображение зон"
            CarMenuItem.AUTO_ROTATE_MAP -> "Автоповорот карты"
            CarMenuItem.THEME -> "Тема"
            CarMenuItem.ABOUT -> "О программе"
            CarMenuItem.EXIT -> "Выйти"
        }

        private fun iconResource(group: CarMenuGroup): Int = when (group) {
            CarMenuGroup.ALERTS -> R.drawable.ic_speed_limit
            CarMenuGroup.MAP -> R.drawable.ic_navigation
            CarMenuGroup.INTERFACE -> R.drawable.ic_theme
            CarMenuGroup.APPLICATION -> R.drawable.ic_info
        }

        private fun title(group: CarMenuGroup): String = when (group) {
            CarMenuGroup.ALERTS -> "Оповещения"
            CarMenuGroup.MAP -> "Карта"
            CarMenuGroup.INTERFACE -> "Интерфейс"
            CarMenuGroup.APPLICATION -> "Приложение"
        }

        private fun application(carContext: CarContext): GpsAntiRadarApplication =
            carContext.applicationContext as GpsAntiRadarApplication
    }
}
