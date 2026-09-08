package ru.gpsantiradar.app;

import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.model.Action;
import androidx.car.app.model.CarIcon;
import androidx.car.app.model.ItemList;
import androidx.car.app.model.ListTemplate;
import androidx.car.app.model.Row;
import androidx.car.app.model.Template;
import androidx.car.app.model.Toggle;
import androidx.core.graphics.drawable.IconCompat;

public final class CarMenuScreen extends Screen {
    interface UpdateController {
        void requestUpdate();
    }

    interface ExitAction {
        void exit();
    }

    private final CarSurfaceController surfaceController;
    private final UpdateController updateController;
    private final ExitAction exitAction;

    public CarMenuScreen(CarContext carContext,
            CarSurfaceController surfaceController) {
        this(carContext, surfaceController,
                new SharedUpdateController(application(carContext).radarBaseUpdater()),
                new ExitAction() {
                    @Override public void exit() {
                        TrackingService.requestStop(carContext);
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
                .setTitle(item == CarMenuItem.THEME
                        ? "Тема: " + ThemeSettings.mode(getCarContext()).title()
                        : title(item))
                .setImage(icon(iconResource(item)), Row.IMAGE_TYPE_ICON);
        if (item == CarMenuItem.AUTO_ROTATE_MAP) {
            boolean enabled = getCarContext().getSharedPreferences(
                    AppSettings.PREFERENCES, android.content.Context.MODE_PRIVATE)
                    .getBoolean(AppSettings.AUTO_ROTATE_MAP,
                            AppSettings.DEFAULT_AUTO_ROTATE_MAP);
            row.setToggle(new Toggle.Builder(checked -> {
                getCarContext().getSharedPreferences(
                        AppSettings.PREFERENCES, android.content.Context.MODE_PRIVATE)
                        .edit().putBoolean(AppSettings.AUTO_ROTATE_MAP, checked).apply();
                invalidate();
            }).setChecked(enabled).build());
        } else {
            row.setBrowsable(item != CarMenuItem.UPDATE_DATABASE
                            && item != CarMenuItem.EXIT)
                    .setOnClickListener(() -> select(item));
        }
        return row.build();
    }

    private void select(CarMenuItem item) {
        switch (item) {
            case UPDATE_DATABASE:
                updateController.requestUpdate();
                break;
            case OVERSPEED_THRESHOLD:
                getScreenManager().push(new CarValueScreen(getCarContext(),
                        CarValueScreen.Setting.OVERSPEED_THRESHOLD, surfaceController));
                break;
            case HUD_TRANSPARENCY:
                getScreenManager().push(new CarValueScreen(getCarContext(),
                        CarValueScreen.Setting.HUD_TRANSPARENCY, surfaceController));
                break;
            case AUTO_ROTATE_MAP:
                break;
            case THEME:
                getScreenManager().push(new CarThemeScreen(
                        getCarContext(), surfaceController));
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

    private CarIcon icon(int resourceId) {
        return new CarIcon.Builder(
                IconCompat.createWithResource(getCarContext(), resourceId)).build();
    }

    private static int iconResource(CarMenuItem item) {
        switch (item) {
            case UPDATE_DATABASE:
                return R.drawable.ic_refresh;
            case OVERSPEED_THRESHOLD:
                return R.drawable.ic_speed_limit;
            case HUD_TRANSPARENCY:
                return R.drawable.ic_opacity;
            case AUTO_ROTATE_MAP:
                return R.drawable.ic_navigation;
            case THEME:
                return R.drawable.ic_theme;
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
            case OVERSPEED_THRESHOLD:
                return "Предел превышения скорости";
            case HUD_TRANSPARENCY:
                return "Прозрачность HUD";
            case AUTO_ROTATE_MAP:
                return "Автоповорот карты";
            case THEME:
                return "Тема";
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
    }
}
