package app.thirdear.car

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.browse.MediaBrowser.MediaItem
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.service.media.MediaBrowserService
import android.util.Log
import android.view.KeyEvent
import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import app.thirdear.R
import app.thirdear.actions.ActionStore
import app.thirdear.audio.EngineStatus
import app.thirdear.extensions.describe
import app.thirdear.extensions.inputLabel
import app.thirdear.extensions.recordActions
import app.thirdear.services.RecorderService
import app.thirdear.whitenoise.IncomingStore
import app.thirdear.whitenoise.MessageNotifier
import app.thirdear.whitenoise.WhiteNoiseService

/**
 * Android Auto as a media app: the route that works sideloaded (Unknown sources on), where the
 * template car screens ([ThirdEarCarAppService]) need a Play install. Android Auto draws
 * everything; see [CarMedia] for what each tap and button does.
 *
 * The session is active only while a recording runs, so the phone's headset and the car's
 * steering wheel buttons go to Third Ear then (play/pause = pause, next = save) and back to the
 * music app afterwards. Only a tap on a line starts a recording: a stray "play" (the headphones,
 * Android Auto resuming the last media app on connect) never does.
 */
class RecorderMediaService : MediaBrowserService() {

    companion object {
        private const val TAG = "RecorderMediaService"
        private const val AUTO = "com.google.android.projection.gearhead"
        private const val ICON_PX = 128
        private const val ART_PX = 320
        private const val SENDER_CHARS = 8

        // Android Auto's content style extras (androidx.media.utils.MediaConstants)
        private const val STYLE_SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED"
        private const val STYLE_PLAYABLE = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
        private const val STYLE_BROWSABLE = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
        private const val STYLE_LIST = 1
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var session: MediaSession
    private var shown: EngineStatus? = null
    private var shownAt = 0L
    private var recordingShown = false
    private var tappedAt = 0L
    private var error: String? = null

    /** The line the last recording went to, for the "Saving…" frame after a stop. */
    private var savedTo: String? = null
    private var discarded = false

    override fun onCreate() {
        super.onCreate()
        session = MediaSession(this, TAG).apply {
            setCallback(Callback())
            setSessionActivity(
                android.app.PendingIntent.getActivity(
                    this@RecorderMediaService, 0,
                    packageManager.getLaunchIntentForPackage(packageName),
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        }
        sessionToken = session.sessionToken
        publish(RecorderService.live.value)
        scope.launch {
            RecorderService.live.collect { publish(it) }
        }
        scope.launch {
            RecorderService.refusedAt.collect { at ->
                if (tappedAt in 1..at) {
                    tappedAt = 0
                    error = getString(R.string.te_car_open_phone)
                    publish(RecorderService.live.value)
                }
            }
        }
        scope.launch { ActionStore.changes.collect { notifyChildrenChanged(CarMedia.TAB_RECORD) } }
        scope.launch { IncomingStore.messages.collect { notifyChildrenChanged(CarMedia.TAB_MESSAGES) } }
    }

    override fun onDestroy() {
        scope.cancel()
        session.release()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ browse

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot? {
        // starting the microphone is not something any app may ask for
        val allowed = clientUid == Process.myUid() || clientUid == Process.SYSTEM_UID || clientPackageName == AUTO
        if (!allowed) {
            Log.i(TAG, "refused browser $clientPackageName")
            return null
        }
        val extras = Bundle().apply {
            putBoolean(STYLE_SUPPORTED, true)
            putInt(STYLE_BROWSABLE, STYLE_LIST)
            putInt(STYLE_PLAYABLE, STYLE_LIST)
        }
        return BrowserRoot(CarMedia.ROOT, extras)
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaItem>>) {
        result.sendResult(
            when (parentId) {
                CarMedia.ROOT -> mutableListOf(
                    tab(CarMedia.TAB_RECORD, getString(R.string.te_car_record), R.drawable.ic_microphone_widget_icon),
                    tab(CarMedia.TAB_MESSAGES, getString(R.string.te_car_messages) + unreadSuffix(this), R.drawable.ic_send_vector_te),
                )

                CarMedia.TAB_RECORD -> recordItems()
                CarMedia.TAB_MESSAGES -> messageItems()
                else -> mutableListOf()
            },
        )
    }

    private fun tab(id: String, title: String, @DrawableRes icon: Int): MediaItem = MediaItem(
        MediaDescription.Builder().setMediaId(id).setTitle(title).setIconBitmap(icon(this, icon, null, ICON_PX)).build(),
        MediaItem.FLAG_BROWSABLE,
    )

    private fun recordItems(): MutableList<MediaItem> {
        val actions = recordActions()
        val items = CarMedia.recordItems(
            actions,
            RecorderService.live.value?.action?.id,
            { describe(it) },
        ) { getString(R.string.te_car_stop, it) }
        return items.map { item ->
            val bitmap = icon(this, R.drawable.ic_car_line, item.actionIndex?.let { i -> lineColor(this, i) }, ICON_PX)
            MediaItem(
                MediaDescription.Builder().setMediaId(item.mediaId).setTitle(item.title).setSubtitle(item.subtitle).setIconBitmap(bitmap).build(),
                MediaItem.FLAG_PLAYABLE,
            )
        }.toMutableList()
    }

    private fun messageItems(): MutableList<MediaItem> =
        IncomingStore(this).load().filter { it.ignoredBy == null }.take(CarMedia.MAX_MESSAGES).map { m ->
            MediaItem(
                MediaDescription.Builder()
                    .setMediaId(CarMedia.messageId(m.id))
                    // a sender named like the group (an agent's own group) is said once
                    .setTitle(listOfNotNull(m.groupName, (m.senderName ?: m.sender.take(SENDER_CHARS)).takeIf { it != m.groupName }).joinToString(" · "))
                    .setSubtitle(MessageNotifier.messageLine(this, m))
                    .build(),
                MediaItem.FLAG_PLAYABLE,
            )
        }.toMutableList()

    // ------------------------------------------------------------------ session

    private fun publish(l: RecorderService.Companion.Live?) {
        val recording = l != null
        if (recording != recordingShown) {
            recordingShown = recording
            // the record tab switches between "record with" and "stop → line"
            notifyChildrenChanged(CarMedia.TAB_RECORD)
            if (recording) error = null
        }
        if (l == null) {
            publishIdle()
            return
        }
        val now = System.currentTimeMillis()
        if (!CarModel.needsRefresh(shown, l.status, shownAt, now)) return
        val firstFrame = shown == null
        shown = l.status
        shownAt = now
        val status = l.status
        val actions = recordActions()
        val index = actions.indexOfFirst { it.id == l.action.id }
        if (savedTo == null || firstFrame) savedTo = l.action.name
        val title = getString(R.string.te_car_recording, l.action.name)
        val subtitle = inputLabel(status) + if (status.paused) " · " + getString(R.string.paused) else ""
        if (firstFrame) session.isActive = true
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, CarMedia.recordId(l.action.id))
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, subtitle)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, subtitle)
                .putBitmap(
                    MediaMetadata.METADATA_KEY_ART,
                    if (index >= 0) {
                        icon(this, R.drawable.ic_car_line, lineColor(this, index), ART_PX)
                    } else {
                        icon(this, R.drawable.ic_microphone_widget_icon, null, ART_PX)
                    },
                )
                .build(),
        )
        val state = PlaybackState.Builder()
            .setState(
                if (status.paused) PlaybackState.STATE_PAUSED else PlaybackState.STATE_PLAYING,
                status.durationMs,
                if (status.paused) 0f else 1f,
                SystemClock.elapsedRealtime(),
            )
            .setActions(
                PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_STOP or PlaybackState.ACTION_PLAY_FROM_MEDIA_ID,
            )
            // three buttons, three different icons: pause (Android Auto's), stop and save, discard.
            // "Stop → another line" is the record tab's list (back arrow): icons cannot say which line.
            .addCustomAction(
                PlaybackState.CustomAction.Builder(
                    CarMedia.CUSTOM_SAVE, getString(R.string.te_car_stop, l.action.name), R.drawable.ic_car_stop,
                ).build(),
            )
            .addCustomAction(
                PlaybackState.CustomAction.Builder(CarMedia.CUSTOM_DISCARD, getString(R.string.te_car_discard), R.drawable.ic_cancel_recording_vector).build(),
            )
        session.setPlaybackState(state.build())
    }

