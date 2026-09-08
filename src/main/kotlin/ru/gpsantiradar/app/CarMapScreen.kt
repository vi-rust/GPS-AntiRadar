package ru.gpsantiradar.app

import androidx.car.app.*
import androidx.car.app.model.*
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat

class CarMapScreen : Screen {
    private val surfaceController: CarSurfaceController
    private val menuAction: Runnable
    constructor(carContext: CarContext, surfaceController: CarSurfaceController) : this(
        carContext, surfaceController, Runnable {
            carContext.getCarService(ScreenManager::class.java).push(CarMenuScreen(carContext, surfaceController))
        })
    constructor(carContext: CarContext, surfaceController: CarSurfaceController, menuAction: Runnable) : super(carContext) {
        this.surfaceController = surfaceController; this.menuAction = menuAction
    }
    override fun onGetTemplate(): Template {
        val applicationActions = ActionStrip.Builder().addAction(Action.Builder().setIcon(CarIcon.Builder(
            IconCompat.createWithResource(carContext, R.drawable.ic_car_menu)).build()).setOnClickListener(menuAction::run).build()).build()
        val mapActions = ActionStrip.Builder().addAction(Action.PAN)
            .addAction(iconAction(R.drawable.ic_car_zoom_in) { surfaceController.zoomBy(1f) })
            .addAction(iconAction(R.drawable.ic_car_zoom_out) { surfaceController.zoomBy(-1f) })
            .addAction(iconAction(R.drawable.ic_car_location) {
                if (!surfaceController.recenter()) CarToast.makeText(carContext, "Дождитесь определения координат GPS", CarToast.LENGTH_SHORT).show()
            }).build()
        return NavigationTemplate.Builder().setActionStrip(applicationActions).setMapActionStrip(mapActions)
            .setPanModeListener(surfaceController::setPanMode).build()
    }
    private fun iconAction(resourceId: Int, listener: Runnable): Action = Action.Builder().setIcon(
        CarIcon.Builder(IconCompat.createWithResource(carContext, resourceId)).build())
        .setOnClickListener(listener::run).build()
}
