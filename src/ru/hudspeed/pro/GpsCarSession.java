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

import androidx.car.app.CarToast;
import androidx.car.app.Screen;
import androidx.car.app.ScreenManager;
import androidx.car.app.Session;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

public final class GpsCarSession extends Session {
    private CarSurfaceController surfaceController;
    private CarRadarBaseUpdateNotifier updateNotifier;
    private BroadcastReceiver updateReceiver;
    private boolean receiverRegistered;
    private boolean surfaceFailureShown;

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
        if (!locationGranted || !mapKitReady) {
            destroyCarResources();
            return new CarSetupScreen(getCarContext(), locationGranted, mapKitReady);
        }

        destroyCarResources();
        surfaceController = new CarSurfaceController(
                getCarContext(), application, this::showSurfaceFailure);
        registerUpdateReceiver();
        context.startForegroundService(new Intent(context, TrackingService.class)
                .setAction(TrackingService.ACTION_START));
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
                CarSurfaceController controller = surfaceController;
                if (controller == null || intent == null) return;
                if (RadarBaseUpdater.ACTION_DATABASE_UPDATED.equals(intent.getAction())) {
                    controller.refreshVisible();
                } else if (TrackingService.ACTION_UPDATE.equals(intent.getAction())) {
                    controller.onDrivingSnapshot(DrivingSnapshotIntent.from(intent));
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(TrackingService.ACTION_UPDATE);
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

    private void destroyCarResources() {
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
