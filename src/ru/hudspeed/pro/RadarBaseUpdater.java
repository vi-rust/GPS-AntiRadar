package ru.gpsantiradar.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

public final class RadarBaseUpdater {
    public static final String ACTION_DATABASE_UPDATED =
            "ru.gpsantiradar.app.RADARBASE_UPDATED";

    private static final String RADARBASE_URL =
            "https://radarbase.info/export/cache/RU/main_extended.json";
    private static final String RADARBASE_ETAG = "radarbase_etag";
    private static final String RADARBASE_MODIFIED = "radarbase_modified";
    private static final String RADARBASE_COORDINATE_FIX = "radarbase_coordinate_fix_v1";
    private static final String RADARBASE_IMPORT_FORMAT = "radarbase_import_format";
    private static final int CURRENT_RADARBASE_IMPORT_FORMAT = 2;

    public interface Listener {
        void onRadarBaseUpdate(RadarBaseUpdateState state);
    }

    private final Context context;
    private final RadarBaseUpdateSingleFlight gate;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private volatile RadarBaseUpdateState latestState = new RadarBaseUpdateState(
            0, RadarBaseUpdateState.Status.IDLE, 0, false, "");
    private long sequence;

    public RadarBaseUpdater(Context context, RadarBaseUpdateSingleFlight gate) {
        this.context = context.getApplicationContext();
        this.gate = gate;
    }

    public void requestUpdate() {
        if (!gate.tryStart()) {
            publish(RadarBaseUpdateState.Status.ALREADY_RUNNING, 0, false,
                    "Обновление базы RadarBase уже выполняется");
            return;
        }
        publish(RadarBaseUpdateState.Status.STARTED, 0, false,
                "Обновление базы RadarBase начато");
        new Thread(new Runnable() {
            @Override public void run() {
                updateInBackground();
            }
        }, "radarbase-download").start();
    }

    public RadarBaseUpdateState latestState() {
        return latestState;
    }

    public synchronized void addListener(Listener listener, boolean replayLatest) {
        if (listener == null) return;
        if (!listeners.contains(listener)) listeners.add(listener);
        if (replayLatest) {
            List<Listener> replay = new ArrayList<>();
            replay.add(listener);
            dispatch(replay, latestState);
        }
    }

    public synchronized void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    private void updateInBackground() {
        HttpURLConnection connection = null;
        try {
            SharedPreferences preferences = context.getSharedPreferences(
                    AppSettings.PREFERENCES, Context.MODE_PRIVATE);
            prepareMigration(preferences);
            connection = (HttpURLConnection) new URL(RADARBASE_URL).openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(120000);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Accept-Encoding", "gzip");
            String etag = preferences.getString(RADARBASE_ETAG, "");
            String modified = preferences.getString(RADARBASE_MODIFIED, "");
            if (etag != null && !etag.isEmpty()) {
                connection.setRequestProperty("If-None-Match", etag);
            }
            if (modified != null && !modified.isEmpty()) {
                connection.setRequestProperty("If-Modified-Since", modified);
            }

            int status = connection.getResponseCode();
            if (status == HttpURLConnection.HTTP_NOT_MODIFIED) {
                publish(RadarBaseUpdateState.Status.UNCHANGED, 0, false,
                        "База RadarBase уже актуальна");
                return;
            }
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("Сервер RadarBase: HTTP " + status);
            }

            InputStream raw = connection.getInputStream();
            InputStream input = "gzip".equalsIgnoreCase(connection.getContentEncoding())
                    ? new GZIPInputStream(raw) : raw;
            RadarBaseParser.Result result;
            try (InputStream source = input;
                 CameraDatabase db = new CameraDatabase(context)) {
                result = db.importRadarBase(source);
            }

            SharedPreferences.Editor editor = preferences.edit();
            String newEtag = connection.getHeaderField("ETag");
            String newModified = connection.getHeaderField("Last-Modified");
            if (newEtag != null) editor.putString(RADARBASE_ETAG, newEtag);
            if (newModified != null) editor.putString(RADARBASE_MODIFIED, newModified);
            editor.putBoolean(RADARBASE_COORDINATE_FIX, true);
            editor.putInt(RADARBASE_IMPORT_FORMAT, CURRENT_RADARBASE_IMPORT_FORMAT);
            editor.putLong(AppSettings.RADARBASE_LAST_SUCCESSFUL_DOWNLOAD,
                    System.currentTimeMillis());
            editor.apply();

            context.sendBroadcast(
                    new Intent(ACTION_DATABASE_UPDATED).setPackage(context.getPackageName()));
            String suffix = result.coordinatesCorrected ? " · координаты восстановлены"
                    : " · без поправки координат";
            publish(RadarBaseUpdateState.Status.SUCCESS, result.count,
                    result.coordinatesCorrected, "Импортировано: " + result.count + suffix);
        } catch (Exception error) {
            String detail = error.getMessage();
            publish(RadarBaseUpdateState.Status.ERROR, 0, false,
                    detail == null || detail.trim().isEmpty()
                            ? "Не удалось загрузить JSON RadarBase" : detail);
        } finally {
            if (connection != null) connection.disconnect();
            gate.finish();
        }
    }

    private static void prepareMigration(SharedPreferences preferences) {
        boolean coordinatesReady = preferences.getBoolean(RADARBASE_COORDINATE_FIX, false);
        boolean importReady = preferences.getInt(RADARBASE_IMPORT_FORMAT, 0)
                >= CURRENT_RADARBASE_IMPORT_FORMAT;
        if (!coordinatesReady || !importReady) {
            preferences.edit().remove(RADARBASE_ETAG).remove(RADARBASE_MODIFIED).commit();
        }
    }

    private void publish(RadarBaseUpdateState.Status status, int importedCount,
                         boolean coordinatesCorrected, String message) {
        RadarBaseUpdateState state;
        List<Listener> snapshot;
        synchronized (this) {
            state = new RadarBaseUpdateState(++sequence, status, importedCount,
                    coordinatesCorrected, message);
            latestState = state;
            snapshot = new ArrayList<Listener>(listeners);
        }
        dispatch(snapshot, state);
    }

    private void dispatch(final List<Listener> targets, final RadarBaseUpdateState state) {
        mainHandler.post(new Runnable() {
            @Override public void run() {
                for (Listener listener : targets) {
                    listener.onRadarBaseUpdate(state);
                }
            }
        });
    }
}
