package app.thirdear.ui

import android.content.Context
import app.thirdear.R
import app.thirdear.actions.ActionPlan
import app.thirdear.actions.Content
import app.thirdear.actions.Destination
import app.thirdear.actions.LineCode
import app.thirdear.actions.RecordAction
import app.thirdear.extensions.describe
import app.thirdear.pipeline.Note
import app.thirdear.pipeline.NoteState
import app.thirdear.pipeline.NoteStep
import app.thirdear.whitenoise.IncomingMessage

/** What a line lamp says. Never alone: a word travels with every lit lamp. */
enum class LampState { OFF, BUSY, REPLY, FAULT }

/** One action as the board draws it. */
data class Line(
    val action: RecordAction,
    val code: String,
    val cord: Int,
    val strip: List<String>,
    val lamp: LampState,
    val word: String?,
    val unread: Int,
    /** What the key does, read by TalkBack. */
    val description: String,
)

fun cordOf(action: RecordAction, index: Int): Int = action.cord ?: index

/** The designation strip of an action: what happens after the stop, in printed cells. */
fun Context.stripCells(action: RecordAction): List<String> {
    val plan = ActionPlan.of(action)
    val content = when (action.content) {
        Content.AUDIO -> getString(R.string.te_strip_voice)
        Content.AUDIO_AND_TRANSCRIPT -> getString(R.string.te_strip_voice_text)
        Content.TRANSCRIPT -> getString(R.string.te_strip_text)
    }
    return buildList {
        if (plan.transcribe) add(getString(R.string.te_strip_ostt))
        when (action.destination) {
            Destination.SAVE -> if (!plan.transcribe) add(getString(R.string.te_strip_save_only)) else add(getString(R.string.te_strip_save))
            Destination.COPY_TRANSCRIPT -> add(getString(R.string.te_strip_copy))
            Destination.SHARE -> { add(getString(R.string.te_strip_share)); add(content) }
            Destination.WHITE_NOISE -> { add(action.groupName ?: getString(R.string.te_strip_no_group)); add(content) }
            Destination.WEBHOOK -> { add(getString(R.string.te_strip_webhook)); add(content) }
        }
        if (plan.copyTranscript && action.destination != Destination.COPY_TRANSCRIPT) add(getString(R.string.te_strip_copy))
    }
}

/** The step a busy note is in, as the word under its lamp. */
fun Context.stepWord(n: Note): String = when (n.step) {
    NoteStep.TRANSCRIBING -> getString(R.string.te_word_transcribing)
    NoteStep.WAITING_FOR_TRANSCRIBER -> getString(R.string.te_word_waiting_ostt)
    NoteStep.SENDING -> getString(R.string.te_word_sending)
    else -> getString(R.string.te_word_saving)
}

/**
 * Lamps for every line: busy while a recording that stopped on it is being processed, white while
 * replies from its group wait, red when its last recording was not delivered.
 */
fun Context.lines(actions: List<RecordAction>, notes: List<Note>, messages: List<IncomingMessage>): List<Line> =
    actions.mapIndexed { i, a ->
        val own = notes.filter { it.action.id == a.id && it.state != NoteState.RECORDING }
        val busy = own.firstOrNull { it.state == NoteState.PROCESSING }
        val unread = if (a.destination == Destination.WHITE_NOISE && a.groupId != null) {
            messages.count { it.groupId == a.groupId && !it.read && it.ignoredBy == null }
        } else {
            0
        }
        val failed = own.firstOrNull()?.state == NoteState.FAILED
        val (lamp, word) = when {
            busy != null -> LampState.BUSY to stepWord(busy)
            unread > 0 -> LampState.REPLY to resources.getQuantityString(R.plurals.te_word_replies, unread, unread)
            failed -> LampState.FAULT to getString(R.string.te_word_not_delivered)
            else -> LampState.OFF to null
        }
        Line(
            action = a,
            code = LineCode.of(a),
            cord = cordOf(a, i),
            strip = stripCells(a),
            lamp = lamp,
            word = word,
            unread = unread,
            description = listOfNotNull(a.name, word, describe(a)).joinToString(". "),
        )
    }
