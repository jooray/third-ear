package app.thirdear.pipeline

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import android.service.notification.NotificationListenerService

/**
 * Pauses what plays when a recording starts and resumes it after (so a podcast is not recorded
 * over, and music does not play in call quality while a Bluetooth microphone is open). Controlling
 * another app's playback needs notification access: the only way a normal app may do it.
 */
class MediaPauser : NotificationListenerService() {

    companion object {
        @Volatile
        var instance: MediaPauser? = null
            private set

        fun hasAccess(context: Context): Boolean {
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
            val me = ComponentName(context, MediaPauser::class.java)
            return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
        }

        /** Pauses every playing session; returns their packages for [resume]. */
        fun pausePlaying(): List<String> = instance?.sessions().orEmpty()
            .filter { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            .map { c ->
                c.transportControls.pause()
                c.packageName
            }

        /** Resumes those of [packages] that are still paused, if nothing else started meanwhile. */
        fun resume(packages: List<String>) {
            val list = instance?.sessions().orEmpty()
            if (list.any { it.playbackState?.state == PlaybackState.STATE_PLAYING }) return
            list.filter { it.packageName in packages && it.playbackState?.state == PlaybackState.STATE_PAUSED }
                .forEach { it.transportControls.play() }
        }
    }

    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        instance = null
    }

    private fun sessions(): List<MediaController> {
        val msm = getSystemService(MediaSessionManager::class.java) ?: return emptyList()
        return runCatching { msm.getActiveSessions(ComponentName(this, MediaPauser::class.java)) }.getOrDefault(emptyList())
    }
}
