package app.thirdear.car

import app.thirdear.actions.RecordAction

/**
 * The Android Auto media screens, decided without the media framework so they can be tested.
 *
 * Android Auto shows a sideloaded *media* app (Unknown sources) but never a sideloaded *template*
 * app (docs/ANDROID-AUTO-PLAY-CHECK.md), so [RecorderMediaService] presents recording as media:
 * a browse tab of lines (tap = record with it; while recording, tap = stop → that line), the
 * Now Playing screen as the recording screen (pause, stop and save, discard), and the car's
 * steering wheel buttons on the session. Android Auto opens on the line list while nothing records.
 */
object CarMedia {

    const val ROOT = "root"
    const val TAB_RECORD = "tab:record"
    const val TAB_MESSAGES = "tab:messages"
    private const val REC = "rec:"
    private const val MSG = "msg:"

    /** Lines in the record tab; Android Auto cuts longer lists while driving anyway. */
    const val MAX_LINES = 8

    const val MAX_MESSAGES = 10

    const val CUSTOM_SAVE = "app.thirdear.car.SAVE"
    const val CUSTOM_DISCARD = "app.thirdear.car.DISCARD"

    fun recordId(actionId: String?) = REC + actionId.orEmpty()
    fun messageId(id: String) = MSG + id

    data class Item(val mediaId: String, val title: String, val subtitle: String, val actionIndex: Int?)

    /**
     * The record tab. Idle: the lines (tap = record with it). Recording: "Stop → line" for each
     * line, the started one first, so the list does what the template car screen's stop rows do.
     * There is no "record now, choose at the stop" entry: Now Playing can only stop to the started
     * line, so its choice would be hidden behind the back arrow.
     */
    fun recordItems(
        actions: List<RecordAction>,
        startedWithId: String?,
        describe: (RecordAction) -> String,
        stopLabel: (String) -> String,
    ): List<Item> = actions.withIndex()
        .sortedByDescending { it.value.id == startedWithId }
        .take(MAX_LINES)
        .map { (i, a) -> Item(recordId(a.id), if (startedWithId == null) a.name else stopLabel(a.name), describe(a), i) }

    sealed interface Command {
        data class Start(val actionId: String?) : Command
        data class Stop(val actionId: String?) : Command
        data object Discard : Command
        data object TogglePause : Command
        data class PlayMessage(val messageId: String) : Command
        data object None : Command
    }

    /** A tap on a browse item. */
    fun onTap(mediaId: String?, recording: Boolean): Command = when {
        mediaId == null -> Command.None
        mediaId.startsWith(REC) -> {
            val id = mediaId.removePrefix(REC).ifEmpty { null }
            if (recording) Command.Stop(id) else Command.Start(id)
        }

        mediaId.startsWith(MSG) -> Command.PlayMessage(mediaId.removePrefix(MSG))
        else -> Command.None
    }

    /**
     * Play resumes a paused recording and never starts one: Android Auto sends "play" to the last
     * media app when the car connects, and a headset's button may land here too.
     */
    fun onPlay(recording: Boolean, paused: Boolean): Command = if (recording && paused) Command.TogglePause else Command.None

    fun onPause(recording: Boolean, paused: Boolean): Command = if (recording && !paused) Command.TogglePause else Command.None

    /** Stop and the steering wheel's "next" save with the started line. */
    fun onStop(recording: Boolean): Command = if (recording) Command.Stop(null) else Command.None

    fun onCustom(action: String, recording: Boolean): Command = when {
        !recording -> Command.None
        action == CUSTOM_SAVE -> Command.Stop(null)
        action == CUSTOM_DISCARD -> Command.Discard
        else -> Command.None
    }
}
