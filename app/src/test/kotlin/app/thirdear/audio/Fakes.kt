package app.thirdear.audio

/** A clock tests advance by hand (each fake read moves it by 100 ms). */
class FakeClock(var now: Long = 1_000_000L) {
    fun get(): Long = now
}

/**
 * A microphone that plays a script: each entry is one read (samples of one value, 0 = no data,
 * negative = error). After the script it returns [tail] forever.
 */
class FakeSource(
    override val kind: Source,
    private val clock: FakeClock,
    private val script: List<Int>,
    private val tail: Int = 1,
    var routedInput: RoutedInput = if (kind == Source.BLUETOOTH) RoutedInput(true, "Headset") else RoutedInput.PHONE,
    private val value: Short = 100,
    private val onRead: (Int) -> Unit = {},
) : PcmSource {
    var reads = 0
    var closed = false

    override fun read(buffer: ShortArray, count: Int): Int {
        clock.now += 100
        val step = script.getOrElse(reads) { tail }
        onRead(reads)
        reads++
        return when {
            step < 0 -> step
            step == 0 -> 0
            else -> {
                // step 1 = real sound, step 2 = exact zeros (an LE input still starting)
                val v: Short = if (step == 2) 0 else value
                for (i in 0 until count) buffer[i] = v
                count
            }
        }
    }

    override fun routed(): RoutedInput = routedInput

    override fun close() {
        closed = true
    }
}

class FakeSink : AudioSink {
    override var samplesWritten = 0L
        private set
    var finished = false
    var aborted = false
    val values = mutableListOf<Short>()

    override fun write(pcm: ShortArray, count: Int) {
        samplesWritten += count
        if (count > 0) values += pcm[0]
    }

    override fun finish() {
        finished = true
    }

    override fun abort() {
        aborted = true
    }
}

class RecordingListener : RecordingEngine.Listener {
    val statuses = mutableListOf<EngineStatus>()
    val cues = mutableListOf<SourcePolicy.Cue>()
    val log = mutableListOf<String>()
    var finishedSamples = -1L
    var failure: String? = null

    override fun onStatus(status: EngineStatus) {
        statuses += status
    }

    override fun onCue(cue: SourcePolicy.Cue) {
        cues += cue
    }

    override fun onLog(message: String) {
        log += message
    }

    override fun onFinished(samples: Long, failure: String?) {
        finishedSamples = samples
        this.failure = failure
    }
}
