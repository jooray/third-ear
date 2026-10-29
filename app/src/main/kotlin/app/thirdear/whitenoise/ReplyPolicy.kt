package app.thirdear.whitenoise

import app.thirdear.actions.enumOr
import org.json.JSONArray
import org.json.JSONObject

/** What Third Ear plays from a group. */
enum class ReplyMode {
    /** Nothing: no sound, no notification (messages still appear in the in-app list). */
    OFF,

    /** Voice messages are played; text is only shown. */
    VOICE,

    /** Voice messages are played and text is read with text-to-speech. */
    VOICE_AND_TEXT,
}

/** When it plays. */
enum class ReadMode {
    /** Right away, as in I Hear You (not while recording, not in the car). */
    AUTO,

    /** On one tap: "Read" in the notification, the car screen or the in-app list. */
    ASK,
}

/** Per-group settings. */
data class GroupSettings(
    val replies: ReplyMode = ReplyMode.VOICE_AND_TEXT,
    val read: ReadMode = ReadMode.ASK,
    /** Messages containing any of these are ignored; default the microphone emoji that marks an echoed transcript. */
    val ignore: List<String> = listOf(DEFAULT_IGNORE),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("replies", replies.name)
        .put("read", read.name)
        .put("ignore", JSONArray(ignore))

    companion object {
        const val DEFAULT_IGNORE = "🎙️"

        fun fromJson(o: JSONObject?): GroupSettings {
            if (o == null) return GroupSettings()
            val arr = o.optJSONArray("ignore")
            return GroupSettings(
                replies = enumOr(o.optString("replies"), ReplyMode.VOICE_AND_TEXT),
                read = enumOr(o.optString("read"), ReadMode.ASK),
                ignore = if (arr == null) listOf(DEFAULT_IGNORE) else (0 until arr.length()).map { arr.getString(it) }.filter { it.isNotBlank() },
            )
        }

        /** The ignore list as typed in the settings: one entry per line. */
        fun parseIgnore(text: String): List<String> = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }
}

/**
 * Decides what happens with an incoming White Noise message, without Android, so the rules are
 * tested: per-group mode (off / voice / voice + text), automatic or on request, the ignore list,
 * and two situations that always turn "automatic" into "ask": the car (Android Auto announces the
 * message and offers to read it, like WhatsApp) and a recording in progress (it would be
 * recorded).
 */
object ReplyPolicy {

    data class Message(val text: String, val hasAudio: Boolean)

    data class Decision(
        /** Post a notification (MessagingStyle; Android Auto picks it up). */
        val notify: Boolean,
        val autoPlayAudio: Boolean,
        val autoReadText: Boolean,
        /** Offer "Play" / "Read" on request. */
        val offerPlay: Boolean,
        val offerRead: Boolean,
        /** The ignore-list entry that matched, if any. */
        val ignoredBy: String? = null,
    ) {
        companion object {
            val NOTHING = Decision(notify = false, autoPlayAudio = false, autoReadText = false, offerPlay = false, offerRead = false)
        }
    }

    fun decide(m: Message, s: GroupSettings, carConnected: Boolean, recording: Boolean): Decision {
        s.ignore.firstOrNull { containsLoosely(m.text, it) }?.let { return Decision.NOTHING.copy(ignoredBy = it) }
        if (s.replies == ReplyMode.OFF) return Decision.NOTHING
        val canPlay = m.hasAudio
        val canRead = s.replies == ReplyMode.VOICE_AND_TEXT && m.text.isNotBlank()
        val auto = s.read == ReadMode.AUTO && !carConnected && !recording
        return Decision(
            notify = true,
            autoPlayAudio = auto && canPlay,
            autoReadText = auto && canRead,
            offerPlay = !auto && canPlay,
            offerRead = !auto && canRead,
        )
    }

    /** Case-insensitive, and blind to emoji variation selectors (🎙 and 🎙️ are the same). */
    fun containsLoosely(text: String, needle: String): Boolean {
        fun norm(s: String) = s.replace("️", "").replace("︎", "").lowercase()
        val n = norm(needle).trim()
        return n.isNotEmpty() && norm(text).contains(n)
    }
}
