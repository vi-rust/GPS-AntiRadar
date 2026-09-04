package ru.gpsantiradar.app;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.model.Action;
import androidx.car.app.model.CarIcon;
import androidx.car.app.model.Pane;
import androidx.car.app.model.PaneTemplate;
import androidx.car.app.model.Row;
import androidx.car.app.model.Template;
import androidx.core.graphics.drawable.IconCompat;

public final class CarValueScreen extends Screen {
    public enum Setting {
        ALERT_DISTANCE(
                AppSettings.ALERT_DISTANCE,
                "Расстояние оповещения",
                AppSettings.DEFAULT_ALERT_DISTANCE_METERS,
                AppSettings.MIN_ALERT_DISTANCE_METERS,
                AppSettings.MAX_ALERT_DISTANCE_METERS,
                AppSettings.ALERT_DISTANCE_STEP_METERS),
        OVERSPEED_THRESHOLD(
                AppSettings.OVERSPEED_THRESHOLD,
                "Предел превышения скорости",
                AppSettings.DEFAULT_OVERSPEED_THRESHOLD_KMH,
                AppSettings.MIN_OVERSPEED_THRESHOLD_KMH,
                AppSettings.MAX_OVERSPEED_THRESHOLD_KMH,
                AppSettings.OVERSPEED_THRESHOLD_STEP_KMH),
        HUD_TRANSPARENCY(
                AppSettings.HUD_TRANSPARENCY,
                "Прозрачность HUD",
                AppSettings.DEFAULT_HUD_TRANSPARENCY_PERCENT,
                AppSettings.MIN_HUD_TRANSPARENCY_PERCENT,
                AppSettings.MAX_HUD_TRANSPARENCY_PERCENT,
                AppSettings.HUD_TRANSPARENCY_STEP_PERCENT);

        private final String preferenceKey;
        private final String title;
        private final int defaultValue;
        private final int minValue;
        private final int maxValue;
        private final int step;

        Setting(String preferenceKey, String title, int defaultValue,
                int minValue, int maxValue, int step) {
            this.preferenceKey = preferenceKey;
            this.title = title;
            this.defaultValue = defaultValue;
            this.minValue = minValue;
            this.maxValue = maxValue;
            this.step = step;
        }

        public String preferenceKey() {
            return preferenceKey;
        }

        public String title() {
            return title;
        }

        public int defaultValue() {
            return defaultValue;
        }

        public int minValue() {
            return minValue;
        }

        public int maxValue() {
            return maxValue;
        }

        public int step() {
            return step;
        }

        public int normalize(int value) {
            switch (this) {
                case ALERT_DISTANCE:
                    return AppSettings.clampAlertDistance(value);
                case OVERSPEED_THRESHOLD:
                    return AppSettings.clampOverspeedThreshold(value);
                case HUD_TRANSPARENCY:
                    return AppSettings.clampHudTransparency(value);
                default:
                    throw new AssertionError(this);
            }
        }

        public int adjust(int value, int direction) {
            switch (this) {
                case ALERT_DISTANCE:
                    return AppSettings.adjustAlertDistance(value, direction);
                case OVERSPEED_THRESHOLD:
                    return AppSettings.adjustOverspeedThreshold(value, direction);
                case HUD_TRANSPARENCY:
                    return AppSettings.adjustHudTransparency(value, direction);
                default:
                    throw new AssertionError(this);
            }
        }

        public String format(int value) {
            int normalized = normalize(value);
            switch (this) {
                case ALERT_DISTANCE:
                    return normalized + " м";
                case OVERSPEED_THRESHOLD:
                    return normalized + " км/ч";
                case HUD_TRANSPARENCY:
                    return normalized + "%";
                default:
                    throw new AssertionError(this);
            }
        }
    }

    private static final Runnable NO_OP = new Runnable() {
        @Override public void run() {}
    };

    private final Setting setting;
    private final Runnable hudTransparencyRefresh;
    private final SharedPreferences preferences;

    public CarValueScreen(CarContext carContext, Setting setting,
            CarSurfaceController surfaceController) {
        this(carContext, setting, surfaceController == null
                ? NO_OP : surfaceController::refreshHudTransparency);
    }

    CarValueScreen(CarContext carContext, Setting setting,
            Runnable hudTransparencyRefresh) {
        super(carContext);
        this.setting = setting;
        this.hudTransparencyRefresh =
                hudTransparencyRefresh == null ? NO_OP : hudTransparencyRefresh;
        preferences = carContext.getSharedPreferences(
                AppSettings.PREFERENCES, Context.MODE_PRIVATE);
    }

    @Override public Template onGetTemplate() {
        Pane pane = new Pane.Builder()
                .addRow(new Row.Builder()
                        .setTitle(setting.format(currentValue()))
                        .addText("Диапазон: " + setting.format(setting.minValue())
                                + " – " + setting.format(setting.maxValue()))
                        .build())
                .addAction(valueAction(R.drawable.ic_remove, -1))
                .addAction(valueAction(R.drawable.ic_add, 1))
                .build();
        return new PaneTemplate.Builder(pane)
                .setTitle(setting.title())
                .setHeaderAction(Action.BACK)
                .build();
    }

    private int currentValue() {
        return setting.normalize(preferences.getInt(
                setting.preferenceKey(), setting.defaultValue()));
    }

    private Action valueAction(int iconResource, int direction) {
        CarIcon icon = new CarIcon.Builder(
                IconCompat.createWithResource(getCarContext(), iconResource)).build();
        return new Action.Builder()
                .setIcon(icon)
                .setOnClickListener(() -> changeValue(direction))
                .build();
    }

    private void changeValue(int direction) {
        int adjusted = setting.adjust(currentValue(), direction);
        preferences.edit().putInt(setting.preferenceKey(), adjusted).commit();
        if (setting == Setting.HUD_TRANSPARENCY) {
            hudTransparencyRefresh.run();
        }
        invalidate();
    }
}
