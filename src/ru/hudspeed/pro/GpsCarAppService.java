package ru.gpsantiradar.app;

import android.annotation.SuppressLint;

import androidx.car.app.CarAppService;
import androidx.car.app.Session;
import androidx.car.app.validation.HostValidator;

public final class GpsCarAppService extends CarAppService {
    @SuppressLint("PrivateResource")
    @Override public HostValidator createHostValidator() {
        return new HostValidator.Builder(this)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build();
    }

    @Override public Session onCreateSession() {
        return new GpsCarSession();
    }
}
