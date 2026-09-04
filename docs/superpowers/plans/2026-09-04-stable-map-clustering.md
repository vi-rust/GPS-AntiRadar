# План реализации стабильной кластеризации карты

> **Для агентов-разработчиков:** ОБЯЗАТЕЛЬНЫЙ ДОПОЛНИТЕЛЬНЫЙ НАВЫК: для пошагового выполнения этого плана используйте superpowers:subagent-driven-development (рекомендуется) или superpowers:executing-plans. Для отслеживания в шагах используется синтаксис флажков (- [ ]).

**Цель:** сохранить существующие одиночные и групповые иконки карты, изменяя только сущности, затронутые изменением окна просмотра и масштаба.

**Архитектура:** заменить кластеризацию всей коллекции MapKit детерминированной компоновкой Web Mercator и diff сущностей. `MainActivity` применяет diff к одной постоянной обычной `MapObjectCollection`; полигоны областей действия и существующий 20-процентный буфер SQLite-запроса остаются независимыми.

**Технологии:** Java 8, Android SDK 36, Yandex MapKit 4.42 Lite, среда тестирования PowerShell/JVM.

**Спецификация:** docs/superpowers/specs/2026-09-04-stable-map-clustering-design.md

## Общие ограничения

- Сохранить существующие границы SQLite-запроса с буфером 20%.
- Использовать отдельные маркеры при масштабе 14 и выше.
- При масштабе ниже 14 использовать привязанную к мировым координатам сетку Web Mercator с ячейкой 52 пикселя.
- Никогда не изменять неизменившиеся сущности MapKit.
- Изменившуюся группу можно обновить; несвязанные сущности должны остаться нетронутыми.
- Сохранить независимость полигонов областей действия и GPS-маркера.
- Пропускать некорректные координаты.
- Версия 4.9.3 показывает собственное описание в окне «О программе».
- Сохранить все существующие незакоммиченные изменения пользователя.

---

### Задача 1: детерминированная компоновка маркеров

**Файлы:**
- Создать: src/ru/hudspeed/pro/MapMarkerLayout.java
- Изменить: test.ps1
- Изменить: tests/ParserGeoTest.java

**Интерфейсы:**
- Принимает записи `CameraPoint` и масштаб `float`.
- Возвращает результат `MapMarkerLayout.create(List<CameraPoint>, float)`.
- Возвращает неизменяемые поля `Entity`: `key`, `cluster`, `latitude`, `longitude`, `memberIds`, `camera`.
- Предоставляет `Entity.samePresentation(Entity)`.

- [ ] **Шаг 1: написать падающие тесты**

Добавить `MapMarkerLayout.java` в `test.ps1` и следующий код:

~~~java
private static void verifyStableMapMarkerLayout() {
    List<CameraPoint> points = java.util.Arrays.asList(
            marker(401, 55.75000, 37.61000, 1),
            marker(402, 55.75005, 37.61005, 2),
            marker(403, 56.83000, 60.60000, 1),
            marker(404, 56.83005, 60.60005, 2));
    List<MapMarkerLayout.Entity> first = MapMarkerLayout.create(points, 10.4f);
    List<MapMarkerLayout.Entity> panned =
            MapMarkerLayout.create(new ArrayList<>(points), 10.4f);
    check(first.size() == 2, "nearby cameras form two stable clusters");
    check(first.get(0).key.equals(panned.get(0).key)
                    && first.get(1).key.equals(panned.get(1).key),
            "same zoom preserves world-anchored cluster keys");

    List<MapMarkerLayout.Entity> individual = MapMarkerLayout.create(
            Collections.singletonList(points.get(0)), 14f);
    check(individual.size() == 1 && !individual.get(0).cluster
                    && individual.get(0).key.equals("camera:401"),
            "zoom 14 displays individual cameras");

    CameraPoint invalid = marker(405, Double.NaN, 37.0, 1);
    check(MapMarkerLayout.create(Collections.singletonList(invalid), 10f).isEmpty(),
            "invalid coordinates are skipped");
}
~~~

Этот тест выявляет нестабильные ключи, неверный порог масштаба и неправильную обработку координат.

- [ ] **Шаг 2: проверить RED**

