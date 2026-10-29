package app.thirdear.audio

/**
 * Which Bluetooth communication device records, without Android (tested). Same rule in Third Ear,
 * I Hear You and OSTT: the device media plays to first, then LE Audio (headsets, hearing aids),
 * then classic hands-free (SCO). Anything else is not a Bluetooth microphone.
 */
object BluetoothChoice {
    private const val TYPE_BLUETOOTH_SCO = 7
    private const val TYPE_BLE_HEADSET = 26
    private const val TYPE_BLE_HEARING_AID = 33

    data class Dev(val type: Int, val address: String?)

    /** Indices of [devices] that are Bluetooth microphones, best first. */
    fun order(devices: List<Dev>, mediaAddresses: Set<String>): List<Int> {
        fun le(t: Int) = t == TYPE_BLE_HEADSET || t == TYPE_BLE_HEARING_AID
        val mics = devices.indices.filter { le(devices[it].type) || devices[it].type == TYPE_BLUETOOTH_SCO }
        val media = mediaAddresses.map { it.uppercase() }.toSet()
        fun rank(i: Int): Int {
            val d = devices[i]
            val playing = !d.address.isNullOrBlank() && d.address.uppercase() in media
            return when {
                playing -> 0
                le(d.type) -> 1
                else -> 2
            }
        }
        return mics.sortedBy { rank(it) } // stable: Android's order within a rank
    }
}
