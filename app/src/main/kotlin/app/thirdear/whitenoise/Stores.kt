package app.thirdear.whitenoise

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import app.thirdear.actions.optStringOrNull
import org.json.JSONArray
import org.json.JSONObject

/** Per-group reply settings, keyed by group id. */
class GroupSettingsStore(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.applicationContext.getSharedPreferences("third_ear_groups", Context.MODE_PRIVATE))

    fun get(groupId: String): GroupSettings =
        GroupSettings.fromJson(prefs.getString(groupId, null)?.let { runCatching { JSONObject(it) }.getOrNull() })

    fun put(groupId: String, settings: GroupSettings) {
        prefs.edit().putString(groupId, settings.toJson().toString()).apply()
    }
}

/** One incoming message as the in-app list and the notifications show it. */
data class IncomingMessage(
    val id: String,
    val groupId: String,
    val groupName: String,
    val sender: String,
    val senderName: String?,
    val text: String,
    val at: Long,
    /** Voice attachments, downloaded on arrival so they play without network later. */
    val audio: List<String> = emptyList(),
    val read: Boolean = false,
    val ignoredBy: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("groupId", groupId).put("groupName", groupName).put("sender", sender)
        .putOpt("senderName", senderName).put("text", text).put("at", at)
        .put("audio", JSONArray(audio)).put("read", read).putOpt("ignoredBy", ignoredBy)

    companion object {
        fun fromJson(o: JSONObject): IncomingMessage {
            val a = o.optJSONArray("audio") ?: JSONArray()
            return IncomingMessage(
                id = o.getString("id"),
                groupId = o.getString("groupId"),
                groupName = o.optString("groupName"),
                sender = o.optString("sender"),
                senderName = o.optStringOrNull("senderName"),
                text = o.optString("text"),
                at = o.optLong("at"),
                audio = (0 until a.length()).map { a.getString(it) },
                read = o.optBoolean("read"),
                ignoredBy = o.optStringOrNull("ignoredBy"),
            )
        }
    }
}

/** The recent incoming messages (newest first), persisted in app storage with their audio. */
class IncomingStore(private val root: File) {
    constructor(context: Context) : this(File(context.filesDir, "incoming"))

    companion object {
        const val KEEP = 50
        private val state = MutableStateFlow<List<IncomingMessage>>(emptyList())
        val messages: StateFlow<List<IncomingMessage>> get() = state
    }

    private val index = File(root.apply { mkdirs() }, "index.json")

    fun audioFile(messageId: String, n: Int, ext: String) = File(root, "${messageId.take(16)}-$n.$ext")

    @Synchronized
    fun load(): List<IncomingMessage> {
        val list = runCatching {
            val a = JSONArray(index.readText())
            (0 until a.length()).map { IncomingMessage.fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        state.value = list
        return list
    }

    @Synchronized
    fun add(m: IncomingMessage) {
        val list = (listOf(m) + load().filterNot { it.id == m.id }).sortedByDescending { it.at }
        list.drop(KEEP).forEach { old -> old.audio.forEach { File(it).delete() } }
        save(list.take(KEEP))
    }

    @Synchronized
    fun markRead(ids: Collection<String>) {
        save(load().map { if (it.id in ids) it.copy(read = true) else it })
    }

    fun get(id: String): IncomingMessage? = load().firstOrNull { it.id == id }

    fun unread(groupId: String): List<IncomingMessage> = load().filter { it.groupId == groupId && !it.read && it.ignoredBy == null }

    private fun save(list: List<IncomingMessage>) {
        val tmp = File(index.path + ".tmp")
        tmp.writeText(JSONArray(list.map { it.toJson() }).toString())
        tmp.renameTo(index)
        state.value = list
    }
}
