package ru.gpsantiradar.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.car.app.CarToast;
import androidx.car.app.Screen;
import androidx.car.app.ScreenManager;
import androidx.car.app.Session;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

public final class GpsCarSession extends Session {
    private CarSurfaceController surfaceController;
    private CarSetupScreen setupScreen;
    private CarRadarBaseUpdateNotifier updateNotifier;
    private BroadcastReceiver updateReceiver;
    private boolean receiverRegistered;
    private boolean surfaceFailureShown;
    private int databaseLoadGeneration;

    public GpsCarSession() {
        getLifecycle().addObserver(new DefaultLifecycleObserver() {
            @Override public void onDestroy(LifecycleOwner owner) {
                stopUpdateFeedback();
                destroyCarResources();
            }
        });
    }

    @Override public Screen onCreateScreen(Intent intent) {
        Context context = getCarContext();
        GpsAntiRadarApplication application =
                (GpsAntiRadarApplication) context.getApplicationContext();
        startUpdateFeedback(application.radarBaseUpdater());
        surfaceFailureShown = false;
        boolean locationGranted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        boolean mapKitReady = GpsAntiRadarApplication.ensureMapKit(context);
        CarStartupDecision startup = CarStartupDecision.from(
                locationGranted, mapKitReady);
        if (startup.startTracking) {
            context.startForegroundService(new Intent(context, TrackingService.class)
                    .setAction(TrackingService.ACTION_START));
        }
        if (!startup.showMap) {
            destroyCarResources();
            setupScreen = new CarSetupScreen(getCarContext(), locationGranted,
                    mapKitReady, startup.startTracking, null);
            if (startup.startTracking) registerUpdateReceiver();
            return setupScreen;
        }

        destroyCarResources();
        surfaceController = new CarSurfaceController(
                getCarContext(), application, this::showSurfaceFailure);
        registerUpdateReceiver();
        loadDatabaseCount();
        return new CarMapScreen(getCarContext(), surfaceController);
    }

    private void showSurfaceFailure(String message) {
        if (surfaceFailureShown) return;
        surfaceFailureShown = true;
        getCarContext().getCarService(ScreenManager.class).push(
                new CarSetupScreen(getCarContext(), true, true, message));
    }

    @Override public void onCarConfigurationChanged(Configuration newConfiguration) {
        super.onCarConfigurationChanged(newConfiguration);
        CarSurfaceController controller = surfaceController;
        if (controller != null) controller.onCarConfigurationChanged();
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerUpdateReceiver() {
        if (receiverRegistered) return;
        updateReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (intent == null) return;
                CarSurfaceController controller = surfaceController;
                CarSetupScreen setup = setupScreen;
                if (TrackingService.ACTION_STOPPED.equals(intent.getAction())) {
                    if (controller != null) controller.onTrackingStopped();
                    if (setup != null) setup.setTrackingActive(false);
                    return;
                }
                if (TrackingService.ACTION_UPDATE.equals(intent.getAction()) && setup != null) {
                    setup.setTrackingActive(true);
                }
                if (controller == null) return;
                if (RadarBaseUpdater.ACTION_DATABASE_UPDATED.equals(intent.getAction())) {
                    controller.refreshVisible();
                    loadDatabaseCount();
                } else if (TrackingService.ACTION_UPDATE.equals(intent.getAction())) {
                    controller.onDrivingSnapshot(DrivingSnapshotIntent.from(intent));
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(TrackingService.ACTION_UPDATE);
        filter.addAction(TrackingService.ACTION_STOPPED);
        filter.addAction(RadarBaseUpdater.ACTION_DATABASE_UPDATED);
        if (Build.VERSION.SDK_INT >= 33) {
            getCarContext().registerReceiver(
                    updateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            getCarContext().registerReceiver(updateReceiver, filter);
        }
        receiverRegistered = true;
    }

    private void startUpdateFeedback(RadarBaseUpdater updater) {
        if (updateNotifier == null) {
            updateNotifier = new CarRadarBaseUpdateNotifier(
                    updater, this::showUpdateState);
        }
        updateNotifier.start();
    }

    private void stopUpdateFeedback() {
        if (updateNotifier == null) return;
        updateNotifier.stop();
        updateNotifier = null;
    }

    private void showUpdateState(RadarBaseUpdateState state) {
        int duration = state.status == RadarBaseUpdateState.Status.STARTED
                || state.status == RadarBaseUpdateState.Status.ALREADY_RUNNING
                ? CarToast.LENGTH_SHORT : CarToast.LENGTH_LONG;
        CarToast.makeText(getCarContext(), state.message, duration).show();
    }

    private void loadDatabaseCount() {
        final int generation = ++databaseLoadGeneration;
        final Context context = getCarContext();
        new Thread(new Runnable() {
            @Override public void run() {
                int count;
                try (CameraDatabase database = new CameraDatabase(context)) {
                    count = database.count();
                } catch (RuntimeException error) {
                    count = -1;
                }
                final int result = count;
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override public void run() {
                        CarSurfaceController controller = surfaceController;
                        if (generation == databaseLoadGeneration && controller != null) {
                            controller.onDatabaseCount(result);
                        }
                    }
                });
            }
        }, "car-database-count").start();
    }

    private void destroyCarResources() {
        databaseLoadGeneration++;
        setupScreen = null;
        if (receiverRegistered) {
            getCarContext().unregisterReceiver(updateReceiver);
            receiverRegistered = false;
            updateReceiver = null;
        }
        if (surfaceController != null) {
            surfaceController.destroy();
            surfaceController = null;
        }
    }
}
