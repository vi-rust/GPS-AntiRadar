package ru.gpsantiradar.app

import android.content.Context
import androidx.car.app.*
import androidx.car.app.model.*

class CarMapKeyScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val help = ItemList.Builder().addItem(Row.Builder().setTitle("Ввод ключа")
            .addText("Если клавиатура недоступна в движении, выполните ввод на телефоне.").build()).build()
        return SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
            override fun onSearchSubmitted(searchText: String) = save(searchText)
        }).setHeaderAction(Action.BACK).setSearchHint("Ключ Yandex MapKit")
            .setShowKeyboardByDefault(true).setItemList(help).build()
    }
    private fun save(searchText: String?) {
        val key = searchText?.trim().orEmpty()
        if (key.isEmpty()) {
            CarToast.makeText(carContext, "Ключ не может быть пустым", CarToast.LENGTH_SHORT).show()
            return
        }
        carContext.getSharedPreferences(AppSettings.PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString(AppSettings.MAPKIT_KEY, key).remove(AppSettings.MAPKIT_PENDING)
            .putBoolean(AppSettings.MAPKIT_SAFE_MIGRATION, true).putBoolean(AppSettings.MAPKIT_MARKER_FIX, true)
            .putBoolean(AppSettings.MAPKIT_KEY_REENTRY, true).commit()
        CarToast.makeText(carContext, "Ключ сохранён. Полностью перезапустите приложение.", CarToast.LENGTH_LONG).show()
    }
}
