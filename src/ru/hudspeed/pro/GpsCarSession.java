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

import androidx.car.app.Screen;
import androidx.car.app.Session;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

public final class GpsCarSession extends Session {
    private CarSurfaceController surfaceController;
    private BroadcastReceiver updateReceiver;
    private boolean receiverRegistered;

    public GpsCarSession() {
        getLifecycle().addObserver(new DefaultLifecycleObserver() {
            @Override public void onDestroy(LifecycleOwner owner) {
                destroyCarResources();
            }
        });
    }

    @Override public Screen onCreateScreen(Intent intent) {
        Context context = getCarContext();
        boolean locationGranted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        boolean mapKitReady = GpsAntiRadarApplication.ensureMapKit(context);
        if (!locationGranted || !mapKitReady) {
            destroyCarResources();
            return new CarSetupScreen(getCarContext(), locationGranted, mapKitReady);
        }

        destroyCarResources();
        GpsAntiRadarApplication application =
                (GpsAntiRadarApplication) context.getApplicationContext();
        surfaceController = new CarSurfaceController(getCarContext(), application);
        registerTrackingReceiver();
        context.startForegroundService(new Intent(context, TrackingService.class)
                .setAction(TrackingService.ACTION_START));
        return new CarMapScreen(getCarContext(), surfaceController);
    }

    @Override public void onCarConfigurationChanged(Configuration newConfiguration) {
        super.onCarConfigurationChanged(newConfiguration);
        CarSurfaceController controller = surfaceController;
        if (controller != null) controller.onCarConfigurationChanged();
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerTrackingReceiver() {
        if (receiverRegistered) return;
        updateReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                CarSurfaceController controller = surfaceController;
                if (controller != null) {
                    controller.onDrivingSnapshot(DrivingSnapshotIntent.from(intent));
                }
            }
        };
        IntentFilter filter = new IntentFilter(TrackingService.ACTION_UPDATE);
        if (Build.VERSION.SDK_INT >= 33) {
            getCarContext().registerReceiver(
                    updateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            getCarContext().registerReceiver(updateReceiver, filter);
        }
        receiverRegistered = true;
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
