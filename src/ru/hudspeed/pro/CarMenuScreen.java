package ru.gpsantiradar.app;

import android.content.Intent;

import androidx.car.app.CarContext;
import androidx.car.app.CarToast;
import androidx.car.app.Screen;
import androidx.car.app.model.Action;
import androidx.car.app.model.CarIcon;
import androidx.car.app.model.ItemList;
import androidx.car.app.model.ListTemplate;
import androidx.car.app.model.Row;
import androidx.car.app.model.Template;
import androidx.core.graphics.drawable.IconCompat;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

public final class CarMenuScreen extends Screen {
    interface UpdateController {
        void requestUpdate();
        void addListener(RadarBaseUpdater.Listener listener, boolean replayLatest);
        void removeListener(RadarBaseUpdater.Listener listener);
    }

    interface ExitAction {
        void exit();
    }

    private final CarSurfaceController surfaceController;
    private final UpdateController updateController;
    private final ExitAction exitAction;
    private boolean listeningForUpdates;

    private final RadarBaseUpdater.Listener updateListener =
            new RadarBaseUpdater.Listener() {
                @Override public void onRadarBaseUpdate(RadarBaseUpdateState state) {
                    showUpdateState(state);
                }
            };

    public CarMenuScreen(CarContext carContext,
            CarSurfaceController surfaceController) {
        this(carContext, surfaceController,
                new SharedUpdateController(application(carContext).radarBaseUpdater()),
                new ExitAction() {
                    @Override public void exit() {
                        carContext.stopService(
                                new Intent(carContext, TrackingService.class));
                        carContext.getCarService(
                                androidx.car.app.ScreenManager.class).popToRoot();
                        carContext.finishCarApp();
                    }
                });
    }

    CarMenuScreen(CarContext carContext, CarSurfaceController surfaceController,
            UpdateController updateController, ExitAction exitAction) {
        super(carContext);
        this.surfaceController = surfaceController;
        this.updateController = updateController;
        this.exitAction = exitAction;
        getLifecycle().addObserver(new DefaultLifecycleObserver() {
            @Override public void onStart(LifecycleOwner owner) {
                startListening();
            }

            @Override public void onStop(LifecycleOwner owner) {
                stopListening();
            }

            @Override public void onDestroy(LifecycleOwner owner) {
                stopListening();
            }
        });
    }

    @Override public Template onGetTemplate() {
        ItemList.Builder items = new ItemList.Builder();
        for (CarMenuItem item : CarMenuItem.values()) {
            items.addItem(row(item));
        }
        return new ListTemplate.Builder()
                .setTitle("Меню")
                .setHeaderAction(Action.BACK)
                .setSingleList(items.build())
                .build();
    }

    private Row row(CarMenuItem item) {
        Row.Builder row = new Row.Builder()
                .setTitle(title(item))
                .setImage(icon(iconResource(item)), Row.IMAGE_TYPE_ICON)
                .setBrowsable(item != CarMenuItem.UPDATE_DATABASE
                        && item != CarMenuItem.EXIT)
                .setOnClickListener(() -> select(item));
        return row.build();
    }

    private void select(CarMenuItem item) {
        switch (item) {
            case UPDATE_DATABASE:
                updateController.requestUpdate();
                break;
            case ALERT_DISTANCE:
                getScreenManager().push(new CarValueScreen(getCarContext(),
                        CarValueScreen.Setting.ALERT_DISTANCE, surfaceController));
                break;
            case OVERSPEED_THRESHOLD:
                getScreenManager().push(new CarValueScreen(getCarContext(),
                        CarValueScreen.Setting.OVERSPEED_THRESHOLD, surfaceController));
                break;
            case HUD_TRANSPARENCY:
                getScreenManager().push(new CarValueScreen(getCarContext(),
                        CarValueScreen.Setting.HUD_TRANSPARENCY, surfaceController));
                break;
            case MAPKIT_KEY:
                getScreenManager().push(new CarMapKeyScreen(getCarContext()));
                break;
            case ABOUT:
                getScreenManager().push(new CarAboutScreen(getCarContext()));
                break;
            case EXIT:
                exitAction.exit();
                break;
            default:
                throw new AssertionError(item);
        }
    }

    private void startListening() {
        if (listeningForUpdates) return;
        listeningForUpdates = true;
        updateController.addListener(updateListener, false);
    }

    private void stopListening() {
        if (!listeningForUpdates) return;
        listeningForUpdates = false;
        updateController.removeListener(updateListener);
    }

    private void showUpdateState(RadarBaseUpdateState state) {
        if (state == null || state.status == RadarBaseUpdateState.Status.IDLE) return;
        int duration = state.status == RadarBaseUpdateState.Status.STARTED
                || state.status == RadarBaseUpdateState.Status.ALREADY_RUNNING
                ? CarToast.LENGTH_SHORT : CarToast.LENGTH_LONG;
        CarToast.makeText(getCarContext(), state.message, duration).show();
    }

    private CarIcon icon(int resourceId) {
        return new CarIcon.Builder(
                IconCompat.createWithResource(getCarContext(), resourceId)).build();
    }

    private static int iconResource(CarMenuItem item) {
        switch (item) {
            case UPDATE_DATABASE:
                return R.drawable.ic_refresh;
            case ALERT_DISTANCE:
                return R.drawable.ic_distance;
            case OVERSPEED_THRESHOLD:
                return R.drawable.ic_speed_limit;
            case HUD_TRANSPARENCY:
                return R.drawable.ic_opacity;
            case MAPKIT_KEY:
                return R.drawable.ic_key;
            case ABOUT:
                return R.drawable.ic_info;
            case EXIT:
                return R.drawable.ic_exit;
            default:
                throw new AssertionError(item);
        }
    }

    private static String title(CarMenuItem item) {
        switch (item) {
            case UPDATE_DATABASE:
                return "Обновить базу";
            case ALERT_DISTANCE:
                return "Расстояние оповещения";
            case OVERSPEED_THRESHOLD:
                return "Предел превышения для beep";
            case HUD_TRANSPARENCY:
                return "Прозрачность HUD";
            case MAPKIT_KEY:
                return "Ключ MapKit";
            case ABOUT:
                return "О программе";
            case EXIT:
                return "Выход";
            default:
                throw new AssertionError(item);
        }
    }

    private static GpsAntiRadarApplication application(CarContext carContext) {
        return (GpsAntiRadarApplication) carContext.getApplicationContext();
    }

    private static final class SharedUpdateController implements UpdateController {
        private final RadarBaseUpdater updater;

        SharedUpdateController(RadarBaseUpdater updater) {
            this.updater = updater;
        }

        @Override public void requestUpdate() {
            updater.requestUpdate();
        }

        @Override public void addListener(
                RadarBaseUpdater.Listener listener, boolean replayLatest) {
            updater.addListener(listener, replayLatest);
        }

        @Override public void removeListener(RadarBaseUpdater.Listener listener) {
            updater.removeListener(listener);
        }
    }
}
