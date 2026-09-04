package ru.gpsantiradar.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class CarRadarBaseUpdateNotifierTest {
    @Test public void startReplaysActiveColdUpdateAndDoesNotRegisterTwice() {
        FakeSource source = new FakeSource();
        List<RadarBaseUpdateState> shown = new ArrayList<>();
        CarRadarBaseUpdateNotifier notifier =
                new CarRadarBaseUpdateNotifier(source, shown::add);

        notifier.start();
        notifier.start();

        assertTrue(source.replayLatest);
        assertEquals(1, source.addCount);
        RadarBaseUpdateState started = state(
                1, RadarBaseUpdateState.Status.STARTED, "Начато");
        source.emit(started);
        assertEquals(Arrays.asList(started), shown);
    }

    @Test public void showsOnlyUserVisibleStatesWhileSessionIsActive() {
        FakeSource source = new FakeSource();
        List<RadarBaseUpdateState> shown = new ArrayList<>();
        CarRadarBaseUpdateNotifier notifier =
                new CarRadarBaseUpdateNotifier(source, shown::add);
        notifier.start();

        RadarBaseUpdateState idle = state(
                0, RadarBaseUpdateState.Status.IDLE, "");
        RadarBaseUpdateState unchanged = state(
                2, RadarBaseUpdateState.Status.UNCHANGED, "Без изменений");
        RadarBaseUpdateState success = state(
                3, RadarBaseUpdateState.Status.SUCCESS, "Готово");
        RadarBaseUpdateState error = state(
                4, RadarBaseUpdateState.Status.ERROR, "Ошибка");
        source.emit(idle);
        source.emit(unchanged);
        source.emit(success);
        source.emit(error);

        assertEquals(Arrays.asList(unchanged, success, error), shown);
        notifier.stop();
        assertEquals(1, source.removeCount);
        assertFalse(source.hasListener());

        source.emitDirect(error);
        assertEquals(Arrays.asList(unchanged, success, error), shown);
    }

    private static RadarBaseUpdateState state(
            long sequence, RadarBaseUpdateState.Status status, String message) {
        return new RadarBaseUpdateState(sequence, status, 0, false, message);
    }

    private static final class FakeSource
            implements CarRadarBaseUpdateNotifier.Source {
        RadarBaseUpdater.Listener listener;
        RadarBaseUpdater.Listener lastListener;
        int addCount;
        int removeCount;
        boolean replayLatest;

        @Override public void addListener(
                RadarBaseUpdater.Listener listener, boolean replayLatest) {
            this.listener = listener;
            lastListener = listener;
            this.replayLatest = replayLatest;
            addCount++;
        }

        @Override public void removeListener(RadarBaseUpdater.Listener listener) {
            assertSame(this.listener, listener);
            this.listener = null;
            removeCount++;
        }

        boolean hasListener() {
            return listener != null;
        }

        void emit(RadarBaseUpdateState state) {
            if (listener != null) listener.onRadarBaseUpdate(state);
        }

        void emitDirect(RadarBaseUpdateState state) {
            lastListener.onRadarBaseUpdate(state);
        }
    }
}
