package ru.gpsantiradar.app;

import android.app.Application;
import android.content.Context;

import com.yandex.mapkit.MapKitFactory;

public final class GpsAntiRadarApplication extends Application {
    private static boolean initialized;
    private final ProcessLaunchGuard radarBaseStartupUpdateGuard = new ProcessLaunchGuard();
    private final RadarBaseUpdateSingleFlight radarBaseUpdateGuard =
            new RadarBaseUpdateSingleFlight();

    @Override public void onCreate() {
        super.onCreate();
    }

    public static synchronized boolean ensureMapKit(Context context) {
        if (initialized) return true;
        String key = BuildConfig.MAPKIT_API_KEY == null ? "" : BuildConfig.MAPKIT_API_KEY.trim();
        if (key.isEmpty()) {
            key = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                    .getString("yandex_mapkit_key", "").trim();
        }
        if (key.isEmpty()) return false;
        try {
            MapKitFactory.setApiKey(key);
            MapKitFactory.initialize(context.getApplicationContext());
            initialized = true;
            return true;
        } catch (Throwable error) {
            return false;
        }
    }

    public boolean claimRadarBaseStartupUpdate() {
        return radarBaseStartupUpdateGuard.claim();
    }

    public boolean tryStartRadarBaseUpdate() {
        return radarBaseUpdateGuard.tryStart();
    }

    public void finishRadarBaseUpdate() {
        radarBaseUpdateGuard.finish();
    }
}
