package org.fossify.voicerecorder.audio

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class RecordingEngineTest {

    private val rate = 16_000
    private val block = rate / 10

    private class Opened(val kind: Source, val source: FakeSource?)

    private fun engine(
        clock: FakeClock,
        sink: FakeSink,
        listener: RecordingListener,
        preferBluetooth: Boolean,
        btAvailable: Boolean = true,
        open: (Source, Int) -> FakeSource?,
    ): Pair<RecordingEngine, MutableList<Opened>> {
        val opened = mutableListOf<Opened>()
        var n = 0
        val factory = PcmSourceFactory { kind, _, _ ->
            open(kind, n++).also { opened += Opened(kind, it) }
        }
        val e = RecordingEngine(factory, sink, rate, preferBluetooth, { btAvailable }, listener, clock = clock::get)
        return e to opened
    }

    @Test
    fun `phone recording writes every block and finishes the sink`() {
        val clock = FakeClock()
        val sink = FakeSink()
        val l = RecordingListener()
        lateinit var e: RecordingEngine
        val pair = engine(clock, sink, l, preferBluetooth = false) { kind, _ ->
            FakeSource(kind, clock, List(20) { 1 }, onRead = { if (it == 9) e.stop() })
        }
        e = pair.first
        e.runLoop()
        assertEquals(10L * block, sink.samplesWritten)
        assertTrue(sink.finished)
        assertNull(l.failure)
        assertEquals(listOf(Source.PHONE), pair.second.map { it.kind })
        assertTrue(pair.second.single().source!!.closed)
    }

    @Test
    fun `bluetooth that never sounds falls back to the phone once, with a cue, into the same sink`() {
        val clock = FakeClock()
        val sink = FakeSink()
        val l = RecordingListener()
        lateinit var e: RecordingEngine
        val pair = engine(clock, sink, l, preferBluetooth = true) { kind, _ ->
            when (kind) {
                // an LE Audio input that only ever delivers exact zeros (the Pixel HAL case)
                Source.BLUETOOTH -> FakeSource(kind, clock, emptyList(), tail = 2)
                Source.PHONE -> FakeSource(kind, clock, List(5) { 1 }, onRead = { if (it == 4) e.stop() }, value = 7)
            }
        }
        e = pair.first
        e.runLoop()
        assertEquals(listOf(Source.BLUETOOTH, Source.PHONE), pair.second.map { it.kind })
        assertEquals(listOf(SourcePolicy.Cue.FALLBACK_TO_PHONE), l.cues)
        assertTrue(pair.second[0].source!!.closed, "the Bluetooth input is released")
        // zeros while waiting (80 blocks = the 8 s deadline) plus the phone's five blocks, one file
        assertEquals(7.toShort(), sink.values.last())
        assertTrue(sink.samplesWritten >= 5L * block)
        assertTrue(l.statuses.any { it.waitingForBluetooth }, "the UI saw the waiting state")
        assertFalse(l.statuses.last().waitingForBluetooth)
        assertNull(l.failure)
    }

    @Test
    fun `bluetooth that comes up late is not reopened`() {
        val clock = FakeClock()
        val sink = FakeSink()
        val l = RecordingListener()
        lateinit var e: RecordingEngine
        // 3 s of nothing, 1 s of zeros, then sound: typical LE Audio start
        val script = List(30) { 0 } + List(10) { 2 } + List(10) { 1 }
        val pair = engine(clock, sink, l, preferBluetooth = true) { kind, _ ->
            FakeSource(kind, clock, script, onRead = { if (it == script.size - 1) e.stop() })
        }
        e = pair.first
        e.runLoop()
        assertEquals(listOf(Source.BLUETOOTH), pair.second.map { it.kind })
        assertTrue(l.cues.isEmpty())
        assertEquals(20L * block, sink.samplesWritten)
    }

    @Test
    fun `no bluetooth microphone connected records from the phone without a cue`() {
        val clock = FakeClock()
        val sink = FakeSink()
        val l = RecordingListener()
        lateinit var e: RecordingEngine
        val pair = engine(clock, sink, l, preferBluetooth = true, btAvailable = false) { kind, _ ->
            FakeSource(kind, clock, List(3) { 1 }, onRead = { if (it == 2) e.stop() })
        }
        e = pair.first
        e.runLoop()
        assertEquals(listOf(Source.PHONE), pair.second.map { it.kind })
        assertTrue(l.cues.isEmpty())
        assertEquals("no Bluetooth microphone connected", l.statuses.last().fallbackReason)
    }

    @Test
    fun `bluetooth route that cannot be opened falls back with a cue`() {
        val clock = FakeClock()
        val sink = FakeSink()
        val l = RecordingListener()
        lateinit var e: RecordingEngine
        val pair = engine(clock, sink, l, preferBluetooth = true) { kind, _ ->
            if (kind == Source.BLUETOOTH) null else FakeSource(kind, clock, List(3) { 1 }, onRead = { if (it == 2) e.stop() })
        }
        e = pair.first
        e.runLoop()
        assertEquals(listOf(Source.BLUETOOTH, Source.PHONE), pair.second.map { it.kind })
        assertEquals(1, l.cues.size)
    }

    @Test
    fun `user switch mid-recording continues into the same sink`() {
        val clock = FakeClock()
        val sink = FakeSink()
        val l = RecordingListener()
        lateinit var e: RecordingEngine
        val pair = engine(clock, sink, l, preferBluetooth = false) { kind, _ ->
            when (kind) {
                Source.PHONE -> FakeSource(kind, clock, List(10) { 1 }, onRead = { if (it == 4) e.switchTo(Source.BLUETOOTH) }, value = 1)
                Source.BLUETOOTH -> FakeSource(kind, clock, List(10) { 1 }, onRead = { if (it == 4) e.stop() }, value = 2)
            }
        }
        e = pair.first
        e.runLoop()
        assertEquals(listOf(Source.PHONE, Source.BLUETOOTH), pair.second.map { it.kind })
        assertEquals(10L * block, sink.samplesWritten)
        assertEquals(listOf<Short>(1, 1, 1, 1, 1, 2, 2, 2, 2, 2), sink.values)
        assertTrue(l.cues.isEmpty(), "a switch the user asked for plays no fallback cue")
        assertTrue(sink.finished)
    }

    @Test
    fun `bluetooth disconnect after it started falls back to the phone`() {
        val clock = FakeClock()
        val sink = FakeSink()
        val l = RecordingListener()
        lateinit var e: RecordingEngine
        var bt: FakeSource? = null
        val events = mutableListOf<(SourceEvent) -> Unit>()
        val factory = PcmSourceFactory { kind, _, ev ->
            events += ev
            when (kind) {
                Source.BLUETOOTH -> FakeSource(kind, clock, List(10) { 1 }, onRead = {
                    if (it == 3) ev(SourceEvent.Routed(RoutedInput.PHONE))
                }).also { bt = it }

                Source.PHONE -> FakeSource(kind, clock, List(10) { 1 }, onRead = { if (it == 2) e.stop() })
            }
        }
        e = RecordingEngine(factory, sink, rate, true, { true }, l, clock = clock::get)
        e.runLoop()
        assertTrue(bt!!.closed)
        assertEquals(listOf(SourcePolicy.Cue.FALLBACK_TO_PHONE), l.cues)
        assertEquals(Source.PHONE, l.statuses.last().source)
        assertNull(l.failure)
    }

    @Test
    fun `pause keeps reading but drops the samples`() {
        val clock = FakeClock()
        val sink = FakeSink()
        val l = RecordingListener()
        lateinit var e: RecordingEngine
        val pair = engine(clock, sink, l, preferBluetooth = false) { kind, _ ->
            FakeSource(kind, clock, List(10) { 1 }, onRead = {
                when (it) {
                    2 -> e.setPaused(true)
                    5 -> e.setPaused(false)
                    8 -> e.stop()
                }
            })
        }
        e = pair.first
        e.runLoop()
        // reads 0-2 written (the pause applies before read 3), 3-5 dropped, 6-8 written
        assertEquals(6L * block, sink.samplesWritten)
        assertTrue(l.statuses.any { it.paused })
    }

    @Test
    fun `phone read error ends the recording and keeps what was captured`() {
        val clock = FakeClock()
        val sink = FakeSink()
        val l = RecordingListener()
        val pair = engine(clock, sink, l, preferBluetooth = false) { kind, _ ->
            FakeSource(kind, clock, listOf(1, 1, -6))
        }
        pair.first.runLoop()
        assertEquals(2L * block, sink.samplesWritten)
        assertTrue(sink.finished)
        assertTrue(l.failure!!.contains("input lost"))
    }

    @Test
    fun `discard aborts the sink`() {
        val clock = FakeClock()
        val sink = FakeSink()
        val l = RecordingListener()
        lateinit var e: RecordingEngine
        val pair = engine(clock, sink, l, preferBluetooth = false) { kind, _ ->
            FakeSource(kind, clock, List(5) { 1 }, onRead = { if (it == 1) e.stop(discardAudio = true) })
        }
        e = pair.first
        e.runLoop()
        assertTrue(sink.aborted)
        assertFalse(sink.finished)
    }

    @Test
    fun `stop requested while starting ends right after the source opened`() {
        val clock = FakeClock()
        val sink = FakeSink()
        val l = RecordingListener()
        lateinit var e: RecordingEngine
        val pair = engine(clock, sink, l, preferBluetooth = false) { kind, _ ->
            e.stop()
            FakeSource(kind, clock, List(5) { 1 })
        }
        e = pair.first
        e.runLoop()
        assertEquals(0L, sink.samplesWritten)
        assertTrue(sink.finished)
        assertEquals(0L, l.finishedSamples)
    }

    @Test
    fun `five seconds of exact zeros on the phone mark the recording silent and sound clears it`() {
        val clock = FakeClock()
        val sink = FakeSink()
        val l = RecordingListener()
        lateinit var e: RecordingEngine
        val script = listOf(1) + List(51) { 2 } + listOf(1, 1)
        val pair = engine(clock, sink, l, preferBluetooth = false) { kind, _ ->
            FakeSource(kind, clock, script, onRead = { if (it == script.size - 1) e.stop() })
        }
        e = pair.first
        e.runLoop()
        assertTrue(l.statuses.any { it.silent })
        assertFalse(l.statuses.last().silent)
    }
}
