package app.thirdear.helpers

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import app.thirdear.R
import app.thirdear.actions.ActionStore
import app.thirdear.actions.LineCode
import app.thirdear.activities.BackgroundRecordActivity
import app.thirdear.extensions.recordActions
import app.thirdear.services.RecorderService
import app.thirdear.ui.Cords
import app.thirdear.ui.cordOf
import app.thirdear.ui.stripCells

/**
 * The home-screen widget: the widget line (Setup → Tiles and widget) as a key. Tap to record on it;
 * while recording the lamp is lit and a tap stops on that line.
 */
class MyWidgetRecordDisplayProvider : AppWidgetProvider() {
    companion object {
        private const val OPEN_APP_INTENT_ID = 1
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        render(context, appWidgetManager, RecorderService.isRunning)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == TOGGLE_WIDGET_UI && intent.extras?.containsKey(IS_RECORDING) == true) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            render(context, manager, intent.getBooleanExtra(IS_RECORDING, false))
        } else {
            super.onReceive(context, intent)
        }
    }

    private fun render(context: Context, manager: AppWidgetManager, recording: Boolean) {
        val all = context.recordActions()
        val action = ActionStore(context).defaultAction() ?: all.firstOrNull() ?: return
        val cord = cordOf(action, all.indexOfFirst { it.id == action.id }.coerceAtLeast(0))
        val views = RemoteViews(context.packageName, R.layout.widget_record_display).apply {
            setTextViewText(R.id.widget_code, LineCode.of(action))
            setColorStateList(R.id.widget_code, "setBackgroundTintList", ColorStateList.valueOf(Cords.color(cord).toArgb()))
            setTextColor(R.id.widget_code, Cords.ink(cord).toArgb())
            setTextViewText(R.id.widget_name, action.name)
            setTextViewText(R.id.widget_state, if (recording) context.getString(R.string.recording) else "")
            setImageViewResource(R.id.widget_lamp, if (recording) R.drawable.widget_lamp_on else R.drawable.widget_lamp)
            setTextViewText(
                R.id.widget_strip,
                if (recording) context.getString(R.string.te_widget_stop) else context.stripCells(action).joinToString(" · "),
            )
            val tap = Intent(context, BackgroundRecordActivity::class.java)
                .setAction(BackgroundRecordActivity.RECORD_INTENT_ACTION)
                .putExtra(BackgroundRecordActivity.EXTRA_ACTION_ID, action.id)
            setOnClickPendingIntent(
                R.id.record_display_btn,
                PendingIntent.getActivity(context, OPEN_APP_INTENT_ID, tap, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
            )
            setContentDescription(R.id.record_display_btn, if (recording) context.getString(R.string.te_stop_with, action.name) else action.name)
        }
        manager.updateAppWidget(ComponentName(context, MyWidgetRecordDisplayProvider::class.java), views)
    }
}
