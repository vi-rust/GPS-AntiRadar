package ru.gpsantiradar.app;

import android.content.Context;

import androidx.car.app.CarContext;
import androidx.car.app.CarToast;
import androidx.car.app.Screen;
import androidx.car.app.model.Action;
import androidx.car.app.model.ItemList;
import androidx.car.app.model.Row;
import androidx.car.app.model.SearchTemplate;
import androidx.car.app.model.Template;

public final class CarMapKeyScreen extends Screen {
    public CarMapKeyScreen(CarContext carContext) {
        super(carContext);
    }

    @Override public Template onGetTemplate() {
        ItemList help = new ItemList.Builder()
                .addItem(new Row.Builder()
                        .setTitle("Ввод ключа")
                        .addText("Если клавиатура недоступна в движении, "
                                + "выполните ввод на телефоне.")
                        .build())
                .build();
        return new SearchTemplate.Builder(new SearchTemplate.SearchCallback() {
                    @Override public void onSearchSubmitted(String searchText) {
                        save(searchText);
                    }
                })
                .setHeaderAction(Action.BACK)
                .setSearchHint("Ключ Yandex MapKit")
                .setShowKeyboardByDefault(true)
                .setItemList(help)
                .build();
    }

    private void save(String searchText) {
        String key = searchText == null ? "" : searchText.trim();
        if (key.isEmpty()) {
            CarToast.makeText(getCarContext(), "Ключ не может быть пустым",
                    CarToast.LENGTH_SHORT).show();
            return;
        }
        getCarContext().getSharedPreferences(
                AppSettings.PREFERENCES, Context.MODE_PRIVATE)
                .edit().putString(AppSettings.MAPKIT_KEY, key).commit();
        CarToast.makeText(getCarContext(),
                "Ключ сохранён. Полностью перезапустите приложение.",
                CarToast.LENGTH_LONG).show();
    }
}
