package app.thirdear.tiles

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.thirdear.R
import app.thirdear.actions.ActionStore
import app.thirdear.activities.BackgroundRecordActivity
import app.thirdear.services.RecorderService

/**
 * A Quick Settings tile bound to one of the user's actions (slot 1–3, chosen in the settings,
 * else the action at that position). Idle: tap starts recording with it, through the invisible
 * trampoline activity, because Android lets only a visible app open the microphone. Recording:
 * tap stops and runs this tile's action.
 */
abstract class ActionTileService(private val slot: Int) : TileService() {

    companion object {
        private val all = listOf(ActionTile1::class.java, ActionTile2::class.java, ActionTile3::class.java)

        fun refreshAll(context: Context) {
            for (c in all) runCatching { requestListeningState(context, ComponentName(context, c)) }
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        update()
    }

    override fun onClick() {
        super.onClick()
        val action = ActionStore(this).tileAction(slot)
        if (RecorderService.isRunning) {
            RecorderService.stop(this, action?.id)
            update()
            return
        }
        if (action == null) return
        val start = { launch(action.id) }
        if (isLocked) unlockAndRun(start) else start()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun launch(actionId: String) {
        val intent = Intent(this, BackgroundRecordActivity::class.java)
            .setAction(BackgroundRecordActivity.RECORD_INTENT_ACTION)
            .putExtra(BackgroundRecordActivity.EXTRA_ACTION_ID, actionId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, slot, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun update() {
        val tile = qsTile ?: return
        val action = ActionStore(this).tileAction(slot)
        val live = RecorderService.live.value
        val recording = RecorderService.isRunning
        tile.icon = Icon.createWithResource(this, R.drawable.ic_microphone_widget_icon)
        tile.label = action?.name ?: getString(R.string.te_tile_no_action)
        tile.subtitle = when {
            recording && live?.action?.id == action?.id -> getString(R.string.te_tile_recording)
            recording -> getString(R.string.te_stop_with, action?.name ?: "")
            action != null -> app.thirdear.actions.LineCode.of(action) + " · " + getString(R.string.app_name)
            else -> getString(R.string.app_name)
        }
        tile.state = when {
            action == null -> Tile.STATE_UNAVAILABLE
            recording -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.contentDescription = getString(R.string.te_tile_label, action?.name ?: "")
        tile.updateTile()
    }
}

class ActionTile1 : ActionTileService(1)
class ActionTile2 : ActionTileService(2)
class ActionTile3 : ActionTileService(3)
