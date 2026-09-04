# План реализации Android Auto и иммерсивного режима

> **Для агентов-разработчиков:** ОБЯЗАТЕЛЬНЫЙ ДОПОЛНИТЕЛЬНЫЙ НАВЫК: выполняйте этот план с помощью `superpowers:subagent-driven-development` (рекомендуется) или `superpowers:executing-plans`. Каждый флажок является отдельным проверяемым шагом; после каждого RED/GREEN цикла проверяйте diff до коммита.

**Цель:** скрыть системные панели на телефоне, добавить запускаемый из Android Auto экран движения с картой, HUD и всеми существующими функциями меню, сохранив один экземпляр алгоритма отслеживания и звуковых оповещений.

**Архитектура:** `TrackingService` остаётся единственным владельцем GPS, выбора объектов, состояний зоны и звука. Телефон и Android Auto получают один и тот же адресованный пакету `Intent` и строят независимые представления из общего `DrivingSnapshot`. Сетевой импорт RadarBase переходит в `RadarBaseUpdater` уровня `Application`, а поведение маркеров карты переносится в `SharedCameraMapLayer` с отдельным экземпляром на каждый `MapWindow`. Android Auto предоставляет `NavigationTemplate` и выводит Yandex `MapView` с HUD в поверхность хоста через `VirtualDisplay` и `Presentation`.

**Технологии:** Java 8, Android SDK 36, minSdk 26, Android for Cars App Library 1.7.0, Yandex MapKit 4.42.0 Lite, Android Views, PowerShell/JVM-тесты, Android Auto Desktop Head Unit.

**Спецификация:** `docs/superpowers/specs/2026-09-04-android-auto-design.md`

## Общие ограничения

- Не изменять `StrelkaAlertTracker` и не создавать второй источник звука: оба экрана только отображают данные `TrackingService`.
- Автоматическое обновление RadarBase запускать один раз из `GpsAntiRadarApplication.onCreate()` при холодном старте процесса независимо от первой точки входа.
- Сохранить HTTP-кэширование, атомарный импорт, `RadarBaseUpdateSingleFlight` и правило: HTTP 304 не меняет время последней успешной загрузки.
- Сохранить 20-процентный буфер окна карты, поколение асинхронного запроса, порог одиночных маркеров 14 и инкрементальный `MapMarkerEntityDiff`.
- Телефон и автомобиль используют разные `MapView`/`MapWindow`, но один код слоя карты и одни ресурсы `cam_type_*`.
- Для `NavigationTemplate.setMapActionStrip()` требуется Car API 2, поэтому объявить `androidx.car.app.minCarApiLevel=2`.
- Не объявлять активное пошаговое ведение и не публиковать сборку в Google Play.
- Разрешить все хосты только в `GpsCarAppService.createHostValidator()`, поскольку APK устанавливается вручную.
- Скрывать системные панели только у `MainActivity`. Интерфейс Android Auto принадлежит хосту.
- Выпустить 4.9.5 с `versionCode=40` и установить поверх текущей версии без удаления пользовательских данных.
- Не изменять посторонние пользовательские правки.

---

### Задача 1: общая модель движения, HUD и настройки

**Файлы:**
- Создать: `src/ru/hudspeed/pro/DrivingSnapshot.java`
- Создать: `src/ru/hudspeed/pro/DrivingSnapshotIntent.java`
- Создать: `src/ru/hudspeed/pro/DrivingHudPresentation.java`
- Изменить: `src/ru/hudspeed/pro/AppSettings.java`
- Изменить: `src/ru/hudspeed/pro/MainActivity.java`
- Изменить: `src/ru/hudspeed/pro/TrackingService.java`
- Изменить: `tests/ParserGeoTest.java`
- Изменить: `tests/AndroidSourceContractTest.ps1`
- Изменить: `test.ps1`

**Интерфейсы:**

~~~java
public final class DrivingSnapshot {
    public static DrivingSnapshot idle();
    public boolean hasLocation();
    public boolean hasObject();
    // final: speedKmh, distanceMeters, cameraName, cameraId, speedLimitKmh,
    // alertDistanceMeters, latitude, longitude, alertState, alertAlgorithm.
}

public final class DrivingSnapshotIntent {
    public static DrivingSnapshot from(Intent intent);
}

public final class DrivingHudPresentation {
    public static DrivingHudPresentation from(DrivingSnapshot snapshot);
    // final: speedText, distanceText, cameraText, speedColor, hasActiveObject.
}
~~~

- [ ] **Шаг 1: написать падающие JVM-тесты**

Добавить проверки неизменяемого снимка без объекта, снимка внутри зоны, расстояния меньше и больше километра и цветового правила превышения. Добавить проверки диапазонов:

~~~java
check(AppSettings.adjustAlertDistance(300, -1) == 300
        && AppSettings.adjustAlertDistance(300, 1) == 400
        && AppSettings.adjustAlertDistance(2000, 1) == 2000,
        "alert distance uses 300..2000 with a 100 meter step");
check(AppSettings.adjustOverspeedThreshold(0, -1) == 0
        && AppSettings.adjustOverspeedThreshold(20, 1) == 20,
        "overspeed threshold uses 0..20 with a 1 km/h step");
check(AppSettings.adjustHudTransparency(0, -1) == 0
        && AppSettings.adjustHudTransparency(75, 1) == 80
        && AppSettings.adjustHudTransparency(80, 1) == 80,
        "HUD transparency uses 0..80 with a 5 percent step");
