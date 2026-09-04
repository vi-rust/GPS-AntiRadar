package ru.gpsantiradar.app;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Stateful approach confirmation and re-alert behavior reconstructed from Strelka HUD. */
public final class StrelkaAlertTracker {
    private final Map<Long, State> states = new HashMap<>();

    public Update update(List<Observation> observations, float speedKmh,
                         boolean gpsRecovered) {
        for (State state : states.values()) state.observed = false;

        for (Observation observation : observations) {
            State state = states.get(observation.object.id);
            boolean created = false;
            if (state == null && observation.matchesZone) {
                state = new State(observation.object);
                states.put(observation.object.id, state);
                created = true;
            }
            if (state == null) continue;

            state.observed = true;
            state.distanceMeters = observation.distanceMeters;
            state.activationDistance = observation.activationDistance;
            if (observation.dropImmediately) {
                state.confidence = 0f;
            } else {
                state.confidence = StrelkaAlertAlgorithm.updateConfidence(
                        state.confidence, speedKmh, observation.matchesZone,
                        gpsRecovered && created);
            }
            if (!state.active && StrelkaAlertAlgorithm.shouldActivate(
                    state.confidence, state.distanceMeters)) {
                state.active = true;
            }
        }

        List<State> exited = new ArrayList<>();
        Iterator<State> iterator = states.values().iterator();
        while (iterator.hasNext()) {
            State state = iterator.next();
            if (!state.observed || state.confidence <= 0f) {
                if (state.active) exited.add(state);
                iterator.remove();
            }
        }

        return summarize(exited);
    }

    public Update snapshot() {
        return summarize(new ArrayList<State>());
    }

    private Update summarize(List<State> exited) {
        State closestActive = null;
        State pendingVoice = null;
        State closestTracking = null;
        int activeCount = 0;
        for (State state : states.values()) {
            if (closestTracking == null
                    || state.distanceMeters < closestTracking.distanceMeters) {
                closestTracking = state;
            }
            if (!state.active) continue;
            activeCount++;
            if (closestActive == null
                    || state.distanceMeters < closestActive.distanceMeters) {
                closestActive = state;
            }
            if (!state.spoken && (pendingVoice == null
                    || state.distanceMeters < pendingVoice.distanceMeters)) {
                pendingVoice = state;
            }
        }
        return new Update(exited, closestActive, pendingVoice,
                closestTracking, activeCount);
    }

    public void markSpoken(long objectId) {
        State state = states.get(objectId);
        if (state != null) state.spoken = true;
    }

    public void clear() {
        states.clear();
    }

    public static final class Observation {
        final CameraPoint object;
        final int distanceMeters;
        final int activationDistance;
        final boolean matchesZone;
        final boolean dropImmediately;

        public Observation(CameraPoint object, int distanceMeters,
                           int activationDistance, boolean matchesZone,
                           boolean dropImmediately) {
            this.object = object;
            this.distanceMeters = distanceMeters;
            this.activationDistance = activationDistance;
            this.matchesZone = matchesZone;
            this.dropImmediately = dropImmediately;
        }
    }

    public static final class State {
        final CameraPoint object;
        float confidence;
        boolean active;
        boolean spoken;
        boolean observed;
        int distanceMeters;
        int activationDistance;

        State(CameraPoint object) {
            this.object = object;
        }
    }

    public static final class Update {
        final List<State> exited;
        final State closestActive;
        final State pendingVoice;
        final State closestTracking;
        final int activeCount;

        Update(List<State> exited, State closestActive, State pendingVoice,
               State closestTracking, int activeCount) {
            this.exited = exited;
            this.closestActive = closestActive;
            this.pendingVoice = pendingVoice;
            this.closestTracking = closestTracking;
            this.activeCount = activeCount;
        }
    }
}
