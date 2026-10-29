package app.thirdear.audio

/** Where the user wants to record from. What really records is reported by [PcmSource.routed]. */
enum class Source { PHONE, BLUETOOTH }

/** The input Android actually routed the recording to. */
data class RoutedInput(val bluetooth: Boolean, val name: String?) {
    companion object {
        val PHONE = RoutedInput(false, null)
    }
}

/** Reported by an open source from any thread while it records. */
sealed interface SourceEvent {
    /** Android moved the input (a headset disconnected, or the route came up late). */
    data class Routed(val input: RoutedInput) : SourceEvent

    /** Android stopped giving us audio (a call or another app took the microphone). */
    data class Silenced(val silenced: Boolean) : SourceEvent
}

/**
 * One open microphone delivering 16-bit mono PCM at the recording's sample rate. Opening may block
 * for a few seconds (a Bluetooth route coming up), so it happens on the engine thread.
 */
interface PcmSource {
    val kind: Source

    /** Blocking read of up to [count] samples; >= 0 samples read, < 0 an AudioRecord error. */
    fun read(buffer: ShortArray, count: Int): Int

    /** The verified input right now. */
    fun routed(): RoutedInput

    /** Stops and releases the input, and the Bluetooth route if this source set one. */
    fun close()
}

fun interface PcmSourceFactory {
    /**
     * Opens [kind] at [sampleRate]. Returns null when that kind is not available right now (no
     * Bluetooth microphone connected); throws when the microphone cannot be opened at all.
     */
    fun open(kind: Source, sampleRate: Int, events: (SourceEvent) -> Unit): PcmSource?
}
