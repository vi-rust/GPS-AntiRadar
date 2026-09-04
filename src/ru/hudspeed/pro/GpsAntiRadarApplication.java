package ru.gpsantiradar.app;

import android.app.Application;
import android.content.Context;

import com.yandex.mapkit.MapKitFactory;

public final class GpsAntiRadarApplication extends Application {
    private static boolean initialized;
    private final MapKitLifecycle mapKitLifecycle = new MapKitLifecycle(
            new MapKitLifecycle.Delegate() {
                @Override public void onStart() {
                    MapKitFactory.getInstance().onStart();
                }

                @Override public void onStop() {
                    MapKitFactory.getInstance().onStop();
                }
            });
    private final RadarBaseUpdateSingleFlight radarBaseUpdateGuard =
            new RadarBaseUpdateSingleFlight();
    private RadarBaseUpdater radarBaseUpdater;

    @Override public void onCreate() {
        super.onCreate();
        radarBaseUpdater = new RadarBaseUpdater(this, radarBaseUpdateGuard);
        radarBaseUpdater.requestUpdate();
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

    public void acquireMapKit() {
        mapKitLifecycle.acquire();
    }

    public void releaseMapKit() {
        mapKitLifecycle.release();
    }

    public RadarBaseUpdater radarBaseUpdater() {
        return radarBaseUpdater;
    }
}
