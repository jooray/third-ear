package app.thirdear.pipeline

/**
 * How long to wait before asking a busy transcription app again. OSTT runs one job at a time:
 * while its keyboard dictates it answers ERROR_RECOGNIZER_BUSY (or ERROR_AUDIO), and a newer
 * request from another app can retire ours without an answer. I Hear You's schedule: 5, 15, 30,
 * 60 s, then every 2 minutes, about 10 minutes in all.
 */
class Backoff(private val scheduleSeconds: List<Int> = DEFAULT) {
    companion object {
        val DEFAULT = listOf(5, 15, 30, 60, 120, 120, 120, 120)
    }

    /** Delay before retry number [attempt] (0-based), or null when it is time to give up. */
    fun delayMs(attempt: Int): Long? = scheduleSeconds.getOrNull(attempt)?.let { it * 1000L }

    val totalMs: Long get() = scheduleSeconds.sum() * 1000L

    /**
     * Runs [block] until it succeeds, a non-retryable failure, or the schedule runs out. [retryable]
     * tells busy errors apart; [onWait] hears about each wait (for the "waiting for the
     * transcription app" notification); [sleep] is injectable for tests.
     */
    suspend fun <T> run(
        retryable: (Throwable) -> Boolean,
        onWait: suspend (attempt: Int, delayMs: Long, cause: Throwable) -> Unit,
        sleep: suspend (Long) -> Unit,
        block: suspend () -> T,
    ): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                val wait = if (retryable(t)) delayMs(attempt) else null
                if (wait == null) throw t
                onWait(attempt, wait, t)
                sleep(wait)
                attempt++
            }
        }
    }
}
