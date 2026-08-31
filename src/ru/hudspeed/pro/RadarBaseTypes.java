package ru.gpsantiradar.app;

/** RadarBase object categories and user-facing names. */
public final class RadarBaseTypes {
    private RadarBaseTypes() {}

    public static boolean isCameraOrControl(int type) {
        switch (type) {
            case 0: case 1: case 2: case 3: case 4: case 5: case 10: case 11: case 12:
            case 13: case 14: case 15: case 16: case 17: case 18:
            case 41: case 42: case 43: case 100: case 103: case 104:
            case 105: case 106: case 107: case 108: case 171: case 172:
                return true;
            default:
                return false;
        }
    }

    public static String name(int type) {
        switch (type) {
            case 0: return "Неизвестная опасность";
            case 1: return "Стационарная камера";
            case 2: return "Пост ДПС";
            case 3: return "Контроль стоп-линии";
            case 4: return "Парная камера";
            case 5: return "Мобильная засада";
            case 10: return "Комплекс контроля ПДД";
            case 11: return "Контроль полосы транспорта";
            case 12: return "Контроль обочины";
            case 13: return "Контроль разметки";
            case 14: return "Камера в тоннеле";
            case 15: return "Камера наблюдения";
            case 16: return "Муляж камеры";
            case 17: return "Контроль Платон";
            case 18: return "Контроль остановки";
            case 41: return "Начало контроля средней скорости";
            case 42: return "Конец контроля средней скорости";
            case 43: return "Контроль средней скорости";
            case 100: return "Неподтверждённая камера";
            case 103: return "Контроль проезда без остановки";
            case 104: return "Контроль скрытыми патрулями";
            case 105: return "Регулярная мобильная засада";
            case 106: return "Антивандальный бокс камеры";
            case 107: return "Контроль пешеходного перехода";
            case 108: return "Контроль дронами";
            case 171: return "Весовой контроль";
            case 172: return "Мобильный Платон";
            case 6: return "Место концентрации ДТП";
            case 7: return "Опасный пешеходный переход";
            case 61: return "Дорожные работы";
            case 62: return "Неровность";
            case 63: return "Авария";
            case 64: return "Лежачий полицейский";
            case 65: return "Группа лежачих полицейских";
            case 701: return "Внимание, дети!";
            case 702: return "Знак «Уступи дорогу»";
            case 703: return "Знак «СТОП»";
            case 704: return "Населённый пункт";
            case 705: return "Конец населённого пункта";
            case 706: return "Железнодорожный переезд";
            case 707: return "Опасный поворот направо";
            case 708: return "Ограничение высоты";
            case 709: return "Опасный поворот налево";
            case 710: return "Знак «Обгон запрещён»";
            case 711: return "Конец зоны запрещения обгона";
            case 712: return "АЗС";
            case 713: return "Электрозарядная станция";
            case 714: return "АЗС с зарядной станцией";
            default: return "Объект типа " + type;
        }
    }
}
