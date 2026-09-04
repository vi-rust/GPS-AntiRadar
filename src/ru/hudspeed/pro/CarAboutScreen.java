package ru.gpsantiradar.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.model.Action;
import androidx.car.app.model.LongMessageTemplate;
import androidx.car.app.model.Template;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import java.text.DateFormat;
import java.text.NumberFormat;
import java.util.Date;
import java.util.concurrent.Executor;

public final class CarAboutScreen extends Screen {
    interface CountLoader {
        int load();
    }

    private static final int COUNT_LOADING = -1;
    private static final int COUNT_UNAVAILABLE = -2;

    private final CountLoader countLoader;
    private final Executor backgroundExecutor;
    private final Executor mainExecutor;
    private int databaseCount = COUNT_LOADING;
    private boolean loadStarted;
    private volatile boolean destroyed;

    public CarAboutScreen(CarContext carContext) {
        this(carContext, new CountLoader() {
                    @Override public int load() {
                        try (CameraDatabase database =
                                     new CameraDatabase(carContext)) {
                            return database.count();
                        }
                    }
                },
                new Executor() {
                    @Override public void execute(Runnable command) {
                        new Thread(command, "car-about-count").start();
                    }
                },
                new Executor() {
                    private final Handler handler =
                            new Handler(Looper.getMainLooper());

                    @Override public void execute(Runnable command) {
                        handler.post(command);
                    }
                });
    }

    CarAboutScreen(CarContext carContext, CountLoader countLoader,
            Executor backgroundExecutor, Executor mainExecutor) {
        super(carContext);
        this.countLoader = countLoader;
        this.backgroundExecutor = backgroundExecutor;
        this.mainExecutor = mainExecutor;
        getLifecycle().addObserver(new DefaultLifecycleObserver() {
            @Override public void onStart(LifecycleOwner owner) {
                loadCount();
            }

            @Override public void onDestroy(LifecycleOwner owner) {
                destroyed = true;
            }
        });
    }

    @Override public Template onGetTemplate() {
        return new LongMessageTemplate.Builder(aboutText())
                .setTitle("О программе")
                .setHeaderAction(Action.BACK)
                .build();
    }

    private void loadCount() {
        if (loadStarted || destroyed) return;
        loadStarted = true;
        backgroundExecutor.execute(new Runnable() {
            @Override public void run() {
                int loaded;
                try {
                    loaded = countLoader.load();
                } catch (RuntimeException error) {
                    loaded = COUNT_UNAVAILABLE;
                }
                final int result = loaded;
                mainExecutor.execute(new Runnable() {
                    @Override public void run() {
                        if (destroyed) return;
                        databaseCount = result;
                        invalidate();
                    }
                });
            }
        });
    }

    private String aboutText() {
        StringBuilder text = new StringBuilder();
        text.append("GPS AntiRadar\n")
                .append("Версия ").append(BuildConfig.VERSION_NAME)
                .append("\n\n")
                .append(databaseCountText())
                .append("\nПоследняя успешная загрузка: ")
                .append(lastSuccessfulDownloadText());

        ReleaseHistory.Entry current = ReleaseHistory.find(BuildConfig.VERSION_NAME);
        if (current != null) {
            text.append("\n\nИзменения текущей версии\n")
                    .append(current.changes);
        }

        text.append("\n\nИстория релизов");
        for (ReleaseHistory.Entry entry : ReleaseHistory.entries()) {
            text.append("\n\nВерсия ").append(entry.version)
                    .append("\n").append(entry.changes);
        }
        return text.toString();
    }

    private String databaseCountText() {
        if (databaseCount == COUNT_LOADING) {
            return "Объектов в базе: загрузка…";
        }
        if (databaseCount == COUNT_UNAVAILABLE) {
            return "Объектов в базе: недоступно";
        }
        return "Объектов в базе: "
                + NumberFormat.getIntegerInstance().format(databaseCount);
    }

    private String lastSuccessfulDownloadText() {
        long timestamp = getCarContext().getSharedPreferences(
                AppSettings.PREFERENCES, Context.MODE_PRIVATE)
                .getLong(AppSettings.RADARBASE_LAST_SUCCESSFUL_DOWNLOAD, 0L);
        if (timestamp <= 0L) return "не выполнялась";
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(new Date(timestamp));
    }
}
