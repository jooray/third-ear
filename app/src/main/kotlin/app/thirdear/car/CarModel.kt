package app.thirdear.car

import app.thirdear.actions.RecordAction
import app.thirdear.audio.EngineStatus

/**
 * What the car screens show, decided without the Car App Library so it can be tested. The car
 * host limits how often a template may change, so the recording screen refreshes on state
 * changes (source, waiting, paused) and otherwise every few seconds for the clock.
 */
object CarModel {

    /** Android Auto shows at most this many grid items (6 on many head units, more on some). */
    const val MAX_GRID = 6
    const val REFRESH_MS = 5_000L

    data class Tile(val actionId: String, val title: String)

    data class StopRow(val actionId: String, val title: String, val subtitle: String, val startedWith: Boolean)

    fun grid(actions: List<RecordAction>): List<Tile> = actions.take(MAX_GRID).map { Tile(it.id, it.name) }

    /** The started action first: the most likely stop. */
    fun stopRows(actions: List<RecordAction>, startedWithId: String, describe: (RecordAction) -> String, stopLabel: (String) -> String): List<StopRow> =
        actions.sortedByDescending { it.id == startedWithId }.take(MAX_GRID).map {
            StopRow(it.id, stopLabel(it.name), describe(it), it.id == startedWithId)
        }

    fun duration(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    fun needsRefresh(shown: EngineStatus?, now: EngineStatus, shownAt: Long, nowMs: Long): Boolean = when {
        shown == null -> true
        shown.source != now.source || shown.input != now.input || shown.waitingForBluetooth != now.waitingForBluetooth ||
            shown.paused != now.paused || shown.switching != now.switching || shown.starting != now.starting || shown.silent != now.silent -> true

        else -> nowMs - shownAt >= REFRESH_MS
    }
}
