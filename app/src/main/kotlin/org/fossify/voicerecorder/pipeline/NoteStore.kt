package org.fossify.voicerecorder.pipeline

import android.content.Context
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.fossify.voicerecorder.actions.RecordAction
import org.fossify.voicerecorder.actions.enumOr
import org.fossify.voicerecorder.actions.optStringOrNull
import org.fossify.voicerecorder.audio.Container
import org.json.JSONObject

enum class NoteState { RECORDING, PROCESSING, DONE, FAILED }

/** The pipeline step a note is in, for the progress notification and the last-recording card. */
enum class NoteStep { SAVING, TRANSCRIBING, WAITING_FOR_TRANSCRIBER, SENDING }

/**
 * One recording and what happened to it: the action it ran (a snapshot, so editing the action
 * later does not change a note in flight), where it was saved, the transcript and delivery.
 * Working files live in `filesDir/notes/<id>/`.
 */
data class Note(
    val id: String,
    val startedAt: Long,
    val action: RecordAction,
    val container: Container,
    /** File name without extension, from the user's pattern. */
    val baseName: String,
    val durationMs: Long = 0,
    val mic: String? = null,
    /** content: Uri (or path) of the user-visible copy. */
    val savedUri: String? = null,
    val savedName: String? = null,
    val transcript: Boolean = false,
    val transcriptError: String? = null,
    val state: NoteState = NoteState.RECORDING,
    val step: NoteStep? = null,
    val error: String? = null,
    /** Recovered after the process died mid-recording; saved only, never sent. */
    val recovered: Boolean = false,
) {
    val fileName: String get() = "$baseName.${container.extension}"

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("startedAt", startedAt).put("action", action.toJson())
        .put("container", container.name).put("baseName", baseName).put("durationMs", durationMs)
        .putOpt("mic", mic).putOpt("savedUri", savedUri).putOpt("savedName", savedName)
        .put("transcript", transcript).putOpt("transcriptError", transcriptError)
        .put("state", state.name).putOpt("step", step?.name).putOpt("error", error).put("recovered", recovered)

    companion object {
        fun fromJson(o: JSONObject) = Note(
            id = o.getString("id"),
            startedAt = o.optLong("startedAt"),
            action = RecordAction.fromJson(o.getJSONObject("action")),
            container = enumOr(o.optString("container"), Container.MPEG_4),
            baseName = o.optString("baseName"),
            durationMs = o.optLong("durationMs"),
            mic = o.optStringOrNull("mic"),
            savedUri = o.optStringOrNull("savedUri"),
            savedName = o.optStringOrNull("savedName"),
            transcript = o.optBoolean("transcript"),
            transcriptError = o.optStringOrNull("transcriptError"),
            state = enumOr(o.optString("state"), NoteState.FAILED),
            step = o.optStringOrNull("step")?.let { s -> NoteStep.entries.firstOrNull { it.name == s } },
            error = o.optStringOrNull("error"),
            recovered = o.optBoolean("recovered"),
        )
    }
}

class NoteStore(root: File) {

    constructor(context: Context) : this(File(context.filesDir, "notes"))

    companion object {
        /** Notes kept for the last-recording card and transcript lookup; older ones are pruned. */
        const val KEEP = 100
        private val all = MutableStateFlow<List<Note>>(emptyList())
        val notes: StateFlow<List<Note>> get() = all
    }

    private val root = root.apply { mkdirs() }

    fun dir(id: String) = File(root, id).apply { mkdirs() }

    /** The crash-safe working file the encoder writes (journal, or the MP3 itself). */
    fun work(note: Note) = File(dir(note.id), if (note.container == Container.MP3) "work.mp3" else "work.tej")

    /** The playable file, made by the finaliser. */
    fun audio(note: Note) = File(dir(note.id), "audio.${note.container.extension}")

    fun transcriptFile(id: String) = File(dir(id), "transcript.txt")

    fun transcript(id: String): String? = File(root, "$id/transcript.txt").takeIf { it.exists() }?.readText()?.ifBlank { null }

    @Synchronized
    fun put(note: Note) {
        val f = File(dir(note.id), "note.json")
        val tmp = File(f.path + ".tmp")
        tmp.writeText(note.toJson().toString())
        tmp.renameTo(f)
        reload()
    }

    fun get(id: String): Note? = read(File(root, id))

    private fun read(d: File): Note? = runCatching { Note.fromJson(JSONObject(File(d, "note.json").readText())) }.getOrNull()

    @Synchronized
    fun reload(): List<Note> {
        val list = root.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull(::read).sortedByDescending { it.startedAt }
        list.drop(KEEP).filter { it.state == NoteState.DONE || it.state == NoteState.FAILED }.forEach { File(root, it.id).deleteRecursively() }
        all.value = list.take(KEEP)
        return all.value
    }

    /** Notes the process died on, mid-recording or mid-pipeline. */
    fun unfinished(): List<Note> = reload().filter { it.state == NoteState.RECORDING || it.state == NoteState.PROCESSING }

    /** The note that saved a recording with this file name, for "Copy transcript" in the list. */
    fun bySavedName(name: String): Note? = all.value.firstOrNull { it.savedName == name }

    fun delete(id: String) {
        File(root, id).deleteRecursively()
        reload()
    }

    /** After a successful pipeline only the note and transcript are kept; the audio is in the user's folder. */
    fun dropAudio(note: Note) {
        work(note).delete()
        audio(note).delete()
    }
}
