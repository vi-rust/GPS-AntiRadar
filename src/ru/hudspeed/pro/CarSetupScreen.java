package ru.gpsantiradar.app;

import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.model.Action;
import androidx.car.app.model.MessageTemplate;
import androidx.car.app.model.Template;

public final class CarSetupScreen extends Screen {
    private final boolean locationGranted;
    private final boolean mapKitReady;

    public CarSetupScreen(CarContext carContext, boolean locationGranted,
            boolean mapKitReady) {
        super(carContext);
        this.locationGranted = locationGranted;
        this.mapKitReady = mapKitReady;
    }

    @Override public Template onGetTemplate() {
        StringBuilder message = new StringBuilder();
        if (!locationGranted) {
            message.append("Разрешите доступ к геопозиции в приложении на телефоне.");
        }
        if (!mapKitReady) {
            if (message.length() > 0) message.append("\n\n");
            message.append("Укажите ключ Yandex MapKit в приложении на телефоне, "
                    + "затем переподключите Android Auto.");
        }
        return new MessageTemplate.Builder(message)
                .setTitle("Настройка GPS AntiRadar")
                .setHeaderAction(Action.BACK)
                .build();
    }
}
