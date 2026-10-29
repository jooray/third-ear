package org.fossify.voicerecorder.car

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.fossify.voicerecorder.actions.RecordAction
import org.fossify.voicerecorder.audio.EngineStatus
import org.fossify.voicerecorder.audio.RoutedInput
import org.fossify.voicerecorder.audio.Source
import org.junit.Test

class CarModelTest {

    private val actions = (1..8).map { RecordAction(id = "a$it", name = "Action $it") }

    @Test
    fun `grid shows the first actions in order, capped for the head unit`() {
        val g = CarModel.grid(actions)
        assertEquals(CarModel.MAX_GRID, g.size)
        assertEquals("a1", g.first().actionId)
        assertEquals("Action 1", g.first().title)
    }

    @Test
    fun `stop rows put the started action first and say what each does`() {
        val rows = CarModel.stopRows(actions.take(3), "a3", { "does ${it.name}" }) { "Stop → $it" }
        assertEquals(listOf("a3", "a1", "a2"), rows.map { it.actionId })
        assertTrue(rows.first().startedWith)
        assertEquals("Stop → Action 3", rows.first().title)
        assertEquals("does Action 3", rows.first().subtitle)
    }

    @Test
    fun `duration text`() {
        assertEquals("0:07", CarModel.duration(7_900))
        assertEquals("12:05", CarModel.duration(725_000))
        assertEquals("1:00:01", CarModel.duration(3_601_000))
    }

    @Test
    fun `refresh on state changes, otherwise every few seconds`() {
        val a = EngineStatus(starting = false, source = Source.BLUETOOTH, waitingForBluetooth = true)
        assertTrue(CarModel.needsRefresh(null, a, 0, 0))
        assertFalse(CarModel.needsRefresh(a, a.copy(samples = 48_000), 1_000, 2_000))
        assertTrue(CarModel.needsRefresh(a, a.copy(waitingForBluetooth = false, input = RoutedInput(true, "Car")), 1_000, 1_100))
        assertTrue(CarModel.needsRefresh(a, a.copy(source = Source.PHONE), 1_000, 1_100))
        assertTrue(CarModel.needsRefresh(a, a.copy(paused = true), 1_000, 1_100))
        assertTrue(CarModel.needsRefresh(a, a, 1_000, 1_000 + CarModel.REFRESH_MS))
    }
}
