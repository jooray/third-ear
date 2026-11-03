package app.thirdear.car

import kotlin.test.assertEquals
import app.thirdear.actions.RecordAction
import app.thirdear.car.CarMedia.Command
import org.junit.Test

class CarMediaTest {

    private val actions = (1..10).map { RecordAction(id = "a$it", name = "Action $it") }

    private fun items(startedWith: String?) =
        CarMedia.recordItems(actions, startedWith, { "does ${it.name}" }) { "Stop → $it" }

    @Test
    fun `idle tab lists the lines in order, capped, without a choose-later entry`() {
        val i = items(null)
        assertEquals(CarMedia.MAX_LINES, i.size)
        assertEquals(CarMedia.recordId("a1"), i[0].mediaId)
        assertEquals("Action 1", i[0].title)
        assertEquals("does Action 1", i[0].subtitle)
        assertEquals(0, i[0].actionIndex)
    }

    @Test
    fun `recording tab lists stop rows with the started line first and keeps colour indexes`() {
        val i = items("a3")
        assertEquals("Stop → Action 3", i[0].title)
        assertEquals(2, i[0].actionIndex)
        assertEquals("Stop → Action 1", i[1].title)
        assertEquals(CarMedia.MAX_LINES, i.size)
    }

    @Test
    fun `a tap records when idle and stops to that line while recording`() {
        assertEquals(Command.Start("a2"), CarMedia.onTap(CarMedia.recordId("a2"), recording = false))
        assertEquals(Command.Start(null), CarMedia.onTap(CarMedia.recordId(null), recording = false))
        assertEquals(Command.Stop("a2"), CarMedia.onTap(CarMedia.recordId("a2"), recording = true))
        assertEquals(Command.PlayMessage("m1"), CarMedia.onTap(CarMedia.messageId("m1"), recording = false))
        assertEquals(Command.None, CarMedia.onTap(CarMedia.TAB_RECORD, recording = false))
        assertEquals(Command.None, CarMedia.onTap(null, recording = false))
    }

    @Test
    fun `play never starts a recording, it only resumes`() {
        assertEquals(Command.None, CarMedia.onPlay(recording = false, paused = false))
        assertEquals(Command.TogglePause, CarMedia.onPlay(recording = true, paused = true))
        assertEquals(Command.None, CarMedia.onPlay(recording = true, paused = false))
        assertEquals(Command.TogglePause, CarMedia.onPause(recording = true, paused = false))
        assertEquals(Command.None, CarMedia.onPause(recording = true, paused = true))
        assertEquals(Command.None, CarMedia.onPause(recording = false, paused = false))
    }

    @Test
    fun `stop, next and the custom buttons act only while recording`() {
        assertEquals(Command.Stop(null), CarMedia.onStop(recording = true))
        assertEquals(Command.None, CarMedia.onStop(recording = false))
        assertEquals(Command.Stop(null), CarMedia.onCustom(CarMedia.CUSTOM_SAVE, recording = true))
        assertEquals(Command.Discard, CarMedia.onCustom(CarMedia.CUSTOM_DISCARD, recording = true))
        assertEquals(Command.None, CarMedia.onCustom(CarMedia.CUSTOM_DISCARD, recording = false))
        assertEquals(Command.None, CarMedia.onCustom("other", recording = true))
    }
}
