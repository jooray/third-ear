package app.thirdear.shortcuts

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Icon
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.res.ResourcesCompat
import app.thirdear.R
import app.thirdear.actions.LineCode
import app.thirdear.actions.RecordAction
import app.thirdear.activities.BackgroundRecordActivity
import app.thirdear.activities.ShortcutActivity
import app.thirdear.extensions.recordActions
import app.thirdear.ui.Cords
import app.thirdear.ui.cordOf
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * App shortcuts: the long-press menu on the launcher icon (the first lines plus "Stop recording"),
 * and the shortcuts the picker ([ShortcutPickerActivity]) hands to launchers, automation and
 * button-mapping apps. All of them open [ShortcutActivity], which is exported; the intent carries
 * a per-install token so that other apps cannot start a recording that sends somewhere.
 */
object LineShortcuts {
    /** Lines in the long-press menu; launchers show about four entries. */
    const val MENU_LINES = 4

    const val EXTRA_TOKEN = "app.thirdear.shortcut.TOKEN"
    private const val PREFS = "third_ear_shortcuts"
    private const val KEY_TOKEN = "token"
    const val STOP_ID = "stop"
    private const val LINE_PREFIX = "line_"

    fun lineShortcutId(actionId: String) = LINE_PREFIX + actionId

    /** The line id inside a shortcut id, null for the stop shortcut or anything else. */
    fun lineIdOf(shortcutId: String): String? = shortcutId.takeIf { it.startsWith(LINE_PREFIX) }?.removePrefix(LINE_PREFIX)?.ifEmpty { null }

