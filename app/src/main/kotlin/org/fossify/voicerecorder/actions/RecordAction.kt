package org.fossify.voicerecorder.actions

import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Where a finished recording goes. Every destination saves the recording first. */
enum class Destination {
    /** Saved to the recordings folder, nothing else. */
    SAVE,

    /** Saved, transcribed, transcript copied to the clipboard. */
    COPY_TRANSCRIPT,

    /** The Android share sheet. */
    SHARE,

    /** A White Noise group, through Third Ear's own identity. */
    WHITE_NOISE,

    /** HTTP POST (multipart), as in I Hear You. */
    WEBHOOK,
}

/** What is sent where something is sent. */
enum class Content { AUDIO, AUDIO_AND_TRANSCRIPT, TRANSCRIPT }

/**
 * A button on the recorder screen (and a Quick Settings tile, and a car screen tile): tap it to
 * start recording; tap it (or another one) while recording to stop and do what it says.
 */
data class RecordAction(
    val id: String = UUID.randomUUID().toString().take(8),
    val name: String,
    val destination: Destination = Destination.SAVE,
    val content: Content = Content.AUDIO,
    /** Make a transcript even when nothing sends it (it is saved next to the recording). */
    val transcribe: Boolean = false,
    /** Copy the transcript to the clipboard when it is ready. */
    val copyTranscript: Boolean = false,
    val groupId: String? = null,
    val groupName: String? = null,
    val webhookUrl: String? = null,
    val webhookAuth: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("destination", destination.name)
        .put("content", content.name)
        .put("transcribe", transcribe)
        .put("copy", copyTranscript)
        .putOpt("groupId", groupId)
        .putOpt("groupName", groupName)
        .putOpt("webhookUrl", webhookUrl)
        .putOpt("webhookAuth", webhookAuth)

    companion object {
        fun fromJson(o: JSONObject) = RecordAction(
            id = o.getString("id"),
            name = o.optString("name"),
            destination = enumOr(o.optString("destination"), Destination.SAVE),
            content = enumOr(o.optString("content"), Content.AUDIO),
            transcribe = o.optBoolean("transcribe"),
            copyTranscript = o.optBoolean("copy"),
            groupId = o.optStringOrNull("groupId"),
            groupName = o.optStringOrNull("groupName"),
            webhookUrl = o.optStringOrNull("webhookUrl"),
            webhookAuth = o.optStringOrNull("webhookAuth"),
        )

        fun listToJson(list: List<RecordAction>): String = JSONArray(list.map { it.toJson() }).toString()

        fun listFromJson(s: String?): List<RecordAction> {
            if (s.isNullOrBlank()) return emptyList()
            return runCatching {
                val a = JSONArray(s)
                (0 until a.length()).mapNotNull { i -> runCatching { fromJson(a.getJSONObject(i)) }.getOrNull() }
            }.getOrDefault(emptyList())
        }
    }
}

internal fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).ifEmpty { null } else null

internal inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
    name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: fallback
