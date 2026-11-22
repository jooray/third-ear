package app.thirdear.actions

/**
 * What happens to a finished recording under an action, decided without Android so it can be
 * tested and described on the button before the user taps it.
 */
data class ActionPlan(
    val action: RecordAction,
    /** A transcript is made (needs a transcription app). */
    val transcribe: Boolean,
    /** The transcript goes to the clipboard. */
    val copyTranscript: Boolean,
) {
    /** One step of the plan, for the button's description and the spoken confirmation. */
    enum class Step { SAVE, TRANSCRIBE, COPY, SHARE, WHITE_NOISE, WEBHOOK }

    val steps: List<Step>
        get() = buildList {
            add(Step.SAVE)
            if (transcribe) add(Step.TRANSCRIBE)
            when (action.destination) {
                Destination.SHARE -> add(Step.SHARE)
                Destination.WHITE_NOISE -> add(Step.WHITE_NOISE)
                Destination.WEBHOOK -> add(Step.WEBHOOK)
                Destination.SAVE, Destination.COPY_TRANSCRIPT -> {}
            }
            if (copyTranscript) add(Step.COPY)
        }

    /** What is sent once the transcript is known (null: transcription failed or was not made). */
    sealed interface Delivery {
        data object Nothing : Delivery
        data class Audio(val caption: String?) : Delivery
        data class Text(val text: String) : Delivery
    }

    fun delivery(transcript: String?): Delivery {
        if (action.destination == Destination.SAVE || action.destination == Destination.COPY_TRANSCRIPT) return Delivery.Nothing
        return when (action.content) {
            Content.AUDIO -> Delivery.Audio(null)
            Content.AUDIO_AND_TRANSCRIPT -> Delivery.Audio(transcript)
            // nothing to read: send the recording rather than nothing
            Content.TRANSCRIPT -> if (transcript.isNullOrBlank()) Delivery.Audio(null) else Delivery.Text(transcript)
        }
    }

    /** Problems that make the action unusable as configured, for the editor and before sending. */
    enum class Problem { NO_NAME, NO_GROUP, NO_WEBHOOK_URL, WEBHOOK_CLEARTEXT, NO_TRANSCRIBER }

    fun problems(transcriberAvailable: Boolean): List<Problem> = buildList {
        if (action.name.isBlank()) add(Problem.NO_NAME)
        if (action.destination == Destination.WHITE_NOISE && action.groupId.isNullOrBlank()) add(Problem.NO_GROUP)
        if (action.destination == Destination.WEBHOOK) {
            val url = webhookUrl(action.webhookUrl)
            if (!isHttpsUrl(url) && !isHttpUrl(url)) add(Problem.NO_WEBHOOK_URL)
            else if (isHttpUrl(url) && !action.webhookCleartext) add(Problem.WEBHOOK_CLEARTEXT)
        }
        if (transcribe && !transcriberAvailable) add(Problem.NO_TRANSCRIBER)
    }

    companion object {
        fun of(action: RecordAction): ActionPlan {
            val sendsText = action.destination in SENDING && action.content != Content.AUDIO
            val copy = action.destination == Destination.COPY_TRANSCRIPT || action.copyTranscript
            return ActionPlan(
                action = action,
                transcribe = action.transcribe || copy || sendsText,
                copyTranscript = copy,
            )
        }

        private val SENDING = setOf(Destination.SHARE, Destination.WHITE_NOISE, Destination.WEBHOOK)

        fun isHttpsUrl(url: String?): Boolean {
            val u = url?.trim()?.lowercase() ?: return false
            return u.startsWith("https://") && u.length > "https://".length
        }

        fun isHttpUrl(url: String?): Boolean {
            val u = url?.trim()?.lowercase() ?: return false
            return u.startsWith("http://") && u.length > "http://".length
        }

        /** The webhook URL as typed, with https:// assumed when no scheme is given. */
        fun webhookUrl(raw: String?): String? {
            val u = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return if ("://" in u) u else "https://$u"
        }

        /**
         * Where the webhook posts: https, or plain http the user confirmed for this line (a webhook on
         * a Tailscale or Nostr VPN address is encrypted by that layer). Null: nowhere to send.
         */
        fun webhookTarget(action: RecordAction): String? {
            val url = webhookUrl(action.webhookUrl)
            return url?.takeIf { isHttpsUrl(it) || (isHttpUrl(it) && action.webhookCleartext) }?.trim()
        }
    }
}
