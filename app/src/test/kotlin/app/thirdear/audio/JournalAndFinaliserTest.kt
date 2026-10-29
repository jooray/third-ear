package app.thirdear.audio

import java.io.File
import java.io.RandomAccessFile
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class JournalAndFinaliserTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val format = JournalFormat("audio/mp4a-latm", 48_000, 1, 96_000, listOf(byteArrayOf(0x11, 0x90.toByte())))

    private fun writeJournal(f: File, frames: Int): File {
        FrameJournal.Writer(f).use { w ->
            w.format(format)
            for (i in 0 until frames) {
                val data = ByteArray(10 + i) { i.toByte() }
                w.sample(data, 0, data.size, i * 21_333L, if (i == 0) 1 else 0)
            }
        }
        return f
    }

    class FakeMuxer : Muxer {
        var format: JournalFormat? = null
        val samples = mutableListOf<Pair<Long, ByteArray>>()
        var started = false
        var stopped = false
        var released = false
        override fun addTrack(format: JournalFormat): Int {
            this.format = format
            return 0
        }

        override fun start() {
            started = true
        }

        override fun write(track: Int, data: ByteArray, ptsUs: Long, flags: Int) {
            samples += ptsUs to data
        }

        override fun stop() {
            stopped = true
        }

        override fun release() {
            released = true
        }
    }

    @Test
    fun `journal round trip keeps format and every frame`() {
        val f = writeJournal(tmp.newFile("a.tej"), 50)
        var read: JournalFormat? = null
        val samples = mutableListOf<JournalSample>()
        val n = FrameJournal.read(f, { read = it }, { samples += it })
        assertEquals(50, n)
        assertEquals(format, read)
        assertEquals(49 * 21_333L, samples.last().ptsUs)
        assertContentEquals(ByteArray(59) { 49 }, samples.last().data)
        assertEquals(1, samples.first().flags)
        assertTrue(FrameJournal.isJournal(f))
    }

    @Test
    fun `a journal cut mid-record (process killed) reads up to the last complete frame`() {
        val f = writeJournal(tmp.newFile("b.tej"), 20)
        RandomAccessFile(f, "rw").use { it.setLength(it.length() - 7) }
        val samples = mutableListOf<JournalSample>()
        FrameJournal.read(f, {}, { samples += it })
        assertEquals(19, samples.size)
    }

    @Test
    fun `finaliser muxes every frame of the journal`() {
        val f = writeJournal(tmp.newFile("c.tej"), 100)
        val mux = FakeMuxer()
        val out = File(tmp.root, "out.m4a")
        val r = Finaliser.finalise(f, out, Container.MPEG_4) { _, _ -> mux }
        assertEquals(format, mux.format)
        assertEquals(100, mux.samples.size)
        assertTrue(mux.started && mux.stopped && mux.released)
        assertEquals(100L, r.frames)
        // 99 frames × 21.333 ms plus the last frame's own length
        assertEquals(99 * 21_333L / 1000 + 1024_000L / 48_000, r.durationMs)
    }

    @Test
    fun `finaliser recovers a truncated journal`() {
        val f = writeJournal(tmp.newFile("d.tej"), 30)
        RandomAccessFile(f, "rw").use { it.setLength(it.length() - 3) }
        val mux = FakeMuxer()
        Finaliser.finalise(f, File(tmp.root, "d.m4a"), Container.MPEG_4) { _, _ -> mux }
        assertEquals(29, mux.samples.size)
        assertTrue(mux.stopped)
    }

    @Test
    fun `out of order frames at the tail are dropped`() {
        val f = tmp.newFile("e.tej")
        FrameJournal.Writer(f).use { w ->
            w.format(format)
            w.sample(byteArrayOf(1), 0, 1, 0, 0)
            w.sample(byteArrayOf(2), 0, 1, 20_000, 0)
            w.sample(byteArrayOf(3), 0, 1, 10_000, 0)
        }
        val mux = FakeMuxer()
        Finaliser.finalise(f, File(tmp.root, "e.m4a"), Container.MPEG_4) { _, _ -> mux }
        assertEquals(listOf(0L, 20_000L), mux.samples.map { it.first })
    }

    @Test
    fun `a journal without a format (encoder never produced output) is an error`() {
        val f = tmp.newFile("f.tej")
        FrameJournal.Writer(f).close()
        assertFailsWith<IllegalStateException> {
            Finaliser.finalise(f, File(tmp.root, "f.m4a"), Container.MPEG_4) { _, _ -> FakeMuxer() }
        }
    }

    @Test
    fun `mp3 needs no container and its duration comes from the frame header`() {
        val f = tmp.newFile("g.mp3")
        // MPEG-1 layer III, 128 kbps (index 9), 44.1 kHz header, then 16000 bytes = 1 s
        val header = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64)
        f.writeBytes(header + ByteArray(16_000 - 4))
        val out = File(tmp.root, "g-out.mp3")
        val r = Finaliser.finalise(f, out, Container.MP3)
        assertEquals(1000L, r.durationMs)
        assertEquals(16_000L, out.length())
    }
}
