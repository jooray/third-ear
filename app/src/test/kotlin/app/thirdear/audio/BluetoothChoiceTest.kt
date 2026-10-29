package app.thirdear.audio

import app.thirdear.audio.BluetoothChoice.Dev
import kotlin.test.assertEquals
import org.junit.Test

class BluetoothChoiceTest {
    private val earpiece = Dev(1, "")
    private val car = Dev(7, "AA:BB:CC:00:CA:01")
    private val phones = Dev(7, "AA:BB:CC:00:EE:02")
    private val aid = Dev(26, "AA:BB:CC:00:0A:03")
    private val aid17 = Dev(33, "AA:BB:CC:00:0A:04")

    @Test
    fun `android auto with audio on the phone and headphones playing picks the headphones`() {
        assertEquals(listOf(2, 1), BluetoothChoice.order(listOf(earpiece, car, phones), setOf("aa:bb:cc:00:ee:02")))
    }

    @Test
    fun `the headset media plays to beats idle LE audio`() {
        assertEquals(listOf(1, 0), BluetoothChoice.order(listOf(aid, phones), setOf(phones.address!!)))
    }

    @Test
    fun `without media information LE audio comes first, then SCO in Android's order`() {
        assertEquals(listOf(2, 0, 1), BluetoothChoice.order(listOf(car, phones, aid), emptySet()))
    }

    @Test
    fun `android 17 hearing aid type counts as LE audio, other types are not microphones`() {
        assertEquals(listOf(1, 0), BluetoothChoice.order(listOf(car, aid17, earpiece, Dev(8, "x")), emptySet()))
    }
}
