package app.thirdear.audio

import java.util.concurrent.ConcurrentLinkedQueue

/** What the recording screen, the notification and the tiles show. */
data class EngineStatus(
    val starting: Boolean = true,
    val paused: Boolean = false,
    /** The source that is open or being opened. */
    val source: Source = Source.PHONE,
    /** Where Android really routed it. */
    val input: RoutedInput = RoutedInput.PHONE,
    /** True between opening Bluetooth and its first real sample. */
    val waitingForBluetooth: Boolean = false,
    /** Opening a source (a Bluetooth route takes up to a few seconds). */
    val switching: Boolean = false,
    /** Exact digital zeros for a while, or Android silencing us. */
    val silent: Boolean = false,
    /** Why the recording is on the phone although Bluetooth was wanted. */
    val fallbackReason: String? = null,
    val samples: Long = 0,
    val sampleRate: Int = 48_000,
    val amplitude: Int = 0,
) {
    val durationMs: Long get() = samples * 1000 / sampleRate
}

/**
 * One recording: reads the current microphone on its own thread and feeds the [AudioSink]. The
 * microphone can change mid-recording (the user's switch, a Bluetooth fallback, a disconnect)
 * and the encoder, and so the file, just continues. Pause keeps the microphone open and drops
 * the samples, so the file has no gap and resuming is instant.
 */