    private fun token(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_TOKEN, null)?.let { return it }
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val t = bytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putString(KEY_TOKEN, t).apply()
        return t
    }

    /** True when [intent] came from one of Third Ear's own shortcuts. */
    fun isOurs(context: Context, intent: Intent): Boolean {
        val given = intent.getStringExtra(EXTRA_TOKEN) ?: return false
        return MessageDigest.isEqual(given.toByteArray(), token(context).toByteArray())
    }

    fun lineIntent(context: Context, actionId: String): Intent = Intent(context, ShortcutActivity::class.java)
        .setAction(BackgroundRecordActivity.RECORD_INTENT_ACTION)
        .putExtra(BackgroundRecordActivity.EXTRA_ACTION_ID, actionId)
        .putExtra(EXTRA_TOKEN, token(context))

    fun stopIntent(context: Context): Intent = Intent(context, ShortcutActivity::class.java)
        .setAction(BackgroundRecordActivity.STOP_INTENT_ACTION)
        .putExtra(EXTRA_TOKEN, token(context))

    fun lineInfo(context: Context, action: RecordAction, index: Int, rank: Int = index): ShortcutInfo =
        ShortcutInfo.Builder(context, lineShortcutId(action.id))
            .setShortLabel(action.name)
            .setLongLabel(context.getString(R.string.te_tile_label, action.name))
            .setIcon(Icon.createWithAdaptiveBitmap(lineBitmap(context, action, index)))
            .setIntent(lineIntent(context, action.id))
            .setRank(rank)
            .build()

    fun stopInfo(context: Context, rank: Int = MENU_LINES): ShortcutInfo =
        ShortcutInfo.Builder(context, STOP_ID)
            .setShortLabel(context.getString(R.string.te_shortcut_stop))
            .setLongLabel(context.getString(R.string.te_shortcut_stop))
            .setIcon(Icon.createWithAdaptiveBitmap(stopBitmap(context)))
            .setIntent(stopIntent(context))
            .setRank(rank)
            .build()

    /**
     * Publishes the long-press menu and brings pinned shortcuts up to date: renamed or recoloured
     * lines follow, deleted lines are disabled with a word why. Cheap; called whenever lines
     * change, on launch and after an update.
     */
    fun publish(context: Context) {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        runCatching {
            val all = context.recordActions()
            val room = (manager.maxShortcutCountPerActivity - 1).coerceAtLeast(0)
            val menu = all.take(minOf(MENU_LINES, room)).mapIndexed { i, a -> lineInfo(context, a, i) }
            manager.setDynamicShortcuts(menu + stopInfo(context, rank = menu.size))

            val pinned = manager.pinnedShortcuts
            val updates = pinned.mapNotNull { s ->
                if (s.id == STOP_ID) return@mapNotNull stopInfo(context)
                val id = lineIdOf(s.id) ?: return@mapNotNull null
                val i = all.indexOfFirst { it.id == id }
                if (i >= 0) lineInfo(context, all[i], i) else null
            }
            if (updates.isNotEmpty()) manager.updateShortcuts(updates)
            val gone = pinned.map { it.id }.filter { id -> lineIdOf(id)?.let { l -> all.none { it.id == l } } == true }
            if (gone.isNotEmpty()) manager.disableShortcuts(gone, context.getString(R.string.te_shortcut_line_gone))
        }
    }

    /**
     * What the picker returns: a pinned shortcut for launchers that take one, plus the legacy
     * extras (intent, name, icon) that automation and button-mapping apps read.
     */
    @Suppress("DEPRECATION")
    fun resultIntent(context: Context, info: ShortcutInfo, legacyIcon: Bitmap): Intent {
        val manager = context.getSystemService(ShortcutManager::class.java)
        val result = runCatching { manager?.createShortcutResultIntent(info) }.getOrNull() ?: Intent()
        return result
            .putExtra(Intent.EXTRA_SHORTCUT_INTENT, info.intent)
            .putExtra(Intent.EXTRA_SHORTCUT_NAME, info.shortLabel)
            .putExtra(Intent.EXTRA_SHORTCUT_ICON, legacyIcon)
    }

    /** The line's key: its cord colour with its code, as on the Board. */
    fun lineBitmap(context: Context, action: RecordAction, index: Int, adaptive: Boolean = true): Bitmap {
        val cord = cordOf(action, index)
        return keyBitmap(context, Cords.color(cord).toArgb(), Cords.ink(cord).toArgb(), adaptive) { canvas, size, ink ->
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ink
                textAlign = Paint.Align.CENTER
                typeface = runCatching { ResourcesCompat.getFont(context, R.font.sairacondensed_bold) }.getOrNull() ?: Typeface.DEFAULT_BOLD
                textSize = size * 0.30f
            }
            val code = LineCode.of(action)
            // four wide letters must stay inside the launcher's mask
            val maxWidth = size * if (adaptive) 0.52f else 0.78f
            val w = paint.measureText(code)
            if (w > maxWidth) paint.textSize *= maxWidth / w
            val y = size / 2f - (paint.descent() + paint.ascent()) / 2f
            canvas.drawText(code, size / 2f, y, paint)
        }
    }

    /** A stop key: a square on the dark face. */
    fun stopBitmap(context: Context, adaptive: Boolean = true): Bitmap =
        keyBitmap(context, STOP_FACE, STOP_INK, adaptive) { canvas, size, ink ->
            val half = size * if (adaptive) 0.13f else 0.2f
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink }
            val r = size * 0.025f
            canvas.drawRoundRect(RectF(size / 2f - half, size / 2f - half, size / 2f + half, size / 2f + half), r, r, paint)
        }

    private fun keyBitmap(context: Context, fill: Int, ink: Int, adaptive: Boolean, draw: (Canvas, Float, Int) -> Unit): Bitmap {
        val density = context.resources.displayMetrics.density
        // adaptive icons are 108 dp with the visible part in the middle; the legacy icon is 48 dp
        val size = ((if (adaptive) 108 else 48) * density).toInt().coerceAtLeast(48)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill }
        if (adaptive) {
            canvas.drawColor(fill)
        } else {
            val r = size * 0.18f
            canvas.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), r, r, paint)
        }
        draw(canvas, size.toFloat(), ink)
        return bitmap
    }

    private const val STOP_FACE = 0xFF17140F.toInt()
    private const val STOP_INK = 0xFFD63A2B.toInt()
}