    private fun publishIdle() {
        shown = null
        // STATE_NONE makes Android Auto open the line list next time. Without metadata a Now Playing
        // screen that is still open spins on "Getting your selection…", so a stop leaves "Saving… · line".
        session.setPlaybackState(idleState())
        val to = savedTo
        val title = when {
            discarded -> getString(R.string.te_car_discarded)
            to != null -> getString(R.string.te_step_saving)
            else -> null
        }
        session.setMetadata(
            title?.let {
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, it)
                    .putString(MediaMetadata.METADATA_KEY_TITLE, it)
                    .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, if (discarded) null else to)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, if (discarded) null else to)
                    .build()
            },
        )
        session.isActive = false
    }

    private fun idleState(): PlaybackState {
        val b = PlaybackState.Builder()
            // no ACTION_PLAY: Android Auto's "start music automatically" must not start a recording
            .setActions(PlaybackState.ACTION_PLAY_FROM_MEDIA_ID)
        val e = error
        if (e != null) {
            b.setState(PlaybackState.STATE_ERROR, 0, 0f).setErrorMessage(e)
        } else {
            b.setState(PlaybackState.STATE_NONE, 0, 0f)
        }
        return b.build()
    }

    private fun run(c: CarMedia.Command) {
        Log.i(TAG, "car: $c")
        when (c) {
            is CarMedia.Command.Start -> {
                if (RecorderService.isRunning) return
                error = null
                savedTo = null
                discarded = false
                tappedAt = System.currentTimeMillis()
                if (!RecorderService.start(this, c.actionId)) {
                    error = getString(R.string.te_car_open_phone)
                    publish(null)
                }
            }

            is CarMedia.Command.Stop -> {
                recordActions().firstOrNull { it.id == c.actionId }?.let { savedTo = it.name }
                RecorderService.stop(this, c.actionId)
            }
            CarMedia.Command.Discard -> {
                discarded = true
                RecorderService.discard(this)
            }
            CarMedia.Command.TogglePause -> RecorderService.togglePause(this)
            is CarMedia.Command.PlayMessage -> WhiteNoiseService.playMessage(this, c.messageId)
            CarMedia.Command.None -> Unit
        }
    }

    private val recording get() = RecorderService.live.value != null
    private val paused get() = RecorderService.live.value?.status?.paused == true

    private inner class Callback : MediaSession.Callback() {
        override fun onPlay() = run(CarMedia.onPlay(recording, paused))
        override fun onPause() = run(CarMedia.onPause(recording, paused))
        override fun onStop() = run(CarMedia.onStop(recording))

        /** The steering wheel's "next" saves; not offered as an on-screen skip button (it would be a fourth, unclear icon). */
        override fun onMediaButtonEvent(intent: Intent): Boolean {
            val key = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
            if (key?.keyCode == KeyEvent.KEYCODE_MEDIA_NEXT && recording) {
                if (key.action == KeyEvent.ACTION_UP) run(CarMedia.onStop(recording))
                return true
            }
            return super.onMediaButtonEvent(intent)
        }
        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) = run(CarMedia.onTap(mediaId, recording))
        override fun onCustomAction(action: String, extras: Bundle?) = run(CarMedia.onCustom(action, recording))
    }
}

/** A line's cord colour, as on the phone's keys. */
private fun lineColor(context: Context, index: Int): Int {
    val action = context.recordActions().getOrNull(index) ?: return android.graphics.Color.WHITE
    return app.thirdear.ui.Cords.color(app.thirdear.ui.cordOf(action, index)).toArgb()
}

/** Android Auto draws browse icons and art from bitmaps (vector resources of another app may not load). */
private fun icon(context: Context, @DrawableRes res: Int, tint: Int?, px: Int): Bitmap {
    val d = ContextCompat.getDrawable(context, res)!!.mutate()
    if (tint != null) d.setTint(tint)
    val b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    d.setBounds(0, 0, px, px)
    d.draw(Canvas(b))
    return b
}

private fun unreadSuffix(context: Context): String {
    val n = IncomingStore(context).load().count { !it.read && it.ignoredBy == null }
    return if (n > 0) " ($n)" else ""
}
