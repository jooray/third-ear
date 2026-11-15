package app.thirdear.support

import android.content.Context
import android.content.Intent
import android.net.Uri
import app.thirdear.support.SupportEngine.State
import java.time.LocalDate
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * The in-app support ask (spec: ~/projects/support-widget/spec/ask.md). Decides locally, stores one JSON
 * value, and opens the checkout in the browser. No network code of its own.
 */
object SupportAsk {
    private const val PREFS = "support_prompt"
    private const val KEY = "support.v1"
    const val REF_LOG = "recordings-footer"
    const val REF_MENU = "menu"

    private val shown = MutableStateFlow(false)

    /** True while the strip is on screen (until Support, Not now or Don't ask again). */
    val visible: StateFlow<Boolean> = shown.asStateFlow()

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    @Synchronized
    internal fun load(context: Context): State = SupportState.decode(prefs(context).getString(KEY, null))

    @Synchronized
    private fun update(context: Context, change: (State) -> State) {
        val next = change(load(context))
        prefs(context).edit().putString(KEY, SupportState.encode(next)).apply()
    }

    /** The app came to the foreground. */
    fun onLaunch(context: Context) = update(context) { SupportEngine.onLaunch(it, LocalDate.now().toString()) }

    /** A recording finished its line's pipeline successfully (once per recording). */
    fun onAction(context: Context) = update(context) { SupportEngine.onAction(it) }

    /**
     * The Log's recordings list is on screen: a result surface. Puts the strip up when the engine
     * says so; it stays until the user answers it, and is counted once.
     */
    @Synchronized
    fun consider(context: Context, busy: Boolean) {
        if (shown.value) return
        val ctx = SupportEngine.Context(today = LocalDate.now().toString(), momentOfValue = true, busy = busy)
        if (!SupportEngine.shouldAsk(load(context), ctx, SupportEngine.THIRD_EAR)) return
        update(context) { SupportEngine.onShown(it, ctx.today) }
        shown.value = true
    }

    fun support(context: Context, dark: Boolean) {
        update(context) { SupportEngine.onSupportTapped(it) }
        shown.value = false
        SupportCheckout.open(context, REF_LOG, dark)
    }

    fun later() {
        shown.value = false
    }

    fun never(context: Context) {
        update(context) { SupportEngine.onDeclined(it) }
        shown.value = false
    }
}

/** The checkout page, opened in the browser. Never a WebView, never a request of its own. */
object SupportCheckout {
    private const val CHECKOUT = "https://hackyourself.io/support/s/third-ear"

    /** The checkout: lang, theme, ref and nothing else (no id, no key, no version). */
    fun url(ref: String, dark: Boolean, language: String = Locale.getDefault().language): String {
        val lang = if (language in setOf("sk", "cs")) language else "en"
        val theme = if (dark) "dark" else "light"
        return "$CHECKOUT?lang=$lang&theme=$theme&ref=$ref"
    }

    /** Opens the checkout in the default browser; the menu entry calls this alone and changes no state. */
    fun open(context: Context, ref: String, dark: Boolean) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url(ref, dark))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

/** support.v1 as stored: one JSON value (spec/ask.md section 8). */
internal object SupportState {
    /** Corrupt or missing state is a fresh start, never a crash and never an early ask. */
    fun decode(raw: String?): State = runCatching {
        val o = JSONObject(raw ?: return State())
        State(
            activeDays = o.optInt("activeDays", 0),
            lastActiveDay = o.optString("lastActiveDay").takeIf { !o.isNull("lastActiveDay") && it.isNotEmpty() },
            actions = o.optInt("actions", 0),
            asks = o.optInt("asks", 0),
            lastAskDay = o.optString("lastAskDay").takeIf { !o.isNull("lastAskDay") && it.isNotEmpty() },
            supporter = o.optBoolean("supporter", false),
            declined = o.optBoolean("declined", false),
        )
    }.getOrDefault(State())

    fun encode(s: State): String = JSONObject()
        .put("activeDays", s.activeDays)
        .put("lastActiveDay", s.lastActiveDay ?: JSONObject.NULL)
        .put("actions", s.actions)
        .put("asks", s.asks)
        .put("lastAskDay", s.lastAskDay ?: JSONObject.NULL)
        .put("supporter", s.supporter)
        .put("declined", s.declined)
        .toString()
}
