package app.thirdear.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.thirdear.tiles.ActionTileService
import app.thirdear.whitenoise.WhiteNoiseService

/**
 * After a reboot, and after an app update (which kills the process; only BOOT_COMPLETED and
 * MY_PACKAGE_REPLACED bring it back), White Noise reconnects so messages keep arriving, and the
 * tiles show the right actions. Recording itself never starts from here.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        WhiteNoiseService.start(context)
        ActionTileService.refreshAll(context)
    }
}
