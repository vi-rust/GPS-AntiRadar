package ru.gpsantiradar.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.TimeZone;

public final class ThemeSettings {
    private ThemeSettings() {}

    public static ThemeMode mode(Context context) {
        return ThemeMode.fromStored(preferences(context).getString(
                AppSettings.THEME_MODE, ThemeMode.AUTOMATIC.name()));
    }

    public static void setMode(Context context, ThemeMode mode) {
        preferences(context).edit().putString(AppSettings.THEME_MODE,
                (mode == null ? ThemeMode.AUTOMATIC : mode).name()).apply();
    }

    public static boolean isDark(Context context) {
        return isDark(context, System.currentTimeMillis());
    }

    static boolean isDark(Context context, long epochMillis) {
        SharedPreferences preferences = preferences(context);
        double latitude = readCoordinate(preferences, AppSettings.THEME_LATITUDE);
        double longitude = readCoordinate(preferences, AppSettings.THEME_LONGITUDE);
        return ThemeResolver.isDark(mode(context), epochMillis,
                latitude, longitude, TimeZone.getDefault());
    }

    public static boolean isDark(Context context, long epochMillis,
                                 double latitude, double longitude) {
        return ThemeResolver.isDark(mode(context), epochMillis, latitude, longitude,
                TimeZone.getDefault());
    }

    public static void rememberLocation(Context context,
                                        double latitude, double longitude) {
        if (!valid(latitude, longitude)) return;
        SharedPreferences preferences = preferences(context);
        double savedLatitude = readCoordinate(preferences, AppSettings.THEME_LATITUDE);
        double savedLongitude = readCoordinate(preferences, AppSettings.THEME_LONGITUDE);
        if (valid(savedLatitude, savedLongitude)
                && Geo.distanceMeters(savedLatitude, savedLongitude,
                latitude, longitude) < 10_000.0) return;
        preferences.edit()
                .putLong(AppSettings.THEME_LATITUDE,
                        Double.doubleToRawLongBits(latitude))
                .putLong(AppSettings.THEME_LONGITUDE,
                        Double.doubleToRawLongBits(longitude))
                .apply();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(
                AppSettings.PREFERENCES, Context.MODE_PRIVATE);
    }

    private static double readCoordinate(SharedPreferences preferences, String key) {
        return Double.longBitsToDouble(preferences.getLong(
                key, Double.doubleToRawLongBits(Double.NaN)));
    }

    private static boolean valid(double latitude, double longitude) {
        return !Double.isNaN(latitude) && !Double.isInfinite(latitude)
                && !Double.isNaN(longitude) && !Double.isInfinite(longitude)
                && latitude >= -90.0 && latitude <= 90.0
                && longitude >= -180.0 && longitude <= 180.0;
    }
}
