package app.thirdear.support

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Port of the shared support-ask engine (~/projects/support-widget/spec/engine.mjs): pure functions over
 * a small state, no I/O, no network, no clock of its own. Must pass spec/engine-vectors.json
 * (SupportEngineTest). Days are ISO dates ("2026-11-14") in the user's local calendar.
 */
object SupportEngine {

    data class State(
        val activeDays: Int = 0,
        val lastActiveDay: String? = null,
        val actions: Int = 0,
        val asks: Int = 0,
        val lastAskDay: String? = null,
        val supporter: Boolean = false,
        val declined: Boolean = false,
    )

    data class Trigger(val minDays: Int, val minActions: Int, val cooldownDays: List<Int>, val maxAsks: Int)

    /**
     * momentOfValue: the call site is right after a successful action, on a result surface.
     * busy: the user is in the middle of something (recording, processing).
     * isSupporter: the app's own knowledge (Third Ear has none).
     */
    data class Context(val today: String, val momentOfValue: Boolean, val busy: Boolean = false, val isSupporter: Boolean = false)

    val DEFAULT_TRIGGER = Trigger(minDays = 3, minActions = 3, cooldownDays = listOf(14, 45), maxAsks = 3)

    /** Third Ear's trigger (spec/ask.md section 7): five days of use, ten finished recordings. */
    val THIRD_EAR = DEFAULT_TRIGGER.copy(minDays = 5, minActions = 10)

    fun daysBetween(fromIso: String, toIso: String): Long = ChronoUnit.DAYS.between(LocalDate.parse(fromIso), LocalDate.parse(toIso))

    /** The app came to the foreground. Counts one day of use per calendar day, never per launch. */
    fun onLaunch(s: State, today: String): State =
        if (s.lastActiveDay == today) s else s.copy(activeDays = s.activeDays + 1, lastActiveDay = today)

    /** One successful core action: a recording finished its line's pipeline. */
    fun onAction(s: State): State = s.copy(actions = s.actions + 1)

    /** The ask was put on screen. Counted when shown, so a dismissal needs no extra bookkeeping. */
    fun onShown(s: State, today: String): State = s.copy(asks = s.asks + 1, lastAskDay = today)

    /** The user tapped Support. The checkout opens in the browser; nobody asks again. */
    fun onSupportTapped(s: State): State = s.copy(supporter = true)

    /** The user said "don't ask again". Nothing is ever shown again; Setup keeps a plain Support entry. */
    fun onDeclined(s: State): State = s.copy(declined = true)

    @Suppress("ReturnCount")
    fun shouldAsk(s: State, ctx: Context, trigger: Trigger = DEFAULT_TRIGGER): Boolean {
        if (s.supporter || s.declined || ctx.isSupporter) return false
        if (!ctx.momentOfValue || ctx.busy) return false
        if (s.activeDays < trigger.minDays || s.actions < trigger.minActions) return false
        if (s.asks >= trigger.maxAsks) return false
        if (s.asks > 0) {
            val wait = trigger.cooldownDays[minOf(s.asks - 1, trigger.cooldownDays.size - 1)]
            if (s.lastAskDay == null || daysBetween(s.lastAskDay, ctx.today) < wait) return false
        }
        return true
    }
}
