package ru.gpsantiradar.app;

import android.content.Intent;

public final class DrivingSnapshotIntent {
    private DrivingSnapshotIntent() {}

    public static DrivingSnapshot from(Intent intent) {
        return new DrivingSnapshot(intent.getFloatExtra(TrackingService.EXTRA_SPEED, 0),
                intent.getIntExtra(TrackingService.EXTRA_DISTANCE, -1),
                intent.getStringExtra(TrackingService.EXTRA_CAMERA),
                intent.getLongExtra(TrackingService.EXTRA_CAMERA_ID, -1),
                intent.getIntExtra(TrackingService.EXTRA_LIMIT, 0),
                intent.getIntExtra(TrackingService.EXTRA_ALERT_DISTANCE, 0),
                intent.getDoubleExtra(TrackingService.EXTRA_LATITUDE, Double.NaN),
                intent.getDoubleExtra(TrackingService.EXTRA_LONGITUDE, Double.NaN),
                intent.getStringExtra(TrackingService.EXTRA_ALERT_STATE),
                intent.getStringExtra(TrackingService.EXTRA_ALERT_ALGORITHM));
    }
}
