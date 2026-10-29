package org.fossify.voicerecorder.whitenoise

import android.content.Context
import android.util.Log
import java.io.File
import org.fossify.voicerecorder.services.RecorderService

/** Whether the phone is connected to Android Auto (set by the car module's CarConnection observer). */
object CarState {
    @Volatile
    var connected = false
}

/** What the rest of the app uses: sending from the pipeline, groups for the editor, incoming messages. */
object WhiteNoise {
    private const val TAG = "ThirdEarWN"

    data class GroupInfo(val id: String, val name: String)

    /** Messages older than this process's runtime start are history replayed on start, not news. */
    private val startedAt = System.currentTimeMillis()

    fun joinedGroups(): List<GroupInfo> =
        MarmotHost.groups.value.filter { it.member && !it.pending }.map { GroupInfo(it.id, it.name) }

    suspend fun ensureRunning(context: Context) {
        if (MarmotHost.phase.value != MarmotHost.Phase.READY) MarmotHost.start(context)
        if (MarmotHost.phase.value != MarmotHost.Phase.READY) error("White Noise: ${MarmotHost.error.value ?: "not running"}")
    }

    suspend fun sendVoice(context: Context, groupId: String, file: File, fileName: String, mime: String, caption: String?) {
        ensureRunning(context)
        MarmotHost.sendVoice(groupId, file, fileName, mime, caption)
    }

    suspend fun sendText(context: Context, groupId: String, text: String) {
        ensureRunning(context)
        MarmotHost.sendText(groupId, text)
    }

    /** An incoming message: stored, notified, and played or offered as the group's settings say. */
    suspend fun onIncoming(context: Context, m: MarmotHost.Incoming) {
        val store = IncomingStore(context)
        if (store.get(m.messageId) != null) return
        val groupName = MarmotHost.groups.value.firstOrNull { it.id == m.groupId }?.name ?: m.groupId.take(8)
        val settings = GroupSettingsStore(context).get(m.groupId)
        val hasAudio = m.media.any { it.mediaType.startsWith("audio/") }
        val history = m.at < startedAt - 60_000
        val decision = ReplyPolicy.decide(
            ReplyPolicy.Message(m.text, hasAudio), settings, carConnected = CarState.connected, recording = RecorderService.isRunning,
        )
        val files = mutableListOf<String>()
        if (!history && decision.ignoredBy == null && settings.replies != ReplyMode.OFF) {
            m.media.filter { it.mediaType.startsWith("audio/") }.forEachIndexed { i, ref ->
                runCatching {
                    val ext = ref.fileName.substringAfterLast('.', "m4a").take(5)
                    val f = store.audioFile(m.messageId, i, ext)
                    f.writeBytes(MarmotHost.download(m.groupId, ref))
                    files += f.path
                }.onFailure { Log.w(TAG, "download failed: ${it.message}") }
            }
        }
        val msg = IncomingMessage(
            id = m.messageId, groupId = m.groupId, groupName = groupName, sender = m.sender, senderName = m.senderName,
            text = m.text, at = m.at, audio = files,
            read = history || !decision.notify, ignoredBy = decision.ignoredBy,
        )
        store.add(msg)
        Log.i(TAG, "message in $groupName (${m.text.length} chars, ${files.size} audio): $decision")
        if (history || !decision.notify) return
        MessageNotifier.post(context, m.groupId, decision.offerPlay, decision.offerRead, alert = true)
        if (decision.autoPlayAudio || decision.autoReadText) {
            play(context, msg, audio = decision.autoPlayAudio, text = decision.autoReadText)
            markRead(context, m.groupId, listOf(msg.id))
        }
    }

    /** Plays one message (from the list, a notification or the car). */
    suspend fun play(context: Context, m: IncomingMessage, audio: Boolean = true, text: Boolean = true) {
        val speaker = ReplySpeaker.get(context)
        try {
            if (audio) for (path in m.audio) File(path).takeIf { it.exists() }?.let { speaker.play(it) }
            if (text && m.text.isNotBlank()) speaker.speak(m.text)
        } catch (t: Throwable) {
            Log.w(TAG, "playback failed: ${t.message}")
        }
    }

    /** Plays the group's unread messages in order (the notification's "Read aloud"), then marks them read. */
    suspend fun playUnread(context: Context, groupId: String) {
        val settings = GroupSettingsStore(context).get(groupId)
        val unread = IncomingStore(context).unread(groupId).sortedBy { it.at }
        for (m in unread) play(context, m, audio = true, text = settings.replies == ReplyMode.VOICE_AND_TEXT)
        markRead(context, groupId, unread.map { it.id })
    }

    suspend fun markRead(context: Context, groupId: String, ids: List<String>) {
        IncomingStore(context).markRead(ids)
        for (id in ids) runCatching { MarmotHost.markRead(groupId, id) }
        val settings = GroupSettingsStore(context).get(groupId)
        MessageNotifier.post(context, groupId, offerPlay = true, offerRead = settings.replies == ReplyMode.VOICE_AND_TEXT, alert = false)
    }
}