Запустить `.\test.ps1`. Ожидаемый результат: компиляция завершается ошибкой, потому что `MapMarkerLayout` отсутствует.

- [ ] **Шаг 3: реализовать MapMarkerLayout**

Использовать точную модель координат:

~~~java
static final float INDIVIDUAL_ZOOM = 14f;
private static final double TILE_SIZE = 256.0;
private static final double CELL_PIXELS = 52.0;
double x = (longitude + 180.0) / 360.0;
double clamped = Math.max(-85.05112878, Math.min(85.05112878, latitude));
double radians = Math.toRadians(clamped);
double y = (1.0 - Math.log(Math.tan(radians)
        + 1.0 / Math.cos(radians)) / Math.PI) / 2.0;
double worldSize = TILE_SIZE * Math.pow(2.0, Math.floor(zoom));
long cellX = (long) Math.floor(x * worldSize / CELL_PIXELS);
long cellY = (long) Math.floor(y * worldSize / CELL_PIXELS);
~~~

Отклонять неконечные или выходящие за допустимый диапазон координаты. При масштабе 14 и выше возвращать `camera:<id>`. При масштабе ниже 14 группировать в `TreeMap` по ключу `cluster:<floorZoom>:<cellX>:<cellY>`. Ячейка с одним участником остаётся одиночным объектом. Сортировать идентификаторы участников и использовать географический центроид. Сравнение одиночных объектов включает каждое поле `CameraPoint`, используемое при отображении на карте.

- [ ] **Шаг 4: проверить GREEN**

Запустить `.\test.ps1`. Ожидаемый результат: `ParserGeoTest: OK`.

- [ ] **Шаг 5: проверить изменения и создать коммит**

Проверить подготовленные тесты, поскольку они содержат ранее утверждённую работу.

~~~powershell
git add src/ru/hudspeed/pro/MapMarkerLayout.java test.ps1 tests/ParserGeoTest.java
git commit -m "feat: add stable map marker layout"
~~~

---

### Задача 2: diff сущностей

**Файлы:**
- Создать: src/ru/hudspeed/pro/MapMarkerEntityDiff.java
- Изменить: test.ps1
- Изменить: tests/ParserGeoTest.java

**Интерфейсы:**
- Принимает отрисованную `Map<String, MapMarkerLayout.Entity>` и требуемые сущности.
- Возвращает поля `Result`: `removeKeys`, `add`, `update`, `desired`.

- [ ] **Шаг 1: написать падающие тесты локального изменения**

Создать две группы из задачи 1, добавить камеру 405 рядом с камерой 401, затем выполнить:

~~~java
Map<String, MapMarkerLayout.Entity> rendered = entitiesByKey(
        MapMarkerLayout.create(initialPoints, 10.4f));
List<CameraPoint> changedPoints = new ArrayList<>(initialPoints);
changedPoints.add(marker(405, 55.75003, 37.61003, 3));
MapMarkerEntityDiff.Result diff = MapMarkerEntityDiff.between(rendered,
        MapMarkerLayout.create(changedPoints, 10.4f));
check(diff.removeKeys.isEmpty() && diff.add.isEmpty() && diff.update.size() == 1,
        "new member updates only its existing cluster");
check(diff.update.get(0).memberIds.contains(405L),
        "updated cluster contains the entering camera");
~~~

Проверить, что группа Екатеринбурга отсутствует в `update`. При масштабе 14 перемещение камеры 401 должно приводить к одному обновлению без добавлений и удалений.

- [ ] **Шаг 2: проверить RED**

Запустить `.\test.ps1`. Ожидаемый результат: компиляция завершается ошибкой, потому что `MapMarkerEntityDiff` отсутствует.

- [ ] **Шаг 3: реализовать MapMarkerEntityDiff**

~~~java
public static Result between(
        Map<String, MapMarkerLayout.Entity> rendered,
        List<MapMarkerLayout.Entity> requested) {
    Map<String, MapMarkerLayout.Entity> desired = new LinkedHashMap<>();
    for (MapMarkerLayout.Entity entity : requested) desired.put(entity.key, entity);
    // Missing key: removeKeys. New key: add.
    // Same key with changed presentation: update.
    return new Result(removeKeys, add, update, desired);
}
~~~

`update` остаётся отдельным от удаления с последующим добавлением, чтобы его `PlacemarkMapObject` сохранялся.

