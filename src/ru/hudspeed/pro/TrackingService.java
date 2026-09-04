package ru.gpsantiradar.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class TrackingService extends Service implements LocationListener {
    public static final String ACTION_START = "ru.gpsantiradar.app.START";
    public static final String ACTION_STOP = "ru.gpsantiradar.app.STOP";
    public static final String ACTION_UPDATE = "ru.gpsantiradar.app.UPDATE";
    public static final String EXTRA_SPEED = "speed";
    public static final String EXTRA_DISTANCE = "distance";
    public static final String EXTRA_CAMERA = "camera";
    public static final String EXTRA_LIMIT = "limit";
    public static final String EXTRA_ALERT_DISTANCE = "alert_distance";
    public static final String EXTRA_ACCURACY = "accuracy";
    public static final String EXTRA_LATITUDE = "latitude";
    public static final String EXTRA_LONGITUDE = "longitude";
    public static final String EXTRA_ALERT_STATE = "alert_state";
    public static final String EXTRA_ALERT_ALGORITHM = "alert_algorithm";

    private static final String CHANNEL = "tracking";
    private static final int NOTIFICATION_ID = 41;
    private LocationManager locationManager;
    private CameraDatabase database;
    private StrelkaSoundPlayer soundPlayer;
    private TextToSpeech tts;
    private Location previousGps;
    private Location lastRadarScan;
    private float radarHeading = Float.NaN;
    private final StrelkaAlertTracker alertTracker = new StrelkaAlertTracker();
    private long lastRadarFixElapsed;
    private long alertedCameraId = -1;
    private boolean finalWarning;
    private long alertedRoadObjectId = -1;

    @Override public void onCreate() {
        super.onCreate();
        database = new CameraDatabase(this);
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        soundPlayer = new StrelkaSoundPlayer(this);
        createChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopTracking();
            return START_NOT_STICKY;
        }
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification("Ожидание сигнала GPS"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIFICATION_ID, notification("Ожидание сигнала GPS"));
        }
        startTracking();
        return START_STICKY;
    }

    private void startTracking() {
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            stopSelf();
            return;
        }
        locationManager.removeUpdates(this);
        try { locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this); }
        catch (RuntimeException ignored) {}
        try { locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000L, 5f, this); }
        catch (RuntimeException ignored) {}
    }

    @Override public void onLocationChanged(Location location) {
        if (strelkaAlertsEnabled()) {
            onStrelkaLocationChanged(location);
            return;
        }
        boolean gpsLocation = LocationManager.GPS_PROVIDER.equals(location.getProvider());
        if (!gpsLocation && hasRecentGpsFix()) return;
        float speedKmh = speed(location);
        float heading = heading(location);
        CameraPoint nearestCamera = null;
        double nearestCameraDistance = Double.MAX_VALUE;
        CameraPoint nearestRoadObject = null;
        double nearestRoadDistance = Double.MAX_VALUE;
        CameraPoint roadWarningCandidate = null;
        double roadWarningDistance = Double.MAX_VALUE;
        double roadWarningProgress = Double.MAX_VALUE;
        List<CameraPoint> candidates = database.nearby(location.getLatitude(), location.getLongitude(), 5000);
        for (CameraPoint object : candidates) {
            double distance = Geo.distanceMeters(location.getLatitude(), location.getLongitude(),
                    object.latitude, object.longitude);
            if (distance > 5000) continue;
            float toObject = Geo.bearing(location.getLatitude(), location.getLongitude(),
                    object.latitude, object.longitude);
            if (speedKmh >= 8 && distance > 60 && Geo.angleDifference(heading, toObject) > 65) continue;
            if (speedKmh >= 8 && distance > 60 && !matchesControlledDirection(object, heading)) continue;
            if (object.isCameraOrControl()) {
                if (distance < nearestCameraDistance) {
                    nearestCamera = object;
                    nearestCameraDistance = distance;
                }
            } else {
                if (distance < nearestRoadDistance) {
                    nearestRoadObject = object;
                    nearestRoadDistance = distance;
                }
                int warningDistance = roadAlertDistance(object);
                double progress = distance / warningDistance;
                if (distance <= warningDistance && progress < roadWarningProgress) {
                    roadWarningCandidate = object;
                    roadWarningDistance = distance;
                    roadWarningProgress = progress;
                }
            }
        }

        boolean cameraSpoken = false;
        if (nearestCamera == null) {
            alertedCameraId = -1;
            finalWarning = false;
        } else {
            int alertDistance = getSharedPreferences("settings", MODE_PRIVATE)
                    .getInt("alert_distance", 800);
            if (nearestCamera.id != alertedCameraId && nearestCameraDistance <= alertDistance) {
                alertedCameraId = nearestCamera.id;
                finalWarning = false;
                speakWarning(nearestCamera, nearestCameraDistance, false);
                cameraSpoken = true;
            } else if (nearestCamera.id == alertedCameraId && !finalWarning
                    && nearestCameraDistance <= 300) {
                finalWarning = true;
                speakWarning(nearestCamera, nearestCameraDistance, true);
                cameraSpoken = true;
            }
        }

        if (speedKmh >= 3 && roadWarningCandidate != null
                && roadWarningCandidate.id != alertedRoadObjectId && !cameraSpoken) {
            alertedRoadObjectId = roadWarningCandidate.id;
            speakRoadWarning(roadWarningCandidate, roadWarningDistance);
        } else if (roadWarningCandidate == null
                && (nearestRoadObject == null
                || nearestRoadDistance > roadAlertDistance(nearestRoadObject) + 100)) {
            alertedRoadObjectId = -1;
        }

        CameraPoint nearest;
        double nearestDistance;
        if (nearestCameraDistance <= nearestRoadDistance) {
            nearest = nearestCamera;
            nearestDistance = nearestCameraDistance;
        } else {
            nearest = nearestRoadObject;
            nearestDistance = nearestRoadDistance;
        }

        Intent update = new Intent(ACTION_UPDATE).setPackage(getPackageName());
        update.putExtra(EXTRA_SPEED, speedKmh);
        update.putExtra(EXTRA_ACCURACY, location.getAccuracy());
        update.putExtra(EXTRA_LATITUDE, location.getLatitude());
        update.putExtra(EXTRA_LONGITUDE, location.getLongitude());
        update.putExtra(EXTRA_DISTANCE, nearest == null ? -1 : (int) Math.round(nearestDistance));
        update.putExtra(EXTRA_CAMERA, nearest == null ? "" : nearest.typeName());
        update.putExtra(EXTRA_LIMIT, nearest == null || nearest.isRoadObject()
                ? 0 : nearest.currentSpeedLimit());
        update.putExtra(EXTRA_ALERT_DISTANCE, nearest == null ? 0
                : nearest.isRoadObject() ? roadAlertDistance(nearest)
                : getSharedPreferences("settings", MODE_PRIVATE).getInt("alert_distance", 800));
        sendBroadcast(update);

        String line = nearest == null ? Math.round(speedKmh) + " км/ч"
                : nearest.typeName() + " · " + formatDistance(nearestDistance);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                .notify(NOTIFICATION_ID, notification(line));
        if (gpsLocation) previousGps = new Location(location);
    }

    private boolean strelkaAlertsEnabled() {
        return true;
    }

    private void onStrelkaLocationChanged(Location location) {
        boolean gpsLocation = LocationManager.GPS_PROVIDER.equals(location.getProvider());
        if (!gpsLocation && hasRecentGpsFix()) return;

        float speedKmh = speed(location);
        float heading = heading(location);
        double movementSinceScan = lastRadarScan == null ? Double.MAX_VALUE
                : Geo.distanceMeters(lastRadarScan.getLatitude(), lastRadarScan.getLongitude(),
                location.getLatitude(), location.getLongitude());
        float requiredMovement = location.hasAccuracy()
                ? Math.max(3f, location.getAccuracy()) : 3f;
        boolean scanPerformed = requiredMovement < 60f
                && movementSinceScan > requiredMovement;
        if (scanPerformed && lastRadarScan != null) {
            float measuredHeading = Geo.bearing(lastRadarScan.getLatitude(),
                    lastRadarScan.getLongitude(), location.getLatitude(),
                    location.getLongitude());
            radarHeading = Float.isNaN(radarHeading) ? measuredHeading
                    : averageHeading(radarHeading, measuredHeading);
            heading = radarHeading;
        } else if (!Float.isNaN(radarHeading)) {
            heading = radarHeading;
        }
        int fallbackAlertDistance = getSharedPreferences("settings", MODE_PRIVATE)
                .getInt("alert_distance", 800);
        long now = SystemClock.elapsedRealtime();
        boolean gpsRecovered = lastRadarFixElapsed > 0
                && now - lastRadarFixElapsed > 5000L;
        lastRadarFixElapsed = now;
        CameraPoint nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        int nearestAlertDistance = 0;

        List<StrelkaAlertTracker.Observation> observations = new ArrayList<>();
        List<CameraPoint> candidates = database.nearby(location.getLatitude(),
                location.getLongitude(), StrelkaAlertAlgorithm.SEARCH_RADIUS_METERS);
        for (CameraPoint object : candidates) {
            double distance = Geo.distanceMeters(location.getLatitude(), location.getLongitude(),
                    object.latitude, object.longitude);
            if (distance > StrelkaAlertAlgorithm.SEARCH_RADIUS_METERS) continue;
            float bearing = Geo.bearing(location.getLatitude(), location.getLongitude(),
                    object.latitude, object.longitude);
            int alertDistance = StrelkaAlertAlgorithm.activationDistance(
                    object, fallbackAlertDistance);
            boolean matchesZone = StrelkaAlertAlgorithm.matchesZone(object, distance,
                    heading, bearing, fallbackAlertDistance);
            boolean dropImmediately = StrelkaAlertAlgorithm.mustDropImmediately(
                    object, speedKmh, distance, heading, bearing);
            observations.add(new StrelkaAlertTracker.Observation(object,
                    (int) Math.round(distance), alertDistance,
                    matchesZone, dropImmediately));

            boolean relevant = !dropImmediately && (matchesZone
                    || StrelkaAlertAlgorithm.matchesDirectionForAcquisition(
                    object, heading));
            if (relevant && distance < nearestDistance) {
                nearest = object;
                nearestDistance = distance;
                nearestAlertDistance = alertDistance;
            }
        }

        StrelkaAlertTracker.Update alertUpdate;
        if (scanPerformed) {
            alertUpdate = alertTracker.update(observations, speedKmh, gpsRecovered);
            lastRadarScan = new Location(location);
        } else {
            alertUpdate = alertTracker.snapshot();
        }
        CameraPoint finishedObject = null;
        for (StrelkaAlertTracker.State exited : alertUpdate.exited) {
            if (exited.spoken) {
                finishedObject = exited.object;
                break;
            }
        }
        if (finishedObject != null && alertUpdate.activeCount == 0) {
            soundPlayer.objectFinished(finishedObject);
        }

        String alertState = runAlertSequence(alertUpdate, speedKmh, scanPerformed,
                nearest, nearestDistance, nearestAlertDistance);
        sendUpdate(location, speedKmh, nearest, nearestDistance,
                nearestAlertDistance, alertState);

        String line = nearest == null ? Math.round(speedKmh) + " km/h"
                : nearest.typeName() + " - " + formatDistance(nearestDistance);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                .notify(NOTIFICATION_ID, notification(line));
        if (gpsLocation) previousGps = new Location(location);
    }

    private boolean matchesControlledDirection(CameraPoint camera, float vehicleHeading) {
        if (camera.dirType == 0) return true;
        if (Geo.angleDifference(vehicleHeading, camera.direction) <= 70) return true;
        return camera.dirType == 2
                && Geo.angleDifference(vehicleHeading, camera.direction + 180f) <= 70;
    }

    private String runAlertSequence(StrelkaAlertTracker.Update update, float speedKmh,
                                    boolean scanPerformed,
                                    CameraPoint nearest, double nearestDistance,
                                    int nearestAlertDistance) {
        StrelkaAlertTracker.State pending = update.pendingVoice;
        if (pending != null) {
            soundPlayer.announce(pending.object, pending.distanceMeters,
                    update.activeCount > 1);
            alertTracker.markSpoken(pending.object.id);
            return "Вход подтверждён: голос · " + pending.distanceMeters + " м";
        }
        StrelkaAlertTracker.State closest = update.closestActive;
        if (closest != null && closest.spoken) {
            if (StrelkaAlertAlgorithm.isOverspeeding(closest.object, speedKmh)) {
                boolean signaled = scanPerformed
                        && soundPlayer.beepIfIdle(closest.distanceMeters);
                return "В зоне: " + closest.distanceMeters + " м · превышение +"
                        + StrelkaAlertAlgorithm.OVERSPEED_THRESHOLD_KMH + " · "
                        + (signaled ? "сигнал" : "ожидание сигнала");
            }
            return "В зоне: " + closest.distanceMeters + " м · без превышения";
        }
        StrelkaAlertTracker.State tracking = update.closestTracking;
        if (tracking != null) {
            int required = Math.round(tracking.distanceMeters * 0.1f);
            return "Подтверждение подхода: " + Math.round(tracking.confidence)
                    + " / " + required;
        }
        if (nearest != null) {
            return "До зоны: " + Math.round(nearestDistance) + " м / "
                    + nearestAlertDistance + " м";
        }
        return "Поиск впереди: "
                + StrelkaAlertAlgorithm.SEARCH_RADIUS_METERS + " м";
    }

    private float speed(Location location) {
        if (!LocationManager.GPS_PROVIDER.equals(location.getProvider()) || !location.hasSpeed()) {
            return 0;
        }
        float metersPerSecond = Math.max(0, location.getSpeed());
        float stationaryThreshold = 0.8f;
        if (Build.VERSION.SDK_INT >= 26 && location.hasSpeedAccuracy()) {
            stationaryThreshold = Math.max(stationaryThreshold,
                    location.getSpeedAccuracyMetersPerSecond());
        }
        return metersPerSecond <= stationaryThreshold ? 0 : metersPerSecond * 3.6f;
    }

    private float heading(Location location) {
        if (location.hasBearing() && location.getSpeed() > 1.5f) return location.getBearing();
        if (previousGps != null && LocationManager.GPS_PROVIDER.equals(location.getProvider())) {
            return Geo.bearing(previousGps.getLatitude(), previousGps.getLongitude(),
                location.getLatitude(), location.getLongitude());
        }
        return 0;
    }

    private static float averageHeading(float first, float second) {
        double x = Math.cos(Math.toRadians(first)) + Math.cos(Math.toRadians(second));
        double y = Math.sin(Math.toRadians(first)) + Math.sin(Math.toRadians(second));
        return Geo.normalize((float) Math.toDegrees(Math.atan2(y, x)));
    }

    private boolean hasRecentGpsFix() {
        if (previousGps == null) return false;
        long ageNanos = SystemClock.elapsedRealtimeNanos() - previousGps.getElapsedRealtimeNanos();
        return ageNanos >= 0 && ageNanos < 10_000_000_000L;
    }

    private void speakWarning(CameraPoint camera, double distance, boolean close) {
        int rounded = close ? (int) (Math.max(50, distance) / 50) * 50
                : (int) (Math.max(100, distance) / 100) * 100;
        StringBuilder text = new StringBuilder(camera.typeName())
                .append(" через ").append(rounded).append(" метров");
        int speedLimit = camera.currentSpeedLimit();
        if (speedLimit > 0) text.append(". Ограничение ").append(speedLimit);
        if (Build.VERSION.SDK_INT >= 21) tts.speak(text.toString(), TextToSpeech.QUEUE_FLUSH, null, "camera");
        else tts.speak(text.toString(), TextToSpeech.QUEUE_FLUSH, null);
    }

    private void speakRoadWarning(CameraPoint object, double distance) {
        int rounded = (int) (Math.max(50, distance) / 50) * 50;
        String text = object.typeName() + ". Через " + rounded + " метров";
        if (Build.VERSION.SDK_INT >= 21) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "road-object");
        } else {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null);
        }
    }

    static int roadAlertDistance(CameraPoint object) {
        int distance = object.distanceMeters > 0 ? object.distanceMeters : 300;
        return Math.max(50, Math.min(1500, distance));
    }

    private String formatDistance(double meters) {
        return meters >= 1000 ? String.format(Locale.US, "%.1f км", meters / 1000.0)
                : Math.round(meters) + " м";
    }

    private void sendUpdate(Location location, float speedKmh, CameraPoint nearest,
                            double nearestDistance, int alertDistance, String alertState) {
        Intent update = new Intent(ACTION_UPDATE).setPackage(getPackageName());
        update.putExtra(EXTRA_SPEED, speedKmh);
        update.putExtra(EXTRA_ACCURACY, location.getAccuracy());
        update.putExtra(EXTRA_LATITUDE, location.getLatitude());
        update.putExtra(EXTRA_LONGITUDE, location.getLongitude());
        update.putExtra(EXTRA_DISTANCE,
                nearest == null ? -1 : (int) Math.round(nearestDistance));
        update.putExtra(EXTRA_CAMERA, nearest == null ? "" : nearest.typeName());
        update.putExtra(EXTRA_LIMIT,
                nearest == null || nearest.isRoadObject() ? 0 : nearest.currentSpeedLimit());
        update.putExtra(EXTRA_ALERT_DISTANCE, nearest == null ? 0 : alertDistance);
        update.putExtra(EXTRA_ALERT_STATE, alertState);
        update.putExtra(EXTRA_ALERT_ALGORITHM, StrelkaAlertAlgorithm.screenSummary());
        sendBroadcast(update);
    }

    private Notification notification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent content = PendingIntent.getActivity(this, 0, open, pendingFlags);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return builder.setSmallIcon(ru.gpsantiradar.app.R.drawable.ic_launcher)
                .setContentTitle("GPS AntiRadar работает")
                .setContentText(text)
                .setContentIntent(content)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL,
                    "GPS-предупреждения", NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        }
    }

    private void stopTracking() {
        try { locationManager.removeUpdates(this); } catch (RuntimeException ignored) {}
        stopForeground(true);
        stopSelf();
    }

    @Override public void onProviderEnabled(String provider) {}
    @Override public void onProviderDisabled(String provider) {}
    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        try { locationManager.removeUpdates(this); } catch (RuntimeException ignored) {}
        alertTracker.clear();
        if (soundPlayer != null) soundPlayer.release();
        if (tts != null) { tts.stop(); tts.shutdown(); }
        database.close();
        super.onDestroy();
    }
}