~~~

В `test.ps1` добавить новые чистые Java-классы в `javac`. Контракт должен требовать в `MainActivity` вызов `DrivingSnapshotIntent.from(intent)` вместо повторного чтения `TrackingService.EXTRA_*`.

- [ ] **Шаг 2: проверить RED**

Запустить `.\test.ps1`. Ожидается ошибка компиляции из-за отсутствующих классов и методов.

- [ ] **Шаг 3: централизовать ключи и диапазоны**

Добавить в `AppSettings`:

~~~java
public static final String MAPKIT_KEY = "yandex_mapkit_key";
public static final String HUD_TRANSPARENCY = "hud_transparency";
public static final int DEFAULT_ALERT_DISTANCE_METERS = 800;
public static final int MIN_ALERT_DISTANCE_METERS = 300;
public static final int MAX_ALERT_DISTANCE_METERS = 2000;
public static final int ALERT_DISTANCE_STEP_METERS = 100;
public static final int MIN_OVERSPEED_THRESHOLD_KMH = 0;
public static final int OVERSPEED_THRESHOLD_STEP_KMH = 1;
public static final int DEFAULT_HUD_TRANSPARENCY_PERCENT = 10;
public static final int MIN_HUD_TRANSPARENCY_PERCENT = 0;
public static final int MAX_HUD_TRANSPARENCY_PERCENT = 80;
public static final int HUD_TRANSPARENCY_STEP_PERCENT = 5;
~~~

Реализовать `clampAlertDistance`, `adjustAlertDistance`, `adjustOverspeedThreshold`, `clampHudTransparency` и `adjustHudTransparency`. Каждый `adjust*` нормализует текущее значение, применяет один целый шаг и снова ограничивает результат.

- [ ] **Шаг 4: реализовать снимок и HUD**

`DrivingSnapshot` не импортирует Android-классы. `DrivingSnapshotIntent` является единственным Android-адаптером и читает все `TrackingService.EXTRA_*`, включая новый `EXTRA_CAMERA_ID`; добавить этот extra в оба места формирования broadcast сервиса.

`DrivingHudPresentation.from()` сохраняет текущие русские формулировки:

~~~java
String speedText = Integer.toString(Math.round(snapshot.speedKmh));
String distanceText = snapshot.distanceMeters < 0 ? "—"
        : formatDistance(snapshot.distanceMeters);
String cameraText = snapshot.hasObject()
        ? snapshot.cameraName + limitSuffix(snapshot.speedLimitKmh)
        : "Объектов впереди нет";
~~~

Цвета хранить как обычные ARGB-`int` без `android.graphics.Color`. `alertAlgorithm` оставить в модели для диагностики, но не показывать в скоростной плашке.

- [ ] **Шаг 5: подключить MainActivity**

Receiver создаёт один `DrivingSnapshot`, обновляет три поля HUD через `DrivingHudPresentation` и передаёт координаты методу обновления позиции. Удалить дублирующее форматирование, сохранив существующие цвета и тексты.

- [ ] **Шаг 6: проверить GREEN и создать коммит**

~~~powershell
.\test.ps1
git diff --check
git add src/ru/hudspeed/pro/DrivingSnapshot.java `
        src/ru/hudspeed/pro/DrivingSnapshotIntent.java `
        src/ru/hudspeed/pro/DrivingHudPresentation.java `
        src/ru/hudspeed/pro/AppSettings.java `
        src/ru/hudspeed/pro/MainActivity.java `
        src/ru/hudspeed/pro/TrackingService.java `
        tests/ParserGeoTest.java tests/AndroidSourceContractTest.ps1 test.ps1
git commit -m "refactor: share driving display state"
~~~

Ожидается `ParserGeoTest: OK` и `AndroidSourceContractTest: OK`.

---

### Задача 2: обновление RadarBase уровня процесса

**Файлы:**
- Создать: `src/ru/hudspeed/pro/RadarBaseUpdateState.java`
- Создать: `src/ru/hudspeed/pro/RadarBaseUpdater.java`
- Изменить: `src/ru/hudspeed/pro/GpsAntiRadarApplication.java`
- Изменить: `src/ru/hudspeed/pro/MainActivity.java`
- Изменить: `tests/ParserGeoTest.java`
- Изменить: `tests/AndroidSourceContractTest.ps1`
- Изменить: `test.ps1`

**Интерфейсы:**

~~~java
public final class RadarBaseUpdateState {
    public enum Status { IDLE, STARTED, UNCHANGED, SUCCESS, ERROR, ALREADY_RUNNING }
    public final long sequence;
    public final Status status;
    public final int importedCount;
    public final boolean coordinatesCorrected;
    public final String message;
    public boolean isTerminal();
}

public final class RadarBaseUpdater {
    public static final String ACTION_DATABASE_UPDATED =
            "ru.gpsantiradar.app.RADARBASE_UPDATED";
    public interface Listener {
        void onRadarBaseUpdate(RadarBaseUpdateState state);
    }
    public void requestUpdate();
    public RadarBaseUpdateState latestState();
    public void addListener(Listener listener, boolean replayLatest);
    public void removeListener(Listener listener);
}
~~~

- [ ] **Шаг 1: написать падающие тесты**

