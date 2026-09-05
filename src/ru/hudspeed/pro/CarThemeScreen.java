package ru.gpsantiradar.app;

import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.model.Action;
import androidx.car.app.model.ItemList;
import androidx.car.app.model.ListTemplate;
import androidx.car.app.model.Row;
import androidx.car.app.model.Template;

public final class CarThemeScreen extends Screen {
    private final Runnable themeChanged;

    public CarThemeScreen(CarContext carContext,
                          CarSurfaceController surfaceController) {
        this(carContext, surfaceController == null
                ? () -> {}
                : surfaceController::onCarConfigurationChanged);
    }

    CarThemeScreen(CarContext carContext, Runnable themeChanged) {
        super(carContext);
        this.themeChanged = themeChanged == null ? () -> {} : themeChanged;
    }

    @Override public Template onGetTemplate() {
        ThemeMode selected = ThemeSettings.mode(getCarContext());
        ItemList.Builder items = new ItemList.Builder();
        for (ThemeMode mode : ThemeMode.values()) {
            Row.Builder row = new Row.Builder()
                    .setTitle(mode.title())
                    .setOnClickListener(() -> select(mode));
            if (mode == selected) row.addText("Выбрано");
            items.addItem(row.build());
        }
        return new ListTemplate.Builder()
                .setTitle("Тема")
                .setHeaderAction(Action.BACK)
                .setSingleList(items.build())
                .build();
    }

    private void select(ThemeMode mode) {
        ThemeSettings.setMode(getCarContext(), mode);
        themeChanged.run();
        invalidate();
    }
}
