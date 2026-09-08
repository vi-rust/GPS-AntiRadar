package ru.gpsantiradar.app

import android.content.Context
import android.content.SharedPreferences
import java.util.TimeZone

object ThemeSettings {
    fun mode(context: Context) = ThemeMode.fromStored(
        preferences(context).getString(AppSettings.THEME_MODE, ThemeMode.AUTOMATIC.name)
    )
    fun setMode(context: Context, mode: ThemeMode?) = preferences(context).edit()
        .putString(AppSettings.THEME_MODE, (mode ?: ThemeMode.AUTOMATIC).name).apply()
    fun isDark(context: Context) = isDark(context, System.currentTimeMillis())
    fun isDark(context: Context, epochMillis: Long): Boolean {
        val preferences = preferences(context)
        return ThemeResolver.isDark(
            mode(context), epochMillis,
            readCoordinate(preferences, AppSettings.THEME_LATITUDE),
            readCoordinate(preferences, AppSettings.THEME_LONGITUDE), TimeZone.getDefault()
        )
    }
    fun isDark(context: Context, epochMillis: Long, latitude: Double, longitude: Double) =
        ThemeResolver.isDark(mode(context), epochMillis, latitude, longitude, TimeZone.getDefault())
    fun rememberLocation(context: Context, latitude: Double, longitude: Double) {
        if (!valid(latitude, longitude)) return
        val preferences = preferences(context)
        val savedLatitude = readCoordinate(preferences, AppSettings.THEME_LATITUDE)
        val savedLongitude = readCoordinate(preferences, AppSettings.THEME_LONGITUDE)
        if (valid(savedLatitude, savedLongitude) &&
            Geo.distanceMeters(savedLatitude, savedLongitude, latitude, longitude) < 10_000.0) return
        preferences.edit().putLong(AppSettings.THEME_LATITUDE, latitude.toRawBits())
            .putLong(AppSettings.THEME_LONGITUDE, longitude.toRawBits()).apply()
    }
    private fun preferences(context: Context) = context.getSharedPreferences(AppSettings.PREFERENCES, Context.MODE_PRIVATE)
    private fun readCoordinate(preferences: SharedPreferences, key: String) =
        Double.fromBits(preferences.getLong(key, Double.NaN.toRawBits()))
    private fun valid(latitude: Double, longitude: Double) =
        latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0
}
