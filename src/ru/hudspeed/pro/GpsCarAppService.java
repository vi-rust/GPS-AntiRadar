package ru.gpsantiradar.app;

import androidx.car.app.CarAppService;
import androidx.car.app.Session;
import androidx.car.app.validation.HostValidator;

public final class GpsCarAppService extends CarAppService {
    @Override public HostValidator createHostValidator() {
        return new HostValidator.Builder(this).build();
    }

    @Override public Session onCreateSession() {
        return new GpsCarSession();
    }
}
