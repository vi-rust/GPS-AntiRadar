package ru.gpsantiradar.app;

final class CarRadarBaseUpdateNotifier {
    interface Source {
        RadarBaseUpdateState latestState();
        void addListener(RadarBaseUpdater.Listener listener, boolean replayLatest);
        void removeListener(RadarBaseUpdater.Listener listener);
    }

    interface MessageSink {
        void show(RadarBaseUpdateState state);
    }

    private final Source source;
    private final MessageSink messageSink;
    private boolean started;
    private long terminalSequenceAtStart = Long.MIN_VALUE;

    private final RadarBaseUpdater.Listener listener =
            new RadarBaseUpdater.Listener() {
                @Override public void onRadarBaseUpdate(RadarBaseUpdateState state) {
                    if (started && state != null
                            && state.status != RadarBaseUpdateState.Status.IDLE) {
                        if (state.isTerminal()
                                && state.sequence <= terminalSequenceAtStart) {
                            return;
                        }
                        messageSink.show(state);
                    }
                }
            };

    CarRadarBaseUpdateNotifier(RadarBaseUpdater updater, MessageSink messageSink) {
        this(new SharedSource(updater), messageSink);
    }

    CarRadarBaseUpdateNotifier(Source source, MessageSink messageSink) {
        if (source == null) throw new IllegalArgumentException("source is required");
        if (messageSink == null) {
            throw new IllegalArgumentException("messageSink is required");
        }
        this.source = source;
        this.messageSink = messageSink;
    }

    void start() {
        if (started) return;
        RadarBaseUpdateState latest = source.latestState();
        terminalSequenceAtStart = latest != null && latest.isTerminal()
                ? latest.sequence : Long.MIN_VALUE;
        started = true;
        source.addListener(listener, true);
    }

    void stop() {
        if (!started) return;
        started = false;
        source.removeListener(listener);
    }

    private static final class SharedSource implements Source {
        private final RadarBaseUpdater updater;

        SharedSource(RadarBaseUpdater updater) {
            if (updater == null) {
                throw new IllegalArgumentException("updater is required");
            }
            this.updater = updater;
        }

        @Override public RadarBaseUpdateState latestState() {
            return updater.latestState();
        }

        @Override public void addListener(
                RadarBaseUpdater.Listener listener, boolean replayLatest) {
            updater.addListener(listener, replayLatest);
        }

        @Override public void removeListener(RadarBaseUpdater.Listener listener) {
            updater.removeListener(listener);
        }
    }
}
