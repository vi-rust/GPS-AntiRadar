package ru.gpsantiradar.app;

public final class MapKitLifecycle {
    public interface Delegate {
        void onStart();
        void onStop();
    }

    private final Delegate delegate;
    private int owners;

    public MapKitLifecycle(Delegate delegate) {
        this.delegate = delegate;
    }

    public synchronized void acquire() {
        if (owners++ == 0) delegate.onStart();
    }

    public synchronized void release() {
        if (owners == 0) return;
        if (--owners == 0) delegate.onStop();
    }
}
