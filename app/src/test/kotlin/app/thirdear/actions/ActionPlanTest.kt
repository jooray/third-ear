package app.thirdear.actions

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import app.thirdear.actions.ActionPlan.Delivery
import app.thirdear.actions.ActionPlan.Step
import org.junit.Test

class ActionPlanTest {

    private fun plan(d: Destination, c: Content = Content.AUDIO, transcribe: Boolean = false, copy: Boolean = false) =
        ActionPlan.of(RecordAction(
            name = "x",
            destination = d,
            content = c,
            transcribe = transcribe,
            copyTranscript = copy,
            groupId = "g",
            webhookUrl = "https://h",
        ))

    @Test
    fun `save only does nothing but save`() {
        val p = plan(Destination.SAVE)
        assertFalse(p.transcribe)
        assertFalse(p.copyTranscript)
        assertEquals(listOf(Step.SAVE), p.steps)
        assertEquals(Delivery.Nothing, p.delivery("words"))
    }

    @Test
    fun `save with a transcript transcribes without sending`() {
        val p = plan(Destination.SAVE, transcribe = true)
        assertTrue(p.transcribe)
        assertEquals(listOf(Step.SAVE, Step.TRANSCRIBE), p.steps)
        assertEquals(Delivery.Nothing, p.delivery("words"))
    }

    @Test
    fun `copy transcript implies transcribing and copying`() {
        val p = plan(Destination.COPY_TRANSCRIPT)
        assertTrue(p.transcribe)
        assertTrue(p.copyTranscript)
        assertEquals(listOf(Step.SAVE, Step.TRANSCRIBE, Step.COPY), p.steps)
    }

    @Test
    fun `white noise audio sends the voice note without caption and without transcribing`() {
        val p = plan(Destination.WHITE_NOISE)
        assertFalse(p.transcribe)
        assertEquals(listOf(Step.SAVE, Step.WHITE_NOISE), p.steps)
        assertEquals(Delivery.Audio(null), p.delivery(null))
    }

    @Test
    fun `white noise audio and transcript captions the voice note, or sends it bare when transcription failed`() {
        val p = plan(Destination.WHITE_NOISE, Content.AUDIO_AND_TRANSCRIPT)
        assertTrue(p.transcribe)
        assertEquals(Delivery.Audio("hello"), p.delivery("hello"))
        assertEquals(Delivery.Audio(null), p.delivery(null))
    }

    @Test
    fun `transcript only sends text, and falls back to audio rather than nothing`() {
        val p = plan(Destination.WHITE_NOISE, Content.TRANSCRIPT)
        assertTrue(p.transcribe)
        assertEquals(Delivery.Text("hello"), p.delivery("hello"))
        assertEquals(Delivery.Audio(null), p.delivery(null))
        assertEquals(Delivery.Audio(null), p.delivery("  "))
    }

    @Test
    fun `share and webhook follow the same content rules`() {
        assertEquals(Delivery.Text("t"), plan(Destination.SHARE, Content.TRANSCRIPT).delivery("t"))
        assertEquals(Delivery.Audio("t"), plan(Destination.WEBHOOK, Content.AUDIO_AND_TRANSCRIPT).delivery("t"))
        assertEquals(listOf(Step.SAVE, Step.TRANSCRIBE, Step.WEBHOOK, Step.COPY), plan(Destination.WEBHOOK, Content.TRANSCRIPT, copy = true).steps)
    }

    @Test
    fun `problems name what is missing`() {
        val noGroup = ActionPlan.of(RecordAction(name = "a", destination = Destination.WHITE_NOISE))
        assertEquals(listOf(ActionPlan.Problem.NO_GROUP), noGroup.problems(transcriberAvailable = true))
        val badUrl = ActionPlan.of(RecordAction(name = "a", destination = Destination.WEBHOOK, webhookUrl = "ftp://x"))
        assertEquals(listOf(ActionPlan.Problem.NO_WEBHOOK_URL), badUrl.problems(true))
        val noTranscriber = ActionPlan.of(RecordAction(name = " ", destination = Destination.COPY_TRANSCRIPT))
        assertEquals(listOf(ActionPlan.Problem.NO_NAME, ActionPlan.Problem.NO_TRANSCRIBER), noTranscriber.problems(false))
        assertTrue(plan(Destination.SAVE).problems(false).isEmpty())
    }

    @Test
    fun `http urls`() {
        assertTrue(ActionPlan.isHttpsUrl("https://example.org/hook"))
        assertTrue(ActionPlan.isHttpsUrl(" https://192.168.1.2:8123/api "))
        assertFalse(ActionPlan.isHttpsUrl("http://192.168.1.2:8123/api"))
        assertFalse(ActionPlan.isHttpsUrl("https://"))
        assertFalse(ActionPlan.isHttpsUrl(null))
        assertFalse(ActionPlan.isHttpsUrl("example.org"))
    }

    @Test
    fun `a webhook without a scheme is https, plain http needs the user's confirmation`() {
        assertEquals("https://example.org/hook", ActionPlan.webhookUrl(" example.org/hook "))
        assertEquals("http://100.64.0.2:8080/x", ActionPlan.webhookUrl("http://100.64.0.2:8080/x"))
        val bare = RecordAction(name = "a", destination = Destination.WEBHOOK, webhookUrl = "example.org/hook")
        assertTrue(ActionPlan.of(bare).problems(true).isEmpty())
        assertEquals("https://example.org/hook", ActionPlan.webhookTarget(bare))

        val http = RecordAction(name = "a", destination = Destination.WEBHOOK, webhookUrl = "http://100.64.0.2:8080/x")
        assertEquals(listOf(ActionPlan.Problem.WEBHOOK_CLEARTEXT), ActionPlan.of(http).problems(true))
        assertNull(ActionPlan.webhookTarget(http))
        val confirmed = http.copy(webhookCleartext = true)
        assertTrue(ActionPlan.of(confirmed).problems(true).isEmpty())
        assertEquals("http://100.64.0.2:8080/x", ActionPlan.webhookTarget(confirmed))
        assertEquals(confirmed, RecordAction.fromJson(confirmed.toJson()))
    }
}