Проверить состояния `STARTED -> UNCHANGED`, `STARTED -> SUCCESS` и `STARTED -> ERROR`. Только `SUCCESS` содержит число импортированных объектов. Контракт должен требовать один `requestUpdate()` из `Application.onCreate()`, отсутствие сети в Activity, запись времени после импорта и до `SUCCESS`, отсутствие записи времени при HTTP 304 и package-scoped broadcast после импорта.

- [ ] **Шаг 2: проверить RED**

Запустить `.\test.ps1`. Контракт должен обнаружить сеть в Activity и отсутствующие новые классы.

- [ ] **Шаг 3: перенести сетевой импорт**

Перенести в `RadarBaseUpdater` URL, ETag, Last-Modified, признак исправления координат и версию формата. Конструктор принимает application context и `RadarBaseUpdateSingleFlight`. `requestUpdate()`:

1. При занятом gate публикует `ALREADY_RUNNING`.
2. Публикует `STARTED` и запускает `radarbase-download`.
3. Для HTTP 304 публикует `UNCHANGED` без изменения времени.
4. Для HTTP 200 импортирует через `CameraDatabase`, сохраняет заголовки, формат и текущее время, отправляет `ACTION_DATABASE_UPDATED`, затем публикует `SUCCESS`.
5. Для исключения публикует `ERROR` с текущим пользовательским текстом.
6. В `finally` отключает соединение и освобождает gate.

Состояние хранить в `volatile`-поле, `sequence` увеличивать под `synchronized`, callbacks отправлять через `Handler(Looper.getMainLooper())` по копии listeners.

- [ ] **Шаг 4: передать владение Application**

~~~java
@Override public void onCreate() {
    super.onCreate();
    radarBaseUpdater = new RadarBaseUpdater(this, radarBaseUpdateGuard);
    radarBaseUpdater.requestUpdate();
}

public RadarBaseUpdater radarBaseUpdater() {
    return radarBaseUpdater;
}
~~~

Удалить из Application `ProcessLaunchGuard` и методы `claimRadarBaseStartupUpdate`, `tryStartRadarBaseUpdate`, `finishRadarBaseUpdate`. Сам чистый класс не удалять как несвязанную уборку.

- [ ] **Шаг 5: подключить телефон**

`MainActivity` регистрирует listener в `onStart` и снимает в `onStop`. Автоматический и ручной запуск используют одинаковые `Toast` для всех статусов. Кнопка меню вызывает только `application.radarBaseUpdater().requestUpdate()`. При `SUCCESS` broadcast обновляет «О программе» и видимое окно карты.

- [ ] **Шаг 6: проверить и создать коммит**

~~~powershell
.\test.ps1
.\gradlew.bat --no-daemon compileReleaseJavaWithJavac
git diff --check
git add src/ru/hudspeed/pro/RadarBaseUpdateState.java `
        src/ru/hudspeed/pro/RadarBaseUpdater.java `
        src/ru/hudspeed/pro/GpsAntiRadarApplication.java `
        src/ru/hudspeed/pro/MainActivity.java `
        tests/ParserGeoTest.java tests/AndroidSourceContractTest.ps1 test.ps1
git commit -m "refactor: own radar database updates in application"
~~~

---

### Задача 3: MapKit lifecycle и иммерсивный режим телефона

**Файлы:**
- Создать: `src/ru/hudspeed/pro/MapKitLifecycle.java`
- Изменить: `src/ru/hudspeed/pro/GpsAntiRadarApplication.java`
- Изменить: `src/ru/hudspeed/pro/MainActivity.java`
- Изменить: `tests/ParserGeoTest.java`
- Изменить: `tests/AndroidSourceContractTest.ps1`
- Изменить: `test.ps1`

- [ ] **Шаг 1: написать падающие тесты**

`MapKitLifecycle` принимает `Delegate.onStart/onStop`. Тест вызывает `acquire(), acquire(), release(), release(), release()`: delegate получает ровно один start и stop, счётчик не становится отрицательным. Контракт требует `applyImmersiveMode()` из `onCreate`, `onResume` и `onWindowFocusChanged(true)`.

- [ ] **Шаг 2: проверить RED**

Запустить `.\test.ps1`. Ожидаются отсутствующий класс и отсутствующие вызовы.

- [ ] **Шаг 3: добавить процессный счётчик**

Application предоставляет `acquireMapKit()`/`releaseMapKit()` поверх `MapKitLifecycle`, delegate которого вызывает `MapKitFactory.getInstance().onStart()/onStop()`. `MainActivity` заменяет прямые глобальные вызовы. Закрытие телефона не должно остановить MapKit активной автомобильной поверхности и наоборот.

- [ ] **Шаг 4: реализовать applyImmersiveMode**

API 30+:

