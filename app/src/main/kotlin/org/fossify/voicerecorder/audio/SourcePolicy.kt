package org.fossify.voicerecorder.audio

/**
 * Decides which microphone a recording uses, without touching Android: the engine reports what
 * happened (opened, first sound, route changes, read failures, the user's switch) and carries out
 * the returned [Action]. Rules, from the Bluetooth microphone work in I Hear You and OSTT:
 *
 * - a Bluetooth input gets a generous deadline for its first non-zero sample (LE Audio takes
 *   1.7–3.5 s to start); it is never reopened while it starts, because reopening restarts the
 *   LE Audio setup and it never finishes;
 * - when it does not come up, breaks, or Android moves the input off Bluetooth, the recording
 *   falls back to the phone microphone with an audible cue, once, and stays there;
 * - a switch the user asks for always happens, and re-arms the fallback;
 * - the phone microphone failing ends the recording (what was captured is kept).
 */
class SourcePolicy(private val firstSoundTimeoutMs: Long = DEFAULT_FIRST_SOUND_TIMEOUT_MS) {

    companion object {
        const val DEFAULT_FIRST_SOUND_TIMEOUT_MS = 8_000L
    }

    enum class Cue { FALLBACK_TO_PHONE }

    sealed interface Action {
        data object None : Action
        data class Open(val source: Source, val cue: Cue? = null, val reason: String) : Action
        data class Stop(val reason: String) : Action
    }

    /** What is open (or being opened) now. */
    var current: Source = Source.PHONE
        private set

    /** Bluetooth is open but no real audio has arrived yet: "Waiting for the Bluetooth microphone…". */
    var waitingForBluetooth = false
        private set

    /** True after an automatic fallback in this recording (the next one is not automatic). */
    var fellBack = false
        private set

    /** Why the recording is on the phone although Bluetooth was wanted, for the UI and the log. */
    var fallbackReason: String? = null
        private set

    private var waitingSince = 0L
    private var soundOnBluetooth = false

    /** The first source to open. */
    fun start(preferBluetooth: Boolean, bluetoothAvailable: Boolean): Source {
        fellBack = false
        fallbackReason = if (preferBluetooth && !bluetoothAvailable) "no Bluetooth microphone connected" else null
        current = if (preferBluetooth && bluetoothAvailable) Source.BLUETOOTH else Source.PHONE
        return current
    }

    /** [source] opened; [routed] is where Android says the input went. */
    fun opened(source: Source, routed: RoutedInput, now: Long): Action {
        current = source
        soundOnBluetooth = false
        if (source == Source.BLUETOOTH) {
            waitingForBluetooth = true
            waitingSince = now
        } else {
            waitingForBluetooth = false
        }
        return Action.None
    }

    /** [source] could not be opened ([PcmSourceFactory.open] returned null or threw). */
    fun openFailed(source: Source, why: String): Action = when (source) {
        Source.BLUETOOTH -> fallBack("Bluetooth microphone could not be opened: $why")
        Source.PHONE -> Action.Stop("microphone could not be opened: $why")
    }

    /** A non-zero sample arrived from the current source. */
    fun firstSound() {
        if (current == Source.BLUETOOTH) soundOnBluetooth = true
        waitingForBluetooth = false
    }

    /** Called about every 100 ms while recording. */
    fun tick(now: Long): Action {
        if (current == Source.BLUETOOTH && waitingForBluetooth && now - waitingSince >= firstSoundTimeoutMs) {
            return fallBack("no sound from the Bluetooth microphone in ${firstSoundTimeoutMs / 1000} s")
        }
        return Action.None
    }

    /** Android moved the input. Off Bluetooth after it had started means the headset went away. */
    fun routed(input: RoutedInput): Action {
        if (current == Source.BLUETOOTH && !input.bluetooth && (soundOnBluetooth || !waitingForBluetooth)) {
            return fallBack("the Bluetooth microphone disconnected")
        }
        return Action.None
    }

    /** The current source broke (read error, or no data for about a second after it had started). */
    fun readFailed(why: String): Action = when (current) {
        Source.BLUETOOTH -> fallBack("the Bluetooth microphone stopped: $why")
        Source.PHONE -> Action.Stop("the microphone stopped: $why")
    }

    /** The user picked a source on the recording screen. */
    fun userSwitch(to: Source): Action {
        if (to == Source.BLUETOOTH) {
            fellBack = false
        }
        fallbackReason = null
        return Action.Open(to, reason = "switched by the user")
    }

    private fun fallBack(why: String): Action {
        waitingForBluetooth = false
        if (fellBack) {
            // already fell back once; a second automatic flip only adds gaps
            return Action.Open(Source.PHONE, reason = why)
        }
        fellBack = true
        fallbackReason = why
        return Action.Open(Source.PHONE, Cue.FALLBACK_TO_PHONE, why)
    }
}
