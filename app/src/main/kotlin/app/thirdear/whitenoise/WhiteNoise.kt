package app.thirdear.whitenoise

import android.content.Context
import android.util.Log
import java.io.File
import app.thirdear.extensions.config
import app.thirdear.services.RecorderService

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

    /**
     * Settings come back from a backup, the White Noise key and MDK state do not. Starting the
     * runtime then would silently create a new identity; instead White Noise is switched off and
     * the White Noise screen explains: import the nsec, or create a new identity and have the
     * groups invite it again.
     */
    fun checkRestored(context: Context) {
        val c = context.config
        if (restoredWithoutIdentity(c.whiteNoiseEnabled, File(context.noBackupFilesDir, "marmot").exists())) {
            c.whiteNoiseEnabled = false
            c.whiteNoiseRestored = true
        }
    }

    /**
     * Turns White Noise on (Setup's switch, and the line editor's "Turn on"); the caller starts
     * the runtime. From here on a missing MDK directory is the user's choice, not a restored backup.
     */
    fun turnOn(context: Context) {
        val c = context.config
        c.whiteNoiseEnabled = true
        c.whiteNoiseRestored = false
        File(context.noBackupFilesDir, "marmot").mkdirs()
        WhiteNoiseService.start(context)
    }

    fun restoredWithoutIdentity(enabled: Boolean, localState: Boolean) = enabled && !localState

    fun joinedGroups(): List<GroupInfo> =
        MarmotHost.groups.value.filter { it.joined }.map { GroupInfo(it.id, it.name) }

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

    /** How fast this group's voice messages play: its own setting, else the app's default. */
    fun speedFor(context: Context, groupId: String): Float =
        GroupSettingsStore(context).get(groupId).speed ?: context.config.playbackSpeed

    /** Plays one message (from the list, a notification or the car). */
    suspend fun play(context: Context, m: IncomingMessage, audio: Boolean = true, text: Boolean = true) {
        val speaker = ReplySpeaker.get(context)
        val speed = speedFor(context, m.groupId)
        try {
            if (audio) for (path in m.audio) File(path).takeIf { it.exists() }?.let { speaker.play(it, speed) }
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

    /** Marks every unread message of the group read (or of all groups, without one). */
    suspend fun markAllRead(context: Context, groupId: String? = null) {
        IncomingStore(context).load()
            .filter { !it.read && it.ignoredBy == null && (groupId == null || it.groupId == groupId) }
            .groupBy { it.groupId }
            .forEach { (g, list) -> markRead(context, g, list.map { it.id }) }
    }

    /**
     * The voice of a conversation message as local files: the copy saved on arrival when there is
     * one, otherwise downloaded (also Third Ear's own and older messages) into the cache.
     */
    suspend fun audioFiles(context: Context, groupId: String, m: MarmotHost.TimelineMessage): List<File> {
        val saved = IncomingStore(context).get(m.id)?.audio.orEmpty().map(::File).filter { it.exists() }
        if (saved.isNotEmpty()) return saved
        val dir = File(context.cacheDir, "wn-media").apply { mkdirs() }
        return m.audio.mapIndexedNotNull { i, ref ->
            val f = File(dir, "${m.id.take(16)}-$i.${ref.fileName.substringAfterLast('.', "m4a").take(5)}")
            if (!f.exists()) {
                runCatching { File(f.path + ".part").apply { writeBytes(MarmotHost.download(groupId, ref)) }.renameTo(f) }
                    .onFailure { Log.w(TAG, "download failed: ${it.message}") }
            }
            f.takeIf { it.exists() }
        }
    }

    suspend fun markRead(context: Context, groupId: String, ids: List<String>) {
        IncomingStore(context).markRead(ids)
        for (id in ids) runCatching { MarmotHost.markRead(groupId, id) }
        val settings = GroupSettingsStore(context).get(groupId)
        MessageNotifier.post(context, groupId, offerPlay = true, offerRead = settings.replies == ReplyMode.VOICE_AND_TEXT, alert = false)
    }
}