~~~java
getWindow().setDecorFitsSystemWindows(false);
WindowInsetsController controller = getWindow().getInsetsController();
if (controller != null) {
    controller.hide(WindowInsets.Type.systemBars());
    controller.setSystemBarsBehavior(
            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
}
~~~

API 26–29: `SYSTEM_UI_FLAG_FULLSCREEN`, `HIDE_NAVIGATION`, `IMMERSIVE_STICKY`, `LAYOUT_STABLE`, `LAYOUT_FULLSCREEN` и `LAYOUT_HIDE_NAVIGATION`. Вызывать из `onCreate`, `onResume` и при возврате фокуса. Insets слоя карты учитывать только `WindowInsets.Type.displayCutout()`, чтобы временные панели не сдвигали HUD.

- [ ] **Шаг 5: проверить и создать коммит**

~~~powershell
.\test.ps1
.\gradlew.bat --no-daemon compileReleaseJavaWithJavac
git diff --check
git add src/ru/hudspeed/pro/MapKitLifecycle.java `
        src/ru/hudspeed/pro/GpsAntiRadarApplication.java `
        src/ru/hudspeed/pro/MainActivity.java `
        tests/ParserGeoTest.java tests/AndroidSourceContractTest.ps1 test.ps1
git commit -m "feat: add immersive phone mode"
~~~

---

### Задача 4: общий слой объектов карты

**Файлы:**
- Создать: `src/ru/hudspeed/pro/SharedCameraMapLayer.java`
- Изменить: `src/ru/hudspeed/pro/MainActivity.java`
- Изменить: `tests/AndroidSourceContractTest.ps1`

**Интерфейс:**

~~~java
public final class SharedCameraMapLayer {
    public interface Host {
        void postToUi(Runnable action);
        void onCameraTapped(CameraPoint camera, Point position);
    }
    public SharedCameraMapLayer(Context context, MapWindow mapWindow, Host host);
    public void loadInitial(boolean moveToData);
    public void refreshVisible();
    public void updateCurrentLocation(double latitude, double longitude, float heading);
    public void moveToCurrentLocation();
    public void zoomBy(float delta);
    public void pauseFollowing();
    public void resumeFollowing();
    public void destroy();
}
~~~

- [ ] **Шаг 1: написать падающий контракт**

Загрузить `SharedCameraMapLayer.java` и потребовать `(north - south) * 0.20`, `(east - west) * 0.20` и `MapMarkerEntityDiff.between`. В `MainActivity` запретить `renderedMarkerObjects`, `renderedMarkerEntities` и `renderCameraMarkers`.

- [ ] **Шаг 2: проверить RED**

Запустить `.\test.ps1`. Новый файл отсутствует.

- [ ] **Шаг 3: перенести поведение без изменения результата**

Перенести асинхронную загрузку с `cameraLoadGeneration`, `VisibleRegion` и буфером, layout/diff сущностей, кэши значков, полигоны зон, маркер позиции, `CameraListener`, паузу слежения и перемещение камеры. Каждый экземпляр создаёт собственные коллекции в своём `MapWindow`.

`destroy()` повышает поколение, снимает listeners, очищает только собственные коллекции и ссылки. Устаревший запрос проверяет поколение и `destroyed` перед применением.

- [ ] **Шаг 4: сохранить инкрементальность**

~~~java
List<MapMarkerLayout.Entity> entities =
        MapMarkerLayout.create(validPoints, map.getCameraPosition().getZoom());
MapMarkerEntityDiff.Result diff =
        MapMarkerEntityDiff.between(renderedMarkerEntities, entities);
~~~

Удалять только `removeKeys`, менять существующий placemark только для `update` и создавать только `add`. Запретить `cameraMarkerCollection.clear()` и `clusterPlacemarks()`. Полигоны можно очищать только при пересечении `COVERAGE_MIN_ZOOM`.

- [ ] **Шаг 5: подключить MainActivity**

Создать слой после `MapView`, передать callback существующей подсказки. Кнопки масштаба, возврат к позиции, database broadcast и снимок движения делегируют слою. В `onDestroy` вызвать `destroy()`.

- [ ] **Шаг 6: проверить и создать коммит**

~~~powershell
.\test.ps1
.\gradlew.bat --no-daemon compileReleaseJavaWithJavac
rg -n "clusterPlacemarks|cameraMarkerCollection\.clear" src
git diff --check
git add src/ru/hudspeed/pro/SharedCameraMapLayer.java `
        src/ru/hudspeed/pro/MainActivity.java `
        tests/AndroidSourceContractTest.ps1
git commit -m "refactor: share camera map layer"
~~~

Ожидаемый вывод `rg` пустой, существующие тесты layout/diff зелёные.

---

### Задача 5: зависимости и дескриптор Android Auto

**Файлы:**
- Изменить: `build.gradle`
- Создать: `res/xml/automotive_app_desc.xml`
- Изменить: `tests/AndroidSourceContractTest.ps1`

- [ ] **Шаг 1: написать падающий контракт**

Проверить зависимости и XML:

~~~groovy
implementation 'androidx.car.app:app:1.7.0'
implementation 'androidx.car.app:app-projected:1.7.0'
testImplementation 'androidx.car.app:app-testing:1.7.0'
testImplementation 'androidx.test:core:1.6.1'
testImplementation 'junit:junit:4.13.2'
testImplementation 'org.robolectric:robolectric:4.16.1'
~~~

Разобрать `res/xml/automotive_app_desc.xml` через `[xml]` и потребовать `<automotiveApp><uses name="template"/></automotiveApp>`.

- [ ] **Шаг 2: проверить RED**

`.\test.ps1` должен сообщить об отсутствующих зависимостях и XML-дескрипторе.

- [ ] **Шаг 3: добавить конфигурацию**

Добавить все шесть зависимостей точных версий из шага 1. Создать:

~~~xml
<?xml version="1.0" encoding="utf-8"?>
<automotiveApp>
    <uses name="template" />
</automotiveApp>
~~~

- [ ] **Шаг 4: проверить и создать коммит**

~~~powershell
.\test.ps1
.\gradlew.bat --no-daemon compileReleaseJavaWithJavac
git diff --check
git add build.gradle res/xml/automotive_app_desc.xml tests/AndroidSourceContractTest.ps1
git commit -m "build: add Android Auto dependencies"
~~~

---

### Задача 6: поверхность, карта и HUD Android Auto

**Файлы:**
- Изменить: `AndroidManifest.xml`
- Создать: `src/ru/hudspeed/pro/GpsCarAppService.java`
- Создать: `src/ru/hudspeed/pro/GpsCarSession.java`
- Создать: `src/ru/hudspeed/pro/CarSetupScreen.java`
- Создать: `src/ru/hudspeed/pro/CarSurfaceSpec.java`
- Создать: `src/ru/hudspeed/pro/CarSurfaceController.java`
- Создать: `src/ru/hudspeed/pro/CarMapPresentation.java`
- Создать: `src/ru/hudspeed/pro/CarMapScreen.java`
- Создать: `src/ru/hudspeed/pro/CarMapGestureController.java`
- Создать: `res/drawable/ic_car_zoom_in.xml`
- Создать: `res/drawable/ic_car_zoom_out.xml`
- Создать: `res/drawable/ic_car_pan.xml`
- Создать: `res/drawable/ic_car_location.xml`
- Изменить: `src/ru/hudspeed/pro/GpsCarSession.java`
- Изменить: `tests/ParserGeoTest.java`
- Изменить: `tests/AndroidSourceContractTest.ps1`
- Изменить: `test.ps1`
- Создать: `src/test/java/ru/gpsantiradar/app/CarTemplateTest.java`

**Интерфейсы:**

~~~java
public final class CarSurfaceSpec {
    public static CarSurfaceSpec from(int width, int height, int dpi);
    public boolean isUsable();
    // value equality for width, height and dpi.
}

public final class CarSurfaceController implements SurfaceCallback {
    public void onDrivingSnapshot(DrivingSnapshot snapshot);
    public void zoomBy(float delta);
    public void recenter();
    public void setPanMode(boolean enabled);
    public void destroy();
}
~~~

- [ ] **Шаг 1: написать падающие тесты**

`CarSurfaceSpec` отклоняет нулевой размер, имеет value equality и различает изменение DPI/размера. Контракт разбирает manifest и требует разрешения `ACCESS_SURFACE`/`NAVIGATION_TEMPLATES`, Car API 2, metadata дескриптора, экспортированный `GpsCarAppService` с action/category, а также `AppManager.setSurfaceCallback`, `createVirtualDisplay`, `Presentation`, освобождение старого display до замены и `SharedCameraMapLayer` внутри presentation.

- [ ] **Шаг 2: проверить RED**

Запустить `.\test.ps1`: manifest, сервис, session и классы поверхности отсутствуют.

- [ ] **Шаг 3: добавить manifest, сервис и session**

В manifest добавить `androidx.car.app.ACCESS_SURFACE`, `androidx.car.app.NAVIGATION_TEMPLATES`, metadata `androidx.car.app.minCarApiLevel=2`, metadata `com.google.android.gms.car.application=@xml/automotive_app_desc` и экспортированный `.GpsCarAppService` с action `androidx.car.app.CarAppService` и category `androidx.car.app.category.NAVIGATION`.

`GpsCarAppService.createHostValidator()` возвращает `HostValidator.ALLOW_ALL_HOSTS_VALIDATOR`, а `onCreateSession()` — новый `GpsCarSession`. Session проверяет `ACCESS_FINE_LOCATION` и ключ MapKit; при отсутствии возвращает `CarSetupScreen` с `MessageTemplate`, при готовности запускает `TrackingService.ACTION_START` через `startForegroundService` и возвращает `CarMapScreen`. Не запускать RadarBase и не создавать второй tracker.

- [ ] **Шаг 4: реализовать CarMapScreen**

`onGetTemplate()` всегда задаёт `ActionStrip` с меню и `MapActionStrip` с `Action.PAN`, увеличением, уменьшением и возвратом к позиции:

~~~java
return new NavigationTemplate.Builder()
        .setActionStrip(applicationActions)
        .setMapActionStrip(mapActions)
        .setPanModeListener(surfaceController::setPanMode)
        .build();
~~~

Не задавать `NavigationInfo`, `TravelEstimate` или маршрут. Иконки создавать через `CarIcon`/`IconCompat` из монохромных vector drawable.

- [ ] **Шаг 5: реализовать жизненный цикл Surface**

Зарегистрировать controller через `AppManager.setSurfaceCallback(this)`. `onSurfaceAvailable` сначала идемпотентно освобождает старую поверхность, затем создаёт:

~~~java
VirtualDisplay display = displayManager.createVirtualDisplay(
        "gps-antiradar-car-map", width, height, dpi, surface, 0);
Presentation presentation = new Presentation(carContext, display.getDisplay());
CarMapPresentation content = new CarMapPresentation(
        presentation.getContext(), application, mapHost);
presentation.setContentView(content.rootView());
presentation.show();
~~~

`releaseSurface()` уничтожает `SharedCameraMapLayer`, останавливает `mapView`, освобождает процессный MapKit owner, закрывает `Presentation` и освобождает `VirtualDisplay`. `destroy()` снимает SurfaceCallback и вызывает release. Повторный вызов ничего не делает.

- [ ] **Шаг 6: построить автомобильное представление**

`CarMapPresentation` создаёт полноэкранный `FrameLayout` с Yandex `MapView` и HUD. HUD показывает скорость, объект, расстояние, ограничение и состояние, использует `DrivingHudPresentation` и общую прозрачность. Алгоритмический текст не помещать в скоростную плашку.

При старте создать отдельный `SharedCameraMapLayer` и передавать ему координаты каждого snapshot. `onStableAreaChanged(Rect)` задаёт безопасные поля HUD, `onVisibleAreaChanged(Rect)` корректирует временно видимую область без пересоздания карты. `onCarConfigurationChanged` применяет `CarContext.isDarkMode()` к фону HUD, не перекрашивая значки камер.

- [ ] **Шаг 7: связать жесты**

`CarMapGestureController` преобразует scale в delta zoom через `log(scaleFactor)/log(2)`, scroll в новую центральную точку через `MapWindow.screenToWorld`, fling в короткое `Animation.Type.SMOOTH`, click в координату карты. Кнопки zoom/recenter вызывают те же методы слоя. `Action.PAN` обязателен для событий ввода хоста.

- [ ] **Шаг 8: подключить broadcast**

`GpsCarSession` регистрирует receiver `TrackingService.ACTION_UPDATE`, вызывает `DrivingSnapshotIntent.from` и передаёт snapshot controller. При уничтожении снимает receiver и controller, но не останавливает `TrackingService`.

- [ ] **Шаг 9: добавить Car App Library тест**

С `RobolectricTestRunner` и `TestCarContext.createCarContext(ApplicationProvider.getApplicationContext())` проверить `MessageTemplate` настройки, тип `NavigationTemplate`, непустой application action strip, четыре map actions, `Action.PAN` и регистрацию callback в `TestAppManager.getSurfaceCallback()`. Симулировать повторные surface callbacks на controller с заменяемым display factory и проверить порядок `release -> create` без реального `Surface`.

- [ ] **Шаг 10: проверить и создать коммит**

~~~powershell
.\test.ps1
.\gradlew.bat --no-daemon testReleaseUnitTest
.\gradlew.bat --no-daemon compileReleaseJavaWithJavac
.\gradlew.bat --no-daemon lintRelease
git diff --check
git add AndroidManifest.xml `
        src/ru/hudspeed/pro/GpsCarAppService.java `
        src/ru/hudspeed/pro/GpsCarSession.java `
        src/ru/hudspeed/pro/CarSetupScreen.java `
        src/ru/hudspeed/pro/CarSurfaceSpec.java `
        src/ru/hudspeed/pro/CarSurfaceController.java `
        src/ru/hudspeed/pro/CarMapPresentation.java `
        src/ru/hudspeed/pro/CarMapScreen.java `
        src/ru/hudspeed/pro/CarMapGestureController.java `
        src/ru/hudspeed/pro/GpsCarSession.java `
        res/drawable/ic_car_zoom_in.xml res/drawable/ic_car_zoom_out.xml `
        res/drawable/ic_car_pan.xml res/drawable/ic_car_location.xml `
        tests/ParserGeoTest.java tests/AndroidSourceContractTest.ps1 test.ps1 `
        src/test/java/ru/gpsantiradar/app/CarTemplateTest.java
git commit -m "feat: render driving screen in Android Auto"
~~~

---

### Задача 7: полное меню Android Auto

**Файлы:**
- Создать: `src/ru/hudspeed/pro/CarMenuItem.java`
- Создать: `src/ru/hudspeed/pro/CarMenuScreen.java`
- Создать: `src/ru/hudspeed/pro/CarValueScreen.java`
- Создать: `src/ru/hudspeed/pro/CarMapKeyScreen.java`
- Создать: `src/ru/hudspeed/pro/CarAboutScreen.java`
- Создать: `res/drawable/ic_car_menu.xml`
- Изменить: `src/ru/hudspeed/pro/CarMapScreen.java`
- Изменить: `src/ru/hudspeed/pro/CarMapPresentation.java`
- Изменить: `tests/ParserGeoTest.java`
- Изменить: `tests/AndroidSourceContractTest.ps1`
- Изменить: `test.ps1`
- Изменить: `src/test/java/ru/gpsantiradar/app/CarTemplateTest.java`

**Интерфейсы:**

~~~java
public enum CarMenuItem {
    UPDATE_DATABASE, ALERT_DISTANCE, OVERSPEED_THRESHOLD,
    HUD_TRANSPARENCY, MAPKIT_KEY, ABOUT, EXIT
}

public final class CarValueScreen extends Screen {
    public enum Setting { ALERT_DISTANCE, OVERSPEED_THRESHOLD, HUD_TRANSPARENCY }
}
~~~

- [ ] **Шаг 1: написать падающие тесты**

Проверить порядок семи `CarMenuItem` и соответствие трёх `Setting` ключам, диапазонам, шагам и русскому форматированию. Контракт требует все пункты, `CarToast` для updater и `finishCarApp()` только в «Выход».

- [ ] **Шаг 2: проверить RED**

`.\test.ps1` должен завершиться ошибкой на отсутствующих моделях.

- [ ] **Шаг 3: реализовать CarMenuScreen**

`ListTemplate` с `Action.BACK` и одним `ItemList`:

1. «Обновить базу»
2. «Расстояние оповещения»
3. «Предел превышения для beep»
4. «Прозрачность HUD»
5. «Ключ MapKit»
6. «О программе»
7. «Выход»

Обновление вызывает общий updater. Listener показывает `CarToast` для `STARTED`, `UNCHANGED`, `SUCCESS`, `ERROR` и `ALREADY_RUNNING` и снимается по lifecycle.

- [ ] **Шаг 4: реализовать числовые настройки**

`CarValueScreen` возвращает `PaneTemplate` с текущим значением и действиями `ic_remove`/`ic_add`. Нажатие вызывает `AppSettings.adjust*`, синхронно сохраняет значение и делает `invalidate()`. Прозрачность дополнительно вызывает `CarMapPresentation.refreshHudTransparency()`.

- [ ] **Шаг 5: реализовать ключ MapKit**

`CarMapKeyScreen` использует `SearchTemplate.Builder(SearchCallback)` с `Action.BACK` и подсказкой «Ключ Yandex MapKit». `onSearchSubmitted` обрезает пробелы, запрещает пустое значение, сохраняет `AppSettings.MAPKIT_KEY` и показывает просьбу перезапустить приложение. Ограничение клавиатуры во время движения не обходить; предложить выполнить ввод на телефоне.

- [ ] **Шаг 6: реализовать «О программе»**

`CarAboutScreen` использует `LongMessageTemplate`. Текст содержит `BuildConfig.VERSION_NAME`, `CameraDatabase.count()` из фонового потока, время `RADARBASE_LAST_SUCCESSFUL_DOWNLOAD`, описание текущей версии и все `ReleaseHistory.entries()`. Пока count не готов, показать «Объектов в базе: загрузка…», затем `invalidate()`. Не добавлять неизвестные версии.

- [ ] **Шаг 7: реализовать выход**

~~~java
getCarContext().stopService(new Intent(getCarContext(), TrackingService.class));
getScreenManager().popToRoot();
getCarContext().finishCarApp();
~~~

Отключение Android Auto, back и уничтожение поверхности не останавливают сервис.

- [ ] **Шаг 8: расширить тест меню**

Через `ScreenController`/`TestAppManager` проверить `ListTemplate` с семью строками, переходы к трём `CarValueScreen`, изменение `SharedPreferences` на граничных значениях и сообщения updater в `TestAppManager.getToastsShown()`. Отдельно проверить, что «Выход» вызывает внедрённый `ExitAction`; реальный adapter выполняет `stopService`, `popToRoot` и `finishCarApp`.

- [ ] **Шаг 9: проверить и создать коммит**

~~~powershell
.\test.ps1
.\gradlew.bat --no-daemon testReleaseUnitTest
.\gradlew.bat --no-daemon compileReleaseJavaWithJavac
.\gradlew.bat --no-daemon lintRelease
git diff --check
git add src/ru/hudspeed/pro/CarMenuItem.java `
        src/ru/hudspeed/pro/CarMenuScreen.java `
        src/ru/hudspeed/pro/CarValueScreen.java `
        src/ru/hudspeed/pro/CarMapKeyScreen.java `
        src/ru/hudspeed/pro/CarAboutScreen.java `
        src/ru/hudspeed/pro/CarMapScreen.java `
        src/ru/hudspeed/pro/CarMapPresentation.java `
        res/drawable/ic_car_menu.xml `
        tests/ParserGeoTest.java tests/AndroidSourceContractTest.ps1 test.ps1 `
        src/test/java/ru/gpsantiradar/app/CarTemplateTest.java
git commit -m "feat: add Android Auto settings menu"
~~~

---

### Задача 8: версия 4.9.5 и автоматическая проверка

**Файлы:**
- Изменить: `src/ru/hudspeed/pro/ReleaseHistory.java`
- Изменить: `tests/ParserGeoTest.java`
- Изменить: `tests/AndroidSourceContractTest.ps1`
- Изменить: `build.gradle`
- Изменить: `README.md`

- [ ] **Шаг 1: написать падающий тест релиза**

~~~java
ReleaseHistory.Entry current = ReleaseHistory.find("4.9.5");
check(current != null && !current.changes.trim().isEmpty(),
        "release 4.9.5 has known changes");
check(ReleaseHistory.entries().size() == 9,
        "only known releases are listed");
~~~

Контракт требует `versionCode = 40`, `versionName = '4.9.5'` и упоминание Android Auto и иммерсивного режима.

- [ ] **Шаг 2: проверить RED**

`.\test.ps1` должен обнаружить 4.9.4/39 и отсутствующую запись.

- [ ] **Шаг 3: добавить только известную версию**

~~~java
new Entry("4.9.5", "Добавлен экран движения Android Auto с картой, объектами, "
        + "HUD и полным меню. На телефоне скрываются системные панели, а обновление "
        + "RadarBase запускается при холодном старте процесса."),
~~~

Установить 4.9.5/40. `README.md` дополнить запуском Android Auto, общей службой оповещений и настройкой DHU.

- [ ] **Шаг 4: выполнить полную проверку**

~~~powershell
.\test.ps1
.\gradlew.bat --no-daemon testReleaseUnitTest
.\gradlew.bat --no-daemon compileReleaseJavaWithJavac
.\gradlew.bat --no-daemon lintVitalRelease
git diff --check
.\build.ps1
$apksigner = "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.0.0\apksigner.bat"
& $apksigner verify --verbose --print-certs outputs\GPS-AntiRadar.apk
~~~

Ожидаются оба `OK`, `BUILD SUCCESSFUL` и успешная проверка подписи.

- [ ] **Шаг 5: проверить APK и создать коммит**

~~~powershell
$aapt = "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.0.0\aapt.exe"
& $aapt dump badging outputs\GPS-AntiRadar.apk |
        Select-String "package:|application-label|launchable-activity"
Get-FileHash outputs\GPS-AntiRadar.apk -Algorithm SHA256
(Get-Item outputs\GPS-AntiRadar.apk).Length
git add src/ru/hudspeed/pro/ReleaseHistory.java `
        tests/ParserGeoTest.java tests/AndroidSourceContractTest.ps1 `
        build.gradle README.md
git commit -m "release: prepare version 4.9.5"
~~~

`aapt` должен сообщить `ru.gpsantiradar.app`, `versionCode='40'` и `versionName='4.9.5'`. APK не добавлять в git.

---

### Задача 9: установка на телефон и проверка DHU

**Файлы:**
- Артефакт: `outputs/GPS-AntiRadar.apk`

- [ ] **Шаг 1: проверить устройство**

~~~powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb devices -l
& $adb shell getprop ro.build.version.release
& $adb shell pm path ru.gpsantiradar.app
~~~

Продолжать при одном устройстве в состоянии `device`. Не удалять приложение.

- [ ] **Шаг 2: установить релиз поверх текущего**

~~~powershell
& $adb install -r outputs\GPS-AntiRadar.apk
& $adb shell dumpsys package ru.gpsantiradar.app |
        Select-String "versionCode=|versionName="
~~~

Ожидается `Success` и 4.9.5/40.

- [ ] **Шаг 3: проверить телефон**

~~~powershell
& $adb logcat -c
& $adb shell am force-stop ru.gpsantiradar.app
& $adb shell monkey -p ru.gpsantiradar.app -c android.intent.category.LAUNCHER 1
~~~

Проверить скрытие панелей после старта, диалога и Recent Apps; временный показ свайпом; одно холодное обновление базы с результатом; карту/HUD/стабильные иконки; остановку `TrackingService` после удаления карточки из Recent Apps.

- [ ] **Шаг 4: установить DHU**

`desktop-head-unit.exe` сейчас отсутствует. В Android Studio открыть **Tools > SDK Manager > SDK Tools**, установить **Android Auto Desktop Head Unit Emulator**, затем:

~~~powershell
$dhu = "$env:LOCALAPPDATA\Android\Sdk\extras\google\auto\desktop-head-unit.exe"
Test-Path $dhu
~~~

Ожидается `True`.

- [ ] **Шаг 5: подключить DHU**

На телефоне в настройках разработчика Android Auto включить неизвестные источники и сервер головного устройства:

~~~powershell
& $adb forward tcp:5277 tcp:5277
& $adb forward --list
& $dhu
~~~

GPS AntiRadar должен появиться в списке навигационных приложений.

- [ ] **Шаг 6: пройти автомобильный сценарий**

1. Запуск Android Auto первым экраном процесса выполняет одно обновление и использует один `TrackingService`.
2. Карта не пустая; видны тайлы, позиция, одиночные и групповые иконки.
3. HUD обновляется без повторного голоса при открытом телефоне.
4. Pan, pinch/zoom, `+`/`−` и возврат к позиции работают без пересоздания неизменившихся маркеров.
5. Работают семь пунктов меню, диапазоны настроек, `CarToast` и история только известных версий.
6. Ключ MapKit сохраняется; ограничение ввода во время движения не обходится.
7. Отключение DHU не останавливает tracking, а «Выход» останавливает сервис и завершает session.

- [ ] **Шаг 7: проверить замену поверхности и logcat**

Переподключить DHU и изменить размер окна. Карта должна восстановиться без старого `VirtualDisplay`.

~~~powershell
$pid = (& $adb shell pidof ru.gpsantiradar.app).Trim()
& $adb logcat -d --pid=$pid |
        Select-String "FATAL EXCEPTION|AndroidRuntime|ANR|VirtualDisplay|Surface"
~~~

В журнале не должно быть fatal exception/ANR. Сообщить установленную версию, абсолютный путь, размер и SHA-256 APK, результаты телефона и DHU.

## Контроль покрытия

- Задачи 1 и 6: единый снимок, одинаковый HUD и отсутствие второго звукового алгоритма.
- Задача 2: холодное процессное и ручное обновление одной реализацией.
- Задача 3: иммерсивный телефон и одновременный жизненный цикл MapKit.
- Задача 4: 20-процентное окно, поколения запросов и стабильные маркеры.
- Задачи 5 и 6: обнаружение Android Auto, `NavigationTemplate`, поверхность и проверки `app-testing`.
- Задача 7: все утверждённые пункты меню и общие настройки.
- Задачи 8 и 9: 4.9.5/40, сборка, подпись, установка, телефон и DHU.

## Справочные материалы

- [Настройка Car App Library](https://developer.android.com/training/cars/apps/library/set-up-project)
- [Подключение к Android Auto](https://developer.android.com/training/cars/apps/auto)
- [Карта в Surface через VirtualDisplay](https://developer.android.com/training/cars/apps/library/draw-maps)
- [Взаимодействие с картой](https://developer.android.com/training/cars/apps/library/interact-map)
- [CarContext.finishCarApp()](https://developer.android.com/reference/androidx/car/app/CarContext#finishCarApp())
