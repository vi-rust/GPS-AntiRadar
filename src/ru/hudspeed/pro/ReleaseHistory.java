package ru.gpsantiradar.app;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class ReleaseHistory {
    private static final List<Entry> RELEASES = Collections.unmodifiableList(Arrays.asList(
            new Entry("4.9.1", "Убрана техническая информация об алгоритме из плашки "
                    + "скорости. Добавлен раздел «О программе» с историей релизов."),
            new Entry("4.9.0", "Перенесён механизм зон Strelka: подтверждение подхода, "
                    + "движение внутри зоны, завершение после проезда и повторное "
                    + "оповещение только после нового входа."),
            new Entry("4.8.1", "Добавлен явный выход из приложения. Смахивание карточки "
                    + "в Recent Apps останавливает GPS-сервис, звук и уведомление."),
            new Entry("4.8.0", "Добавлены оригинальные иконки объектов и 97 голосовых "
                    + "фрагментов Strelka, голосовые предупреждения и состояние алгоритма."),
            new Entry("4.7.1", "Базовая версия с картой Yandex MapKit, загрузкой базы "
                    + "RadarBase, отображением объектов и GPS-скорости.")
    ));

    private ReleaseHistory() {}

    public static List<Entry> entries() {
        return RELEASES;
    }

    public static final class Entry {
        public final String version;
        public final String changes;

        Entry(String version, String changes) {
            this.version = version;
            this.changes = changes;
        }
    }
}
