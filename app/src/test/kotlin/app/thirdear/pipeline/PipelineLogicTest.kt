package app.thirdear.pipeline

import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import app.thirdear.actions.Content
import app.thirdear.actions.Destination
import app.thirdear.actions.RecordAction
import app.thirdear.audio.Container
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PipelineLogicTest {

    @get:Rule
    val tmp = TemporaryFolder()

    class Busy : Exception("busy")

    @Test
    fun `backoff follows I Hear You's schedule and gives up after it`() {
        val b = Backoff()
        assertEquals(listOf(5_000L, 15_000L, 30_000L, 60_000L, 120_000L), (0..4).map { b.delayMs(it) })
        assertNull(b.delayMs(8))
        assertEquals(590_000L, b.totalMs)
    }

    @Test
    fun `backoff retries busy failures and returns the first success`() = runTest {
        val slept = mutableListOf<Long>()
        var calls = 0
        val result = Backoff().run(
            retryable = { it is Busy },
            onWait = { _, _, _ -> },
            sleep = { slept += it },
        ) {
            if (++calls < 3) throw Busy()
            "text"
        }
        assertEquals("text", result)
        assertEquals(listOf(5_000L, 15_000L), slept)
    }

    @Test
    fun `backoff does not retry other failures, and stops when the schedule runs out`() = runTest {
        assertFailsWith<IllegalStateException> {
            Backoff().run({ it is Busy }, { _, _, _ -> }, {}) { error("no model") }
        }
        var calls = 0
        assertFailsWith<Busy> {
            Backoff(listOf(1, 1)).run({ it is Busy }, { _, _, _ -> }, {}) {
                calls++
                throw Busy()
            }
        }
        assertEquals(3, calls)
    }

    @Test
    fun `resampler halves 32 kHz to 16 kHz and keeps a tone`() {
        val r = MonoResampler(32_000, 1, 16_000)
        val input = ShortArray(3_200) { (sin(2 * PI * 440 * it / 32_000) * 10_000).toInt().toShort() }
        val out = r.process(input, input.size)
        assertTrue(abs(out.size - 1_600) <= 1, "size ${out.size}")
        for (i in out.indices) assertEquals(input[i * 2].toInt(), out[i].toInt())
    }

    @Test
    fun `resampler mixes stereo to mono and stays continuous across blocks`() {
        val r = MonoResampler(48_000, 2, 16_000)
        var total = 0
        repeat(10) {
            val block = ShortArray(960) { i -> if (i % 2 == 0) 1000 else 3000 } // 480 frames
            val out = r.process(block, block.size)
            total += out.size
            out.forEach { assertEquals(2000, it.toInt()) }
        }
        // 4800 frames at 48 kHz are 1600 at 16 kHz
        assertTrue(abs(total - 1_600) <= 1, "total $total")
    }

    @Test
    fun `note json keeps the action snapshot and state`() {
        val n = Note(
            id = "a-1", startedAt = 123, action = RecordAction(id = "x", name = "Fam", destination = Destination.WHITE_NOISE, content = Content.TRANSCRIPT, groupId = "g"),
            container = Container.OGG, baseName = "20261029_101010", durationMs = 4_000, mic = "bluetooth: Aids",
            savedUri = "content://x", savedName = "20261029_101010.ogg", transcript = true, transcriptError = null,
            state = NoteState.FAILED, step = NoteStep.SENDING, error = "HTTP 500", recovered = false,
        )
        assertEquals(n, Note.fromJson(org.json.JSONObject(n.toJson().toString())))
        assertEquals("20261029_101010.ogg", n.fileName)
    }

    @Test
    fun `note store lists newest first and finds unfinished notes`() {
        val store = NoteStore(File(tmp.root, "notes"))
        val a = RecordAction(name = "a")
        store.put(Note("1", 100, a, Container.MPEG_4, "one", state = NoteState.DONE, savedName = "one.m4a"))
        store.put(Note("2", 200, a, Container.MPEG_4, "two", state = NoteState.RECORDING))
        store.put(Note("3", 300, a, Container.MP3, "three", state = NoteState.PROCESSING))
        assertEquals(listOf("3", "2", "1"), NoteStore.notes.value.map { it.id })
        assertEquals(setOf("2", "3"), store.unfinished().map { it.id }.toSet())
        assertEquals("1", store.bySavedName("one.m4a")!!.id)
        store.transcriptFile("1").writeText("hello")
        assertEquals("hello", store.transcript("1"))
        assertEquals("work.mp3", store.work(store.get("3")!!).name)
        assertEquals("work.tej", store.work(store.get("2")!!).name)
        store.delete("2")
        assertNull(store.get("2"))
    }

    @Test
    fun `renaming a recording carries its note and transcript name along`() {
        assertEquals("2026 meeting.txt", NoteStore.transcriptName("2026 meeting.m4a"))
        assertEquals("a.b.txt", NoteStore.transcriptName("a.b.ogg"))
        assertEquals("noext.txt", NoteStore.transcriptName("noext"))
        val store = NoteStore(File(tmp.root, "notes2"))
        store.put(Note("r1", 100, RecordAction(name = "a"), Container.MPEG_4, "old", state = NoteState.DONE, savedName = "old.m4a", savedUri = "content://old"))
        store.transcriptFile("r1").writeText("words")
        store.renamed("old.m4a", "new name.m4a", "content://new")
        val n = store.bySavedName("new name.m4a")!!
        assertEquals("content://new", n.savedUri)
        assertEquals("new name", n.baseName)
        assertEquals("words", store.transcript(n.id))
        assertNull(store.bySavedName("old.m4a"))
    }
}
