package ru.gpsantiradar.app;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

final class RadarBaseUpdateListenerRegistry {
    interface Listener {
        void onRadarBaseUpdate(RadarBaseUpdateState state);
    }

    interface Dispatcher {
        void post(Runnable callback);
    }

    private static final class Registration {
        final long token;
        final Listener listener;
        long lastDeliveredSequence = Long.MIN_VALUE;

        Registration(long token, Listener listener) {
            this.token = token;
            this.listener = listener;
        }
    }

    private final Dispatcher dispatcher;
    private final Map<Listener, Registration> registrations = new IdentityHashMap<>();
    private volatile RadarBaseUpdateState latestState = new RadarBaseUpdateState(
            0, RadarBaseUpdateState.Status.IDLE, 0, false, "");
    private long sequence;
    private long nextRegistrationToken;

    RadarBaseUpdateListenerRegistry(Dispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    RadarBaseUpdateState latestState() {
        return latestState;
    }

    synchronized void addListener(Listener listener, boolean replayLatest) {
        if (listener == null) return;
        Registration registration =
                new Registration(++nextRegistrationToken, listener);
        registrations.put(listener, registration);
        if (replayLatest) enqueue(registration, latestState);
    }

    synchronized void removeListener(Listener listener) {
        registrations.remove(listener);
    }

    synchronized RadarBaseUpdateState publish(RadarBaseUpdateState.Status status,
                                               int importedCount,
                                               boolean coordinatesCorrected,
                                               String message) {
        RadarBaseUpdateState state = new RadarBaseUpdateState(++sequence, status,
                importedCount, coordinatesCorrected, message);
        latestState = state;
        List<Registration> snapshot = new ArrayList<>(registrations.values());
        for (Registration registration : snapshot) enqueue(registration, state);
        return state;
    }

    private void enqueue(final Registration registration,
                         final RadarBaseUpdateState state) {
        dispatcher.post(new Runnable() {
            @Override public void run() {
                deliver(registration, state);
            }
        });
    }

    private void deliver(Registration registration, RadarBaseUpdateState state) {
        Listener listener;
        synchronized (this) {
            Registration current = registrations.get(registration.listener);
            if (current != registration || current.token != registration.token
                    || state.sequence <= current.lastDeliveredSequence) {
                return;
            }
            current.lastDeliveredSequence = state.sequence;
            listener = current.listener;
        }
        listener.onRadarBaseUpdate(state);
    }
}
