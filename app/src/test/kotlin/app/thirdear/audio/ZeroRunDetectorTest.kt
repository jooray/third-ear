package app.thirdear.audio

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class ZeroRunDetectorTest {

    private fun block(v: Short, n: Int = 100) = ShortArray(n) { v }

    @Test
    fun `a quiet room is not silence`() {
        val d = ZeroRunDetector(1_000)
        repeat(100) { assertEquals(ZeroRunDetector.Change.NONE, d.feed(ShortArray(100) { if (it % 7 == 0) 1 else 0 }, 100)) }
        assertFalse(d.silent)
        assertTrue(d.soundSeen)
    }

    @Test
    fun `exact zeros past the limit are silence, sound brings it back`() {
        val d = ZeroRunDetector(1_000)
        repeat(9) { assertEquals(ZeroRunDetector.Change.NONE, d.feed(block(0), 100)) }
        assertEquals(ZeroRunDetector.Change.SILENT, d.feed(block(0), 100))
        assertEquals(ZeroRunDetector.Change.NONE, d.feed(block(0), 100))
        assertEquals(ZeroRunDetector.Change.SOUND_BACK, d.feed(block(5), 100))
        assertFalse(d.silent)
    }

    @Test
    fun `trailing zeros of a block count toward the run`() {
        val d = ZeroRunDetector(150)
        val b = ShortArray(100) { if (it < 50) 3 else 0 }
        assertEquals(ZeroRunDetector.Change.NONE, d.feed(b, 100))
        assertEquals(ZeroRunDetector.Change.SILENT, d.feed(block(0), 100))
    }

    @Test
    fun `peak takes the absolute maximum`() {
        assertEquals(300, peak(shortArrayOf(1, -300, 200), 3))
        assertEquals(32767, peak(shortArrayOf(Short.MIN_VALUE), 1))
    }
}