- [ ] **Шаг 4: проверить GREEN**

Запустить `.\test.ps1`. Ожидаемый результат: `ParserGeoTest: OK`.

- [ ] **Шаг 5: проверить изменения и создать коммит**

~~~powershell
git add src/ru/hudspeed/pro/MapMarkerEntityDiff.java test.ps1 tests/ParserGeoTest.java
git commit -m "feat: diff stable map entities"
~~~

---

### Задача 3: постоянная отрисовка MapKit

**Файлы:**
- Изменить: src/ru/hudspeed/pro/MainActivity.java

**Интерфейсы:**
- Использует `MapMarkerLayout.create(points, zoom)`.
- Использует `MapMarkerEntityDiff.between(rendered, desired)`.
- Хранит `renderedMarkerObjects` и `renderedMarkerEntities` с ключом типа `String`.

- [ ] **Шаг 1: подтвердить ошибочное поведение**

Подтвердить, что `renderCameraMarkers` вызывает `cameraMarkerCollection.clusterPlacemarks()` при каждом появлении или исчезновении камеры. Этот дефект приводит к глобальному перестроению групп.

- [ ] **Шаг 2: заменить состояние кластеризованной коллекции**

Удалить `Cluster`, `ClusterListener` и `ClusterizedPlacemarkCollection`. Добавить:

~~~java
private MapObjectCollection cameraMarkerCollection;
private final Map<String, PlacemarkMapObject> renderedMarkerObjects =
        new HashMap<>();
private final Map<String, MapMarkerLayout.Entity> renderedMarkerEntities =
        new HashMap<>();
private final Map<Integer, ImageProvider> clusterIcons = new HashMap<>();
~~~

Создать `cameraMarkerCollection` через `map.getMapObjects().addCollection()`. Никогда не очищать корневую коллекцию карты.

- [ ] **Шаг 3: применять только операции diff**

Сохранить `CameraMarkerDiff` только для полигонов областей действия. Вычислить:

~~~java
List<MapMarkerLayout.Entity> entities =
        MapMarkerLayout.create(points, map.getCameraPosition().getZoom());
MapMarkerEntityDiff.Result markerDiff =
        MapMarkerEntityDiff.between(renderedMarkerEntities, entities);
~~~

Удалять только `removeKeys`. Для `update` сохранять существующий `PlacemarkMapObject` и изменять только отличающиеся свойства. Добавлять только `add`. После успешного применения сохранить `markerDiff.desired`. Удалить все вызовы `clear()` для маркеров и все вызовы `clusterPlacemarks()`.

- [ ] **Шаг 4: добавить точные вспомогательные методы**

~~~java
private PlacemarkMapObject addMarkerEntity(MapMarkerLayout.Entity entity)
private void updateMarkerEntity(PlacemarkMapObject marker,
        MapMarkerLayout.Entity previous, MapMarkerLayout.Entity current)
private IconStyle individualMarkerStyle()
private IconStyle clusterMarkerStyle()
private ImageProvider clusterIcon(int count)
~~~

Группы используют кэшированный `createClusterIcon(count)`, не имеют поворота и обработчика нажатия, их `z-index` равен 20. Одиночные объекты повторно используют `iconForCamera()`, `shootingBearing()`, пользовательские данные и обработчик нажатия камеры. Для неизменившихся сущностей не выполняются вызовы MapKit.

- [ ] **Шаг 5: сохранить области действия**

Области действия остаются видимыми при масштабе не ниже `COVERAGE_MIN_ZOOM`. Изменение порога может очищать только `cameraCoverageCollection` и не может изменять маркеры или GPS-объект.

- [ ] **Шаг 6: проверить и выполнить аудит**

~~~powershell
.\test.ps1
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
.\gradlew.bat --no-daemon compileReleaseJavaWithJavac
~~~

Ожидаемый результат: `ParserGeoTest: OK` и `BUILD SUCCESSFUL`. Подтвердить отсутствие `clusterPlacemarks`, очистки коллекции маркеров и наличие изолированной очистки областей действия.

- [ ] **Шаг 7: проверить изменения и создать коммит**

`MainActivity` содержит ранее утверждённую работу; сохранить её без изменений.

