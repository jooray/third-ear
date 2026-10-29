package app.thirdear.audio

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test

class SourcePolicyTest {

    private val bt = RoutedInput(true, "Aids")

    @Test
    fun `starts on bluetooth only when wanted and available`() {
        assertEquals(Source.BLUETOOTH, SourcePolicy().start(preferBluetooth = true, bluetoothAvailable = true))
        assertEquals(Source.PHONE, SourcePolicy().start(preferBluetooth = true, bluetoothAvailable = false))
        assertEquals(Source.PHONE, SourcePolicy().start(preferBluetooth = false, bluetoothAvailable = true))
    }

    @Test
    fun `waits generously for the first bluetooth sound, then falls back with a cue`() {
        val p = SourcePolicy(firstSoundTimeoutMs = 8_000)
        p.start(true, true)
        p.opened(Source.BLUETOOTH, bt, now = 0)
        assertTrue(p.waitingForBluetooth)
        assertEquals(SourcePolicy.Action.None, p.tick(3_500))
        assertEquals(SourcePolicy.Action.None, p.tick(7_999))
        val a = p.tick(8_000)
        assertIs<SourcePolicy.Action.Open>(a)
        assertEquals(Source.PHONE, a.source)
        assertEquals(SourcePolicy.Cue.FALLBACK_TO_PHONE, a.cue)
        assertTrue(p.fellBack)
    }

    @Test
    fun `first sound ends the wait`() {
        val p = SourcePolicy()
        p.start(true, true)
        p.opened(Source.BLUETOOTH, bt, 0)
        p.firstSound()
        assertFalse(p.waitingForBluetooth)
        assertEquals(SourcePolicy.Action.None, p.tick(60_000))
    }

    @Test
    fun `route moving off bluetooth while still starting is ignored, after sound it falls back`() {
        val p = SourcePolicy()
        p.start(true, true)
        p.opened(Source.BLUETOOTH, RoutedInput.PHONE, 0)
        // LE Audio reports routes late: no decision until it delivered sound or the deadline
        assertEquals(SourcePolicy.Action.None, p.routed(RoutedInput.PHONE))
        p.firstSound()
        val a = p.routed(RoutedInput.PHONE)
        assertIs<SourcePolicy.Action.Open>(a)
        assertEquals(Source.PHONE, a.source)
    }

    @Test
    fun `only the first automatic fallback has a cue`() {
        val p = SourcePolicy()
        p.start(true, true)
        p.opened(Source.BLUETOOTH, bt, 0)
        val first = p.readFailed("input lost") as SourcePolicy.Action.Open
        assertEquals(SourcePolicy.Cue.FALLBACK_TO_PHONE, first.cue)
        // pretend something reopened Bluetooth automatically; a second fallback is silent
        p.opened(Source.BLUETOOTH, bt, 0)
        val second = p.readFailed("input lost") as SourcePolicy.Action.Open
        assertEquals(null, second.cue)
    }

    @Test
    fun `user switch to bluetooth re-arms the fallback`() {
        val p = SourcePolicy()
        p.start(true, true)
        p.opened(Source.BLUETOOTH, bt, 0)
        p.readFailed("x")
        p.opened(Source.PHONE, RoutedInput.PHONE, 0)
        val a = p.userSwitch(Source.BLUETOOTH) as SourcePolicy.Action.Open
        assertEquals(Source.BLUETOOTH, a.source)
        assertEquals(null, a.cue)
        assertFalse(p.fellBack)
        p.opened(Source.BLUETOOTH, bt, 0)
        assertEquals(SourcePolicy.Cue.FALLBACK_TO_PHONE, (p.readFailed("x") as SourcePolicy.Action.Open).cue)
    }

    @Test
    fun `phone failure stops the recording`() {
        val p = SourcePolicy()
        p.start(false, false)
        p.opened(Source.PHONE, RoutedInput.PHONE, 0)
        assertIs<SourcePolicy.Action.Stop>(p.readFailed("input lost"))
        assertIs<SourcePolicy.Action.Stop>(p.openFailed(Source.PHONE, "busy"))
    }

    @Test
    fun `bluetooth that cannot open falls back`() {
        val p = SourcePolicy()
        p.start(true, true)
        val a = p.openFailed(Source.BLUETOOTH, "not available") as SourcePolicy.Action.Open
        assertEquals(Source.PHONE, a.source)
        assertTrue(p.fallbackReason!!.contains("could not be opened"))
    }
}
