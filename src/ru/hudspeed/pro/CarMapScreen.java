package ru.gpsantiradar.app;

import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.ScreenManager;
import androidx.car.app.model.Action;
import androidx.car.app.model.ActionStrip;
import androidx.car.app.model.CarIcon;
import androidx.car.app.model.Template;
import androidx.car.app.navigation.model.NavigationTemplate;
import androidx.core.graphics.drawable.IconCompat;

public final class CarMapScreen extends Screen {
    private final CarSurfaceController surfaceController;
    private final Runnable menuAction;

    public CarMapScreen(CarContext carContext, CarSurfaceController surfaceController) {
        this(carContext, surfaceController, () -> carContext
                .getCarService(ScreenManager.class)
                .push(new CarMenuScreen(carContext, surfaceController)));
    }

    CarMapScreen(CarContext carContext, CarSurfaceController surfaceController,
            Runnable menuAction) {
        super(carContext);
        this.surfaceController = surfaceController;
        this.menuAction = menuAction;
    }

    @Override public Template onGetTemplate() {
        ActionStrip applicationActions = new ActionStrip.Builder()
                .addAction(new Action.Builder()
                        .setIcon(new CarIcon.Builder(IconCompat.createWithResource(
                                getCarContext(), R.drawable.ic_car_menu)).build())
                        .setOnClickListener(menuAction::run)
                        .build())
                .build();
        ActionStrip mapActions = new ActionStrip.Builder()
                .addAction(Action.PAN)
                .addAction(iconAction(R.drawable.ic_car_zoom_in,
                        new Runnable() {
                            @Override public void run() {
                                surfaceController.zoomBy(1f);
                            }
                        }))
                .addAction(iconAction(R.drawable.ic_car_zoom_out,
                        new Runnable() {
                            @Override public void run() {
                                surfaceController.zoomBy(-1f);
                            }
                        }))
                .addAction(iconAction(R.drawable.ic_car_location,
                        new Runnable() {
                            @Override public void run() {
                                surfaceController.recenter();
                            }
                        }))
                .build();
        return new NavigationTemplate.Builder()
                .setActionStrip(applicationActions)
                .setMapActionStrip(mapActions)
                .setPanModeListener(surfaceController::setPanMode)
                .build();
    }

    private Action iconAction(int resourceId, Runnable listener) {
        CarIcon icon = new CarIcon.Builder(
                IconCompat.createWithResource(getCarContext(), resourceId)).build();
        return new Action.Builder()
                .setIcon(icon)
                .setOnClickListener(listener::run)
                .build();
    }
}
