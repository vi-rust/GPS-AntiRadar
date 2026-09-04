package ru.gpsantiradar.app;

public final class ProcessLaunchGuard {
    private boolean claimed;

    public synchronized boolean claim() {
        if (claimed) return false;
        claimed = true;
        return true;
    }
}
