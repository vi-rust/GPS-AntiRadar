package ru.gpsantiradar.app;

public enum ThemeMode {
    LIGHT("Светлая"),
    DARK("Тёмная"),
    AUTOMATIC("Автоматически");

    private final String title;

    ThemeMode(String title) {
        this.title = title;
    }

    public String title() {
        return title;
    }

    public static ThemeMode fromStored(String value) {
        if (value != null) {
            for (ThemeMode mode : values()) {
                if (mode.name().equalsIgnoreCase(value)) return mode;
            }
        }
        return AUTOMATIC;
    }
}
