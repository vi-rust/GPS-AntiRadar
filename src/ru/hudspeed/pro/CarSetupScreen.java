package ru.gpsantiradar.app;

import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.ScreenManager;
import androidx.car.app.model.Action;
import androidx.car.app.model.MessageTemplate;
import androidx.car.app.model.Template;

public final class CarSetupScreen extends Screen {
    private final boolean locationGranted;
    private final boolean mapKitReady;
    private boolean trackingActive;
    private final String surfaceError;

    public CarSetupScreen(CarContext carContext, boolean locationGranted,
            boolean mapKitReady) {
        this(carContext, locationGranted, mapKitReady, false, null);
    }

    public CarSetupScreen(CarContext carContext, boolean locationGranted,
            boolean mapKitReady, String surfaceError) {
        this(carContext, locationGranted, mapKitReady, false, surfaceError);
    }

    public CarSetupScreen(CarContext carContext, boolean locationGranted,
            boolean mapKitReady, boolean trackingActive, String surfaceError) {
        super(carContext);
        this.locationGranted = locationGranted;
        this.mapKitReady = mapKitReady;
        this.trackingActive = trackingActive;
        this.surfaceError = surfaceError;
    }

    @Override public Template onGetTemplate() {
        StringBuilder message = new StringBuilder();
        if (!locationGranted) {
            message.append("Разрешите доступ к геопозиции в приложении на телефоне.");
        }
        if (!mapKitReady) {
            if (message.length() > 0) message.append("\n\n");
            if (trackingActive) {
                message.append("Антирадар и звуковые предупреждения работают без карты.\n\n");
            } else if (locationGranted) {
                message.append("Антирадар остановлен. Повторно откройте приложение для запуска.\n\n");
            }
            message.append("Укажите ключ Yandex MapKit здесь или в приложении на телефоне, "
                    + "затем переподключите Android Auto.");
        }
        if (surfaceError != null && !surfaceError.trim().isEmpty()) {
            if (message.length() > 0) message.append("\n\n");
            message.append(surfaceError);
        }
        MessageTemplate.Builder template = new MessageTemplate.Builder(message)
                .setTitle("Настройка GPS AntiRadar")
                .setHeaderAction(Action.BACK);
        if (!mapKitReady) {
            template.addAction(new Action.Builder()
                    .setTitle("Ввести ключ MapKit")
                    .setOnClickListener(() -> getCarContext()
                            .getCarService(ScreenManager.class)
                            .push(new CarMapKeyScreen(getCarContext())))
                    .build());
        }
        return template.build();
    }

    public void setTrackingActive(boolean active) {
        if (trackingActive == active) return;
        trackingActive = active;
        invalidate();
    }
}
