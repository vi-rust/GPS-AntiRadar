package ru.gpsantiradar.app;

import java.util.concurrent.atomic.AtomicBoolean;

final class RadarBaseUpdateSingleFlight {
    private final AtomicBoolean running = new AtomicBoolean();

    boolean tryStart() {
        return running.compareAndSet(false, true);
    }

    void finish() {
        running.set(false);
    }
}
