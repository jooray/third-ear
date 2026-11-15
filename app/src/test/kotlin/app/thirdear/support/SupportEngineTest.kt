package app.thirdear.support

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

/**
 * Replays the shared vectors (copied from ~/projects/support-widget/spec/engine-vectors.json; refresh
 * the copy whenever the spec changes) against the Kotlin port of the engine.
 */
class SupportEngineTest {

    private val vectors = JSONObject(javaClass.getResource("/support/engine-vectors.json")!!.readText())

    private fun trigger(o: JSONObject) = SupportEngine.Trigger(
        minDays = o.getInt("minDays"),
        minActions = o.getInt("minActions"),
        cooldownDays = o.getJSONArray("cooldownDays").let { a -> List(a.length()) { a.getInt(it) } },
        maxAsks = o.getInt("maxAsks"),
    )

    /** Replays one case; returns the failed ask steps. */
    private fun replay(c: JSONObject, trigger: SupportEngine.Trigger): List<String> {
        var s = SupportEngine.State()
        val failures = mutableListOf<String>()
        val steps: JSONArray = c.getJSONArray("steps")
        for (j in 0 until steps.length()) {
            val step = steps.getJSONArray(j)
            when (val op = step.getString(0)) {
                "launch" -> s = SupportEngine.onLaunch(s, step.getString(1))
                "action" -> repeat(step.getInt(1)) { s = SupportEngine.onAction(s) }
                "shown" -> s = SupportEngine.onShown(s, step.getString(1))
                "tapped" -> s = SupportEngine.onSupportTapped(s)
                "declined" -> s = SupportEngine.onDeclined(s)
                "ask" -> {
                    val ctx = context(step.getJSONObject(1))
                    val got = SupportEngine.shouldAsk(s, ctx, trigger)
                    if (got != step.getBoolean(2)) failures += "${c.getString("name")} @ ${ctx.today}: got $got"
                }
                else -> error("unknown op $op")
            }
        }
        return failures
    }

    private fun context(o: JSONObject) = SupportEngine.Context(
        today = o.getString("today"),
        momentOfValue = o.optBoolean("momentOfValue", false),
        busy = o.optBoolean("busy", false),
        isSupporter = o.optBoolean("isSupporter", false),
    )

    @Test
    fun `every shared vector passes`() {
        val trigger = trigger(vectors.getJSONObject("trigger"))
        val cases = vectors.getJSONArray("cases")
        val failures = (0 until cases.length()).flatMap { replay(cases.getJSONObject(it), trigger) }
        assertTrue(cases.length() >= 14, "vectors file looks truncated")
        assertEquals(emptyList(), failures)
    }

    @Test
    fun `third ear waits for five days and ten recordings`() {
        var s = SupportEngine.State()
        for (d in 1..4) s = SupportEngine.onLaunch(s, "2026-11-0$d")
        repeat(20) { s = SupportEngine.onAction(s) }
        val ctx = SupportEngine.Context(today = "2026-11-04", momentOfValue = true)
        assertFalse(SupportEngine.shouldAsk(s, ctx, SupportEngine.THIRD_EAR))
        s = SupportEngine.onLaunch(s, "2026-11-05")
        assertTrue(SupportEngine.shouldAsk(s, ctx.copy(today = "2026-11-05"), SupportEngine.THIRD_EAR))
        assertFalse(SupportEngine.shouldAsk(s, ctx.copy(today = "2026-11-05", busy = true), SupportEngine.THIRD_EAR))
    }

    @Test
    fun `stored state survives a round trip and garbage is a fresh start`() {
        val s = SupportEngine.State(activeDays = 6, lastActiveDay = "2026-11-15", actions = 12, asks = 1, lastAskDay = "2026-11-10", declined = true)
        assertEquals(s, SupportState.decode(SupportState.encode(s)))
        assertEquals(SupportEngine.State(), SupportState.decode(SupportState.encode(SupportEngine.State())))
        assertEquals(SupportEngine.State(), SupportState.decode("{not json"))
        assertEquals(SupportEngine.State(), SupportState.decode(null))
    }

    @Test
    fun `the checkout url carries lang theme and ref only`() {
        assertEquals(
            "https://hackyourself.io/support/s/third-ear?lang=sk&theme=dark&ref=recordings-footer",
            SupportCheckout.url(SupportAsk.REF_LOG, dark = true, language = "sk"),
        )
        assertEquals(
            "https://hackyourself.io/support/s/third-ear?lang=en&theme=light&ref=menu",
            SupportCheckout.url(SupportAsk.REF_MENU, dark = false, language = "de"),
        )
    }
}
