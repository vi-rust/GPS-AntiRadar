package ru.gpsantiradar.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.LongMessageTemplate
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.concurrent.Executor

class CarAboutScreen internal constructor(
    carContext: CarContext,
    private val countLoader: CountLoader,
    private val backgroundExecutor: Executor,
    private val mainExecutor: Executor,
) : Screen(carContext) {
    fun interface CountLoader {
        fun load(): Int
    }

    private var databaseCount = COUNT_LOADING
    private var loadStarted = false

    @Volatile
    private var destroyed = false

    constructor(carContext: CarContext) : this(
        carContext,
        CountLoader { CameraDatabase(carContext).use { it.count() } },
        Executor { command -> Thread(command, "car-about-count").start() },
        object : Executor {
            private val handler = Handler(Looper.getMainLooper())

            override fun execute(command: Runnable) {
                handler.post(command)
            }
        },
    )

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                loadCount()
            }

            override fun onDestroy(owner: LifecycleOwner) {
                destroyed = true
            }
        })
    }

    override fun onGetTemplate(): Template = LongMessageTemplate.Builder(aboutText())
        .setTitle("О программе")
        .setHeaderAction(Action.BACK)
        .build()

    private fun loadCount() {
        if (loadStarted || destroyed) return
        loadStarted = true
        backgroundExecutor.execute {
            val loaded = try {
                countLoader.load()
            } catch (_: RuntimeException) {
                COUNT_UNAVAILABLE
            }
            mainExecutor.execute {
                if (destroyed) return@execute
                databaseCount = loaded
                invalidate()
            }
        }
    }

    private fun aboutText(): String = buildString {
        append("GPS AntiRadar\n")
        append("Версия ").append(BuildConfig.VERSION_NAME)
        append("\n\n")
        append(databaseCountText())
        append("\nПоследняя успешная загрузка: ")
        append(lastSuccessfulDownloadText())

        ReleaseHistory.find(BuildConfig.VERSION_NAME)?.let { current ->
            append("\n\nИзменения текущей версии\n")
            append(current.changes)
        }

        append("\n\nИстория релизов")
        for (entry in ReleaseHistory.entries()) {
            append("\n\nВерсия ").append(entry.version)
            append("\n").append(entry.changes)
        }
    }

    private fun databaseCountText(): String = when (databaseCount) {
        COUNT_LOADING -> "Объектов в базе: загрузка…"
        COUNT_UNAVAILABLE -> "Объектов в базе: недоступно"
        else -> "Объектов в базе: ${NumberFormat.getIntegerInstance().format(databaseCount)}"
    }

    private fun lastSuccessfulDownloadText(): String {
        val timestamp = carContext.getSharedPreferences(
            AppSettings.PREFERENCES,
            Context.MODE_PRIVATE,
        ).getLong(AppSettings.RADARBASE_LAST_SUCCESSFUL_DOWNLOAD, 0L)
        if (timestamp <= 0L) return "не выполнялась"
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(timestamp))
    }

    companion object {
        private const val COUNT_LOADING = -1
        private const val COUNT_UNAVAILABLE = -2
    }
}