~~~powershell
git add src/ru/hudspeed/pro/MainActivity.java
git commit -m "fix: preserve unchanged map markers"
~~~

---

### Задача 4: описание текущей версии

**Файлы:**
- Изменить: src/ru/hudspeed/pro/ReleaseHistory.java
- Изменить: src/ru/hudspeed/pro/MainActivity.java
- Изменить: tests/ParserGeoTest.java
- Изменить: build.gradle
- Изменить: README.md

**Интерфейсы:**
- Предоставляет `ReleaseHistory.find(String)`, возвращающий `Entry` или `null`.
- Использует `BuildConfig.VERSION_NAME` в `showAboutDialog()`.

- [ ] **Шаг 1: написать падающие тесты**

~~~java
ReleaseHistory.Entry current = ReleaseHistory.find("4.9.3");
check(current != null && !current.changes.trim().isEmpty(),
        "current release has a visible change description");
check(ReleaseHistory.find("missing") == null,
        "unknown release has no fabricated description");
~~~

Ожидаются версии 4.9.3, 4.9.2, 4.9.1, 4.9.0, 4.8.1, 4.8.0, 4.7.1.

- [ ] **Шаг 2: проверить RED**

Запустить `.\test.ps1`. Ожидаемый результат: отсутствует `find()` или запись 4.9.3.

- [ ] **Шаг 3: добавить записи и поиск**

~~~java
new Entry("4.9.3", "Устранено моргание значков: неизменившиеся одиночные "
        + "объекты и группы сохраняются при масштабировании и прокрутке карты."),
new Entry("4.9.2", "Добавлена автоматическая проверка RadarBase при холодном "
        + "запуске и дифференциальное обновление объектов карты с 20% буфером."),
~~~

Реализовать `find(String)` перебором неизменяемого списка.

- [ ] **Шаг 4: показать изменения текущей версии без дублирования**

Под текущей версией показать заголовок «Изменения текущей версии» и соответствующее описание. Пропустить эту версию в цикле старых выпусков.

- [ ] **Шаг 5: изменить версию, обновить документацию, проверить и создать коммит**

Установить `versionCode` в 38, а `versionName` — в 4.9.3. Обновить README описанием стабильной пользовательской кластеризации. Запустить `.\test.ps1` и ожидать `ParserGeoTest: OK`.

~~~powershell
git add src/ru/hudspeed/pro/ReleaseHistory.java src/ru/hudspeed/pro/MainActivity.java tests/ParserGeoTest.java build.gradle README.md
git commit -m "feat: document release 4.9.3"
~~~

---

### Задача 5: релиз и проверка на устройстве

**Файлы:**
- Создать: outputs/GPS-AntiRadar.apk

**Интерфейсы:**
- Создаёт `ru.gpsantiradar.app` версии 4.9.3.

- [ ] **Шаг 1: выполнить полную проверку**

~~~powershell
.\test.ps1
git diff --check
.\build.ps1
~~~

Ожидаемый результат: `ParserGeoTest: OK`, отсутствие ошибок пробелов и `BUILD SUCCESSFUL`.

- [ ] **Шаг 2: установить без удаления данных**

~~~powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb install -r "outputs\GPS-AntiRadar.apk"
~~~

Ожидаемый результат: `Success`.

- [ ] **Шаг 3: проверить отрисовку**

Холодным запуском открыть Activity. В месте, содержащем группы и одиночные объекты, несколько раз изменить масштаб и прокрутить карту, позволяя новым объектам из области с буфером появляться на экране. Записать экран во время действий и проверить кадры на исчезновение незатронутых иконок. Подтвердить, что Activity остаётся в фокусе, а в `AndroidRuntime` нет ошибок. JVM-тест diff сущностей предоставляет детерминированную проверку того, что неизменившиеся ключи не приводят к операциям MapKit.

- [ ] **Шаг 4: проверить окно «О программе» и пакет**

`UI Automator` содержит «Версия 4.9.3», «Изменения текущей версии», соответствующее описание и историческую запись «Версия 4.9.2». `dumpsys package` сообщает `versionCode=38` и `versionName=4.9.3`.

- [ ] **Шаг 5: зафиксировать артефакт**

Сообщить абсолютный путь к APK, размер в байтах и SHA-256.