class RecordingEngine(
    private val sources: PcmSourceFactory,
    private val sink: AudioSink,
    private val sampleRate: Int,
    private val preferBluetooth: Boolean,
    private val bluetoothAvailable: () -> Boolean,
    private val listener: Listener,
    private val clock: () -> Long = System::currentTimeMillis,
    firstSoundTimeoutMs: Long = SourcePolicy.DEFAULT_FIRST_SOUND_TIMEOUT_MS,
    /** Exact zeros this long mean a muted or taken microphone, not a quiet room. */
    silenceSeconds: Int = 5,
) {
    interface Listener {
        fun onStatus(status: EngineStatus)
        fun onCue(cue: SourcePolicy.Cue)
        fun onLog(message: String)

        /** The loop ended; [failure] is set when it ended because the microphone failed. */
        fun onFinished(samples: Long, failure: String?)
    }

    private sealed interface Command {
        data class Switch(val to: Source) : Command
        data class Pause(val paused: Boolean) : Command
        data class FromSource(val event: SourceEvent, val generation: Int) : Command
    }

    private val policy = SourcePolicy(firstSoundTimeoutMs)
    private val zeros = ZeroRunDetector(sampleRate * silenceSeconds)
    private val commands = ConcurrentLinkedQueue<Command>()
    @Volatile private var stopRequested = false
    @Volatile private var discard = false
    @Volatile var status = EngineStatus(sampleRate = sampleRate)
        private set
    private var source: PcmSource? = null
    private var generation = 0
    private var androidSilenced = false
    private var thread: Thread? = null

    fun start() {
        thread = Thread({ runLoop() }, "third-ear-recorder").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    /** Ends the recording; the sink is drained unless [discardAudio]. Safe from any thread, also while starting. */
    fun stop(discardAudio: Boolean = false) {
        discard = discardAudio
        stopRequested = true
    }

    fun switchTo(to: Source) = commands.add(Command.Switch(to))

    fun setPaused(paused: Boolean) = commands.add(Command.Pause(paused))

    fun join(timeoutMs: Long) = thread?.join(timeoutMs)

    /** The loop; [start] runs it on a thread, tests call it directly. */
    fun runLoop() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        var failure: String? = null
        try {
            val first = policy.start(preferBluetooth, preferBluetooth && bluetoothAvailable())
            publish { it.copy(starting = true, source = first, fallbackReason = policy.fallbackReason) }
            failure = openOrFail(SourcePolicy.Action.Open(first, reason = "start"))
            publish { it.copy(starting = false) }
            if (failure == null) failure = loop()
        } catch (t: Throwable) {
            failure = t.message ?: t.javaClass.simpleName
            listener.onLog("recorder crashed: $failure")
        } finally {
            closeSource()
            try {
                if (discard) sink.abort() else sink.finish()
            } catch (t: Throwable) {
                listener.onLog("finishing the encoder failed: ${t.message}")
                if (failure == null) failure = "encoder: ${t.message}"
            }
            listener.onFinished(sink.samplesWritten, failure)
        }
    }

    /** Opens [kind] (falling back through the policy); returns a failure that ends the recording. */
    private fun openOrFail(first: SourcePolicy.Action.Open): String? {
        var next: SourcePolicy.Action = first
        while (next is SourcePolicy.Action.Open) {
            val want = next.source
            next.cue?.let { listener.onCue(it) }
            if (next.reason != "start") listener.onLog("switching to ${want.name.lowercase()}: ${next.reason}")
            closeSource()
            publish { it.copy(source = want, switching = true, waitingForBluetooth = false) }
            val gen = ++generation
            val opened = try {
                sources.open(want, sampleRate) { e -> commands.add(Command.FromSource(e, gen)) }
            } catch (t: Throwable) {
                null.also { listener.onLog("opening ${want.name.lowercase()} failed: ${t.message}") }
            }
            if (opened == null) {
                next = policy.openFailed(want, if (want == Source.BLUETOOTH) "not available" else "failed")
                continue
            }
            source = opened
            zeros.reset()
            val routed = opened.routed()
            policy.opened(want, routed, clock())
            listener.onLog("recording from ${want.name.lowercase()} (routed: ${if (routed.bluetooth) "bluetooth" else "phone"})")
            publish {
                it.copy(
                    source = want, input = routed, switching = false, silent = false,
                    waitingForBluetooth = policy.waitingForBluetooth, fallbackReason = policy.fallbackReason,
                )
            }
            return null
        }
        publish { it.copy(switching = false) }
        return (next as? SourcePolicy.Action.Stop)?.reason
    }

    private fun loop(): String? {
        val buffer = ShortArray(sampleRate / 10)
        var paused = false
        var empty = 0
        var lastPublish = 0L
        while (!stopRequested) {
            // commands from the UI and from the source's callbacks, between reads
            while (true) {
                val c = commands.poll() ?: break
                val action = when (c) {
                    is Command.Switch -> if (c.to == policy.current) SourcePolicy.Action.None else policy.userSwitch(c.to)
                    is Command.Pause -> {
                        paused = c.paused
                        publish { it.copy(paused = paused) }
                        SourcePolicy.Action.None
                    }

                    is Command.FromSource -> if (c.generation != generation) SourcePolicy.Action.None else onSourceEvent(c.event)
                }
                act(action)?.let { return it }
            }
            if (stopRequested) break
            val src = source ?: return "no microphone"
            val n = src.read(buffer, buffer.size)
            val now = clock()
            when {
                n > 0 -> {
                    empty = 0
                    val hadSound = zeros.soundSeen
                    val change = zeros.feed(buffer, n)
                    if (!hadSound && zeros.soundSeen) {
                        policy.firstSound()
                        listener.onLog("first sound from ${src.kind.name.lowercase()}")
                        publish { it.copy(waitingForBluetooth = false) }
                    }
                    when (change) {
                        ZeroRunDetector.Change.SILENT -> if (!policy.waitingForBluetooth) {
                            listener.onLog("exact digital silence from the microphone")
                            publish { it.copy(silent = true) }
                        }

                        ZeroRunDetector.Change.SOUND_BACK -> publish { it.copy(silent = androidSilenced) }
                        ZeroRunDetector.Change.NONE -> {}
                    }
                    if (!paused) sink.write(buffer, n)
                    if (now - lastPublish >= 100) {
                        lastPublish = now
                        val amp = if (paused) 0 else peak(buffer, n)
                        publish { it.copy(samples = sink.samplesWritten, amplitude = amp) }
                    }
                }

                n == 0 -> {
                    // before the first sound a Bluetooth input legitimately delivers nothing
                    val limit = if (zeros.soundSeen) 10 else if (src.kind == Source.PHONE) 30 else Int.MAX_VALUE
                    if (++empty > limit) {
                        empty = 0
                        act(policy.readFailed("no audio for a second"))?.let { return it }
                        continue
                    }
                    Thread.sleep(10)
                }

                else -> {
                    act(policy.readFailed(readError(n)))?.let { return it }
                    continue
                }
            }
            act(policy.tick(now))?.let { return it }
        }
        return null
    }

    private fun onSourceEvent(e: SourceEvent): SourcePolicy.Action = when (e) {
        is SourceEvent.Routed -> {
            listener.onLog("input routed to ${if (e.input.bluetooth) "bluetooth" else "phone"}")
            publish { it.copy(input = e.input) }
            policy.routed(e.input)
        }

        is SourceEvent.Silenced -> {
            androidSilenced = e.silenced
            listener.onLog(if (e.silenced) "Android is silencing the microphone" else "microphone no longer silenced")
            publish { it.copy(silent = e.silenced || zeros.silent) }
            SourcePolicy.Action.None
        }
    }

    /** Carries out a policy decision; returns a failure when the recording has to end. */
    private fun act(action: SourcePolicy.Action): String? = when (action) {
        SourcePolicy.Action.None -> null
        is SourcePolicy.Action.Stop -> action.reason
        is SourcePolicy.Action.Open -> openOrFail(action)
    }

    private fun closeSource() {
        source?.let { runCatching { it.close() } }
        source = null
    }

    private inline fun publish(change: (EngineStatus) -> EngineStatus) {
        status = change(status)
        listener.onStatus(status)
    }

    private fun readError(n: Int) = when (n) {
        -3 -> "invalid operation"
        -2 -> "bad value"
        -6 -> "input lost"
        else -> "error $n"
    }
}
