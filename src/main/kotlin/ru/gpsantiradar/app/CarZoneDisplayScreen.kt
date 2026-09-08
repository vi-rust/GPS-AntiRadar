package ru.gpsantiradar.app

import android.content.Context
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template

class CarZoneDisplayScreen : Screen {
    private val settingChanged: Runnable

    constructor(carContext: CarContext, surfaceController: CarSurfaceController?) :
        this(carContext, Runnable { surfaceController?.refreshCoverageSettings() })

    constructor(carContext: CarContext, settingChanged: Runnable?) : super(carContext) {
        this.settingChanged = settingChanged ?: Runnable {}
    }

    override fun onGetTemplate(): Template {
        val selected = currentMode()
        val items = ItemList.Builder()
        ZoneDisplayMode.entries.forEach { mode ->
            val row = Row.Builder()
                .setTitle(mode.title())
                .setOnClickListener { select(mode) }
            if (mode == selected) row.addText("Выбрано")
            items.addItem(row.build())
        }
        return ListTemplate.Builder()
            .setTitle("Отображение зон")
            .setHeaderAction(Action.BACK)
            .setSingleList(items.build())
            .build()
    }

    private fun currentMode(): ZoneDisplayMode = ZoneDisplayMode.fromStored(
        carContext.getSharedPreferences(AppSettings.PREFERENCES, Context.MODE_PRIVATE)
            .getString(AppSettings.ZONE_DISPLAY_MODE, ZoneDisplayMode.ALL.name),
    )

    private fun select(mode: ZoneDisplayMode) {
        carContext.getSharedPreferences(AppSettings.PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(AppSettings.ZONE_DISPLAY_MODE, mode.name)
            .commit()
        settingChanged.run()
        invalidate()
    }
}
