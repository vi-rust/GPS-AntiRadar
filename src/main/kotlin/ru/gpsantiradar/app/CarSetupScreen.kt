package ru.gpsantiradar.app

import androidx.car.app.*
import androidx.car.app.model.*

class CarSetupScreen : Screen {
    private val locationGranted: Boolean
    private val mapKitReady: Boolean
    private var trackingActive: Boolean
    private val surfaceError: String?
    constructor(carContext: CarContext, locationGranted: Boolean, mapKitReady: Boolean) :
        this(carContext, locationGranted, mapKitReady, false, null)
    constructor(carContext: CarContext, locationGranted: Boolean, mapKitReady: Boolean, surfaceError: String?) :
        this(carContext, locationGranted, mapKitReady, false, surfaceError)
    constructor(carContext: CarContext, locationGranted: Boolean, mapKitReady: Boolean,
        trackingActive: Boolean, surfaceError: String?) : super(carContext) {
        this.locationGranted = locationGranted; this.mapKitReady = mapKitReady
        this.trackingActive = trackingActive; this.surfaceError = surfaceError
    }
    override fun onGetTemplate(): Template {
        val message = StringBuilder()
        if (!locationGranted) message.append("Разрешите доступ к геопозиции в приложении на телефоне.")
        if (!mapKitReady) {
            if (message.isNotEmpty()) message.append("\n\n")
            if (trackingActive) message.append("Антирадар и звуковые предупреждения работают без карты.\n\n")
            else if (locationGranted) message.append("Антирадар остановлен. Повторно откройте приложение для запуска.\n\n")
            message.append("Укажите ключ Yandex MapKit здесь или в приложении на телефоне, затем переподключите Android Auto.")
        }
        if (!surfaceError.isNullOrBlank()) {
            if (message.isNotEmpty()) message.append("\n\n")
            message.append(surfaceError)
        }
        val template = MessageTemplate.Builder(message).setTitle("Настройка GPS AntiRadar").setHeaderAction(Action.BACK)
        if (!mapKitReady) template.addAction(Action.Builder().setTitle("Ввести ключ MapKit").setOnClickListener {
            carContext.getCarService(ScreenManager::class.java).push(CarMapKeyScreen(carContext))
        }.build())
        return template.build()
    }
    fun setTrackingActive(active: Boolean) {
        if (trackingActive == active) return
        trackingActive = active
        invalidate()
    }
}
