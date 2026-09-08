package ru.gpsantiradar.app

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.*

class CarThemeScreen : Screen {
    private val themeChanged: Runnable
    constructor(carContext: CarContext, surfaceController: CarSurfaceController?) :
        this(carContext, Runnable { surfaceController?.onCarConfigurationChanged() })
    constructor(carContext: CarContext, themeChanged: Runnable?) : super(carContext) {
        this.themeChanged = themeChanged ?: Runnable { }
    }
    override fun onGetTemplate(): Template {
        val selected = ThemeSettings.mode(carContext)
        val items = ItemList.Builder()
        ThemeMode.entries.forEach { mode ->
            val row = Row.Builder().setTitle(mode.title()).setOnClickListener { select(mode) }
            if (mode == selected) row.addText("Выбрано")
            items.addItem(row.build())
        }
        return ListTemplate.Builder().setTitle("Тема").setHeaderAction(Action.BACK).setSingleList(items.build()).build()
    }
    private fun select(mode: ThemeMode) {
        ThemeSettings.setMode(carContext, mode)
        themeChanged.run()
        invalidate()
    }
}
