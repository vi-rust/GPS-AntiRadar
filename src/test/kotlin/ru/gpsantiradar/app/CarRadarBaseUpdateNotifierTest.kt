package ru.gpsantiradar.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue

import org.junit.Test

import java.util.ArrayList
import java.util.Arrays

 class CarRadarBaseUpdateNotifierTest {
@Test
fun startReplaysActiveColdUpdateAndDoesNotRegisterTwice() {
val source = FakeSource()
val shown = ArrayList<RadarBaseUpdateState>()
val notifier = CarRadarBaseUpdateNotifier(source, CarRadarBaseUpdateNotifier.MessageSink { shown.add(it) })
val started = state(
1, RadarBaseUpdateState.Status.STARTED, "Начато")
source.latest = started

notifier.start()
notifier.start()

assertTrue(source.replayLatest)
assertEquals(1, source.addCount)
assertEquals(Arrays.asList(started), shown)
}

@Test
fun startDoesNotReplayTerminalStateFromAnOlderSession() {
val source = FakeSource()
val shown = ArrayList<RadarBaseUpdateState>()
val stale = state(
8, RadarBaseUpdateState.Status.SUCCESS, "Старый результат")
source.latest = stale
val notifier = CarRadarBaseUpdateNotifier(source, CarRadarBaseUpdateNotifier.MessageSink { shown.add(it) })

notifier.start()

assertTrue(shown.isEmpty())
val started = state(
9, RadarBaseUpdateState.Status.STARTED, "Новое обновление")
val success = state(
10, RadarBaseUpdateState.Status.SUCCESS, "Новый результат")
source.emit(started)
source.emit(success)
assertEquals(Arrays.asList(started, success), shown)
}

@Test
fun showsOnlyUserVisibleStatesWhileSessionIsActive() {
val source = FakeSource()
val shown = ArrayList<RadarBaseUpdateState>()
val notifier = CarRadarBaseUpdateNotifier(source, CarRadarBaseUpdateNotifier.MessageSink { shown.add(it) })
notifier.start()

val idle = state(
0, RadarBaseUpdateState.Status.IDLE, "")
val unchanged = state(
2, RadarBaseUpdateState.Status.UNCHANGED, "Без изменений")
val success = state(
3, RadarBaseUpdateState.Status.SUCCESS, "Готово")
val error = state(
4, RadarBaseUpdateState.Status.ERROR, "Ошибка")
source.emit(idle)
source.emit(unchanged)
source.emit(success)
source.emit(error)

assertEquals(Arrays.asList(unchanged, success, error), shown)
notifier.stop()
assertEquals(1, source.removeCount)
assertFalse(source.hasListener())

source.emitDirect(error)
assertEquals(Arrays.asList(unchanged, success, error), shown)
}

private class FakeSource:CarRadarBaseUpdateNotifier.Source {
 var listener:RadarBaseUpdater.Listener? = null
 var lastListener:RadarBaseUpdater.Listener? = null
 var addCount:Int = 0
 var removeCount:Int = 0
 var replayLatest:Boolean = false
 var latest:RadarBaseUpdateState? = state(
0, RadarBaseUpdateState.Status.IDLE, "")

override fun latestState():RadarBaseUpdateState? {
return latest
}

override fun addListener(
listener:RadarBaseUpdater.Listener, replayLatest:Boolean) {
this.listener = listener
lastListener = listener
this.replayLatest = replayLatest
addCount++
if (replayLatest) listener.onRadarBaseUpdate(requireNotNull(latest))
}

override fun removeListener(listener:RadarBaseUpdater.Listener) {
assertSame(this.listener, listener)
this.listener = null
removeCount++
}

 fun hasListener():Boolean {
return listener != null
}

 fun emit(state:RadarBaseUpdateState) {
latest = state
if (listener != null) listener!!.onRadarBaseUpdate(state)
}

 fun emitDirect(state:RadarBaseUpdateState) {
lastListener!!.onRadarBaseUpdate(state)
}
}

companion object {

private fun state(
sequence:Long, status:RadarBaseUpdateState.Status, message:String):RadarBaseUpdateState {
return RadarBaseUpdateState(sequence, status, 0, false, message)
}
}
}
