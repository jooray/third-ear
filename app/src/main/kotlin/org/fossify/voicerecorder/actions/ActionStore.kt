package org.fossify.voicerecorder.actions

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The user's actions, in button order, plus which ones the Quick Settings tiles and the widget
 * use. Stored as JSON in SharedPreferences; [changes] lets screens follow edits.
 */
class ActionStore(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    companion object {
        const val PREFS = "third_ear_actions"
        private const val KEY_LIST = "actions"
        private const val KEY_TILE = "tile_"
        private const val KEY_WIDGET = "widget_action"
        const val TILE_SLOTS = 3

        private val version = MutableStateFlow(0)
        val changes: StateFlow<Int> get() = version

        /** First run: a plain save and a transcribe-and-copy, so the screen is useful at once. */
        fun defaults(saveName: String, transcriptName: String) = listOf(
            RecordAction(id = "save", name = saveName, destination = Destination.SAVE),
            RecordAction(id = "transcript", name = transcriptName, destination = Destination.COPY_TRANSCRIPT),
        )
    }

    /** Null until the first [all] with defaults stored them. */
    fun stored(): List<RecordAction>? =
        if (prefs.contains(KEY_LIST)) RecordAction.listFromJson(prefs.getString(KEY_LIST, null)) else null

    fun all(defaults: () -> List<RecordAction>): List<RecordAction> =
        stored() ?: defaults().also { save(it) }

    fun get(id: String?): RecordAction? = id?.let { i -> stored()?.firstOrNull { it.id == i } }

    fun save(list: List<RecordAction>) {
        prefs.edit().putString(KEY_LIST, RecordAction.listToJson(list)).apply()
        version.value++
    }

    fun upsert(action: RecordAction) {
        val list = stored().orEmpty().toMutableList()
        val i = list.indexOfFirst { it.id == action.id }
        if (i >= 0) list[i] = action else list += action
        save(list)
    }

    fun delete(id: String) {
        save(stored().orEmpty().filterNot { it.id == id })
        for (slot in 1..TILE_SLOTS) if (prefs.getString(KEY_TILE + slot, null) == id) setTile(slot, null)
        if (prefs.getString(KEY_WIDGET, null) == id) widgetActionId = null
    }

    fun move(id: String, by: Int) {
        val list = stored().orEmpty().toMutableList()
        val i = list.indexOfFirst { it.id == id }
        val j = i + by
        if (i < 0 || j !in list.indices) return
        list.add(j, list.removeAt(i))
        save(list)
    }

    /** The action tile [slot] (1-based) runs: the one chosen, else the slot-th action. */
    fun tileAction(slot: Int): RecordAction? {
        val list = stored().orEmpty()
        val chosen = prefs.getString(KEY_TILE + slot, null)
        return list.firstOrNull { it.id == chosen } ?: list.getOrNull(slot - 1)
    }

    fun setTile(slot: Int, id: String?) {
        prefs.edit().apply { if (id == null) remove(KEY_TILE + slot) else putString(KEY_TILE + slot, id) }.apply()
        version.value++
    }

    var widgetActionId: String?
        get() = prefs.getString(KEY_WIDGET, null)
        set(v) {
            prefs.edit().apply { if (v == null) remove(KEY_WIDGET) else putString(KEY_WIDGET, v) }.apply()
            version.value++
        }

    /** The widget's (and a plain "start recording" request's) action: chosen, else the first. */
    fun defaultAction(): RecordAction? {
        val list = stored().orEmpty()
        return list.firstOrNull { it.id == widgetActionId } ?: list.firstOrNull()
    }
}
