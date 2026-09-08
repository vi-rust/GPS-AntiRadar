package ru.gpsantiradar.app

import android.content.Context
import android.content.SharedPreferences
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat

class CarValueScreen internal constructor(
    carContext: CarContext,
    private val setting: Setting,
    hudTransparencyRefresh: Runnable?,
) : Screen(carContext) {
    enum class Setting(
        private val preferenceKey: String,
        private val title: String,
        private val defaultValue: Int,
        private val minValue: Int,
        private val maxValue: Int,
        private val step: Int,
    ) {
        OVERSPEED_THRESHOLD(
            AppSettings.OVERSPEED_THRESHOLD,
            "Предел превышения скорости",
            AppSettings.DEFAULT_OVERSPEED_THRESHOLD_KMH,
            AppSettings.MIN_OVERSPEED_THRESHOLD_KMH,
            AppSettings.MAX_OVERSPEED_THRESHOLD_KMH,
            AppSettings.OVERSPEED_THRESHOLD_STEP_KMH,
        ),
        HUD_TRANSPARENCY(
            AppSettings.HUD_TRANSPARENCY,
            "Прозрачность HUD",
            AppSettings.DEFAULT_HUD_TRANSPARENCY_PERCENT,
            AppSettings.MIN_HUD_TRANSPARENCY_PERCENT,
            AppSettings.MAX_HUD_TRANSPARENCY_PERCENT,
            AppSettings.HUD_TRANSPARENCY_STEP_PERCENT,
        );

        fun preferenceKey(): String = preferenceKey
        fun title(): String = title
        fun defaultValue(): Int = defaultValue
        fun minValue(): Int = minValue
        fun maxValue(): Int = maxValue
        fun step(): Int = step

        fun normalize(value: Int): Int = when (this) {
            OVERSPEED_THRESHOLD -> AppSettings.clampOverspeedThreshold(value)
            HUD_TRANSPARENCY -> AppSettings.clampHudTransparency(value)
        }

        fun adjust(value: Int, direction: Int): Int = when (this) {
            OVERSPEED_THRESHOLD -> AppSettings.adjustOverspeedThreshold(value, direction)
            HUD_TRANSPARENCY -> AppSettings.adjustHudTransparency(value, direction)
        }

        fun format(value: Int): String = when (this) {
            OVERSPEED_THRESHOLD -> "${normalize(value)} км/ч"
            HUD_TRANSPARENCY -> "${normalize(value)}%"
        }
    }

    private val hudTransparencyRefresh = hudTransparencyRefresh ?: NO_OP
    private val preferences: SharedPreferences = carContext.getSharedPreferences(
        AppSettings.PREFERENCES,
        Context.MODE_PRIVATE,
    )

    constructor(
        carContext: CarContext,
        setting: Setting,
        surfaceController: CarSurfaceController?,
    ) : this(
        carContext,
        setting,
        if (surfaceController == null) NO_OP else Runnable { surfaceController.refreshHudTransparency() },
    )

    override fun onGetTemplate(): Template {
        val pane = Pane.Builder()
            .addRow(
                Row.Builder()
                    .setTitle(setting.format(currentValue()))
                    .addText(
                        "Диапазон: ${setting.format(setting.minValue())}" +
                            " – ${setting.format(setting.maxValue())}",
                    )
                    .build(),
            )
            .addAction(valueAction(R.drawable.ic_remove, -1))
            .addAction(valueAction(R.drawable.ic_add, 1))
            .build()
        return PaneTemplate.Builder(pane)
            .setTitle(setting.title())
            .setHeaderAction(Action.BACK)
            .build()
    }

    private fun currentValue(): Int = setting.normalize(
        preferences.getInt(setting.preferenceKey(), setting.defaultValue()),
    )

    private fun valueAction(iconResource: Int, direction: Int): Action {
        val icon = CarIcon.Builder(
            IconCompat.createWithResource(carContext, iconResource),
        ).build()
        return Action.Builder()
            .setIcon(icon)
            .setOnClickListener { changeValue(direction) }
            .build()
    }

    private fun changeValue(direction: Int) {
        val adjusted = setting.adjust(currentValue(), direction)
        preferences.edit().putInt(setting.preferenceKey(), adjusted).commit()
        if (setting == Setting.HUD_TRANSPARENCY) hudTransparencyRefresh.run()
        invalidate()
    }

    companion object {
        private val NO_OP = Runnable {}
    }
}
