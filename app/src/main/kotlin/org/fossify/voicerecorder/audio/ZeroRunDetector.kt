package org.fossify.voicerecorder.audio

/**
 * Watches for runs of exact digital zeros. A quiet room still produces non-zero samples; zeros
 * mean no real audio (the system microphone toggle, a muted headset, an input that was never set
 * up, another app holding the microphone).
 */
class ZeroRunDetector(private val samplesForSilence: Int) {

    enum class Change { NONE, SILENT, SOUND_BACK }

    private var zeros = 0L
    var silent = false
        private set

    /** True once any non-zero sample has been seen. */
    var soundSeen = false
        private set

    fun feed(buffer: ShortArray, count: Int): Change {
        var lastNonZero = -1
        for (i in 0 until count) if (buffer[i].toInt() != 0) lastNonZero = i
        if (lastNonZero >= 0) {
            soundSeen = true
            zeros = (count - 1 - lastNonZero).toLong()
        } else {
            zeros += count
        }
        return when {
            !silent && zeros >= samplesForSilence -> {
                silent = true
                Change.SILENT
            }

            silent && lastNonZero >= 0 && zeros < samplesForSilence -> {
                silent = false
                Change.SOUND_BACK
            }

            else -> Change.NONE
        }
    }

    fun reset() {
        zeros = 0
        silent = false
        soundSeen = false
    }
}

/** Peak of a block, in MediaRecorder.getMaxAmplitude's 0..32767 scale. */
fun peak(buffer: ShortArray, count: Int): Int {
    var max = 0
    for (i in 0 until count) {
        val v = buffer[i].toInt()
        val a = if (v < 0) -v else v
        if (a > max) max = a
    }
    return minOf(max, Short.MAX_VALUE.toInt())
}
