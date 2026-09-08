package ru.gpsantiradar.app

class CarRadarBaseUpdateNotifier {
    interface Source {
        fun latestState(): RadarBaseUpdateState?
        fun addListener(listener: RadarBaseUpdater.Listener, replayLatest: Boolean)
        fun removeListener(listener: RadarBaseUpdater.Listener)
    }
    fun interface MessageSink { fun show(state: RadarBaseUpdateState) }
    private val source: Source
    private val messageSink: MessageSink
    private var started = false
    private var terminalSequenceAtStart = Long.MIN_VALUE
    private val listener = object : RadarBaseUpdater.Listener {
        override fun onRadarBaseUpdate(state: RadarBaseUpdateState) {
            if (started && state.status != RadarBaseUpdateState.Status.IDLE) {
                if (state.isTerminal() && state.sequence <= terminalSequenceAtStart) return
                messageSink.show(state)
            }
        }
    }
    constructor(updater: RadarBaseUpdater?, messageSink: MessageSink?) : this(SharedSource(requireNotNull(updater) { "updater is required" }), messageSink)
    constructor(source: Source?, messageSink: MessageSink?) {
        this.source = requireNotNull(source) { "source is required" }
        this.messageSink = requireNotNull(messageSink) { "messageSink is required" }
    }
    fun start() {
        if (started) return
        val latest = source.latestState()
        terminalSequenceAtStart = if (latest?.isTerminal() == true) latest.sequence else Long.MIN_VALUE
        started = true
        source.addListener(listener, true)
    }
    fun stop() {
        if (!started) return
        started = false
        source.removeListener(listener)
    }
    private class SharedSource(private val updater: RadarBaseUpdater) : Source {
        override fun latestState() = updater.latestState()
        override fun addListener(listener: RadarBaseUpdater.Listener, replayLatest: Boolean) = updater.addListener(listener, replayLatest)
        override fun removeListener(listener: RadarBaseUpdater.Listener) = updater.removeListener(listener)
    }
}
