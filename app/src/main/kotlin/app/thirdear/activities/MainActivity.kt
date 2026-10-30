package app.thirdear.activities

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.PersistableBundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import app.thirdear.BuildConfig
import app.thirdear.R
import app.thirdear.actions.RecordAction
import app.thirdear.extensions.config
import app.thirdear.extensions.deleteExpiredTrashedRecordings
import app.thirdear.extensions.deleteRecordings
import app.thirdear.extensions.deleteTrashedRecordings
import app.thirdear.extensions.ensureStoragePermission
import app.thirdear.extensions.getAllRecordings
import app.thirdear.extensions.restoreRecordings
import app.thirdear.extensions.trashRecordings
import app.thirdear.models.Events
import app.thirdear.models.Recording
import app.thirdear.pipeline.NoteStore
import app.thirdear.services.RecorderService
import app.thirdear.ui.Destination
import app.thirdear.ui.ThirdEarApp
import app.thirdear.ui.ThirdEarTheme
import org.fossify.commons.extensions.appLaunched
import org.fossify.commons.extensions.checkAppSideloading
import org.fossify.commons.extensions.openNotificationSettings
import org.fossify.commons.extensions.openPathIntent
import org.fossify.commons.extensions.sharePathsIntent
import org.fossify.commons.helpers.PERMISSION_RECORD_AUDIO
import org.fossify.commons.helpers.ensureBackgroundThread
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

/**
 * Third Ear's one screen host: the Board (lines), Log (recordings, replies, recycle bin), Lines
 * (actions and White Noise groups) and Setup, drawn in Compose. Permissions, storage and the
 * service stay here; the screens call in.
 */
class MainActivity : SimpleActivity() {
    companion object {
        /** Activities may be started (share sheet) only while Third Ear is in front. */
        @Volatile
        var visible = false

        /** Which destination a notification or shortcut opens. */
        const val EXTRA_OPEN = "app.thirdear.open"
        const val OPEN_REPLIES = "replies"
        const val OPEN_SETUP = "setup"
        const val OPEN_LINES = "lines"

        private const val BLUETOOTH_REQUEST = 4711

        fun intent(context: Context, open: String? = null): Intent =
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply { open?.let { putExtra(EXTRA_OPEN, it) } }
    }

    private var bus: EventBus? = null

    /** Storage is usable: the recordings list can load. */
    val storageReady = mutableStateOf(false)
    val recordings = mutableStateOf<List<Recording>>(emptyList())
    val trashed = mutableStateOf<List<Recording>>(emptyList())
    val loading = mutableStateOf(true)
    val requested = mutableStateOf<Destination?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        appLaunched(BuildConfig.APPLICATION_ID)
        if (checkAppSideloading()) return

        setContent {
            ThirdEarTheme {
                ThirdEarApp(this, recordToResult = isThirdPartyIntent())
            }
        }
        requested.value = destinationOf(intent)

        if (savedInstanceState == null) deleteExpiredTrashedRecordings()

        handlePermission(PERMISSION_RECORD_AUDIO) {
            if (it) {
                ensureStorage()
            } else {
                Toast.makeText(this, org.fossify.commons.R.string.no_audio_permissions, Toast.LENGTH_LONG).show()
                finish()
            }
        }

        bus = EventBus.getDefault().also { it.register(this) }
        if (config.recordAfterLaunch && !RecorderService.isRunning) RecorderService.start(this, null)
        app.thirdear.whitenoise.WhiteNoiseService.start(this)
        // recordings the process died on (or that died mid-pipeline) are finished and saved now
        ensureBackgroundThread {
            if (!RecorderService.isRunning && NoteStore(this).unfinished().isNotEmpty()) {
                runOnUiThread { RecorderService.recover(this) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requested.value = destinationOf(intent)
    }

    override fun onResume() {
        super.onResume()
        visible = true
        // fossify's base activity paints the bars from its own theme; the face decides here
        val dark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
        if (storageReady.value) reloadRecordings()
    }

    override fun onPause() {
        super.onPause()
        visible = false
    }

    override fun onDestroy() {
        super.onDestroy()
        bus?.unregister(this)
    }

    private fun destinationOf(intent: Intent?): Destination? = when (intent?.getStringExtra(EXTRA_OPEN)) {
        OPEN_REPLIES -> Destination.LOG_REPLIES
        OPEN_SETUP -> Destination.SETUP
        OPEN_LINES -> Destination.LINES
        else -> if (intent?.action == Intent.ACTION_APPLICATION_PREFERENCES) Destination.SETUP else null
    }

    private fun ensureStorage() {
        ensureStoragePermission { granted ->
            if (granted) {
                storageReady.value = true
                reloadRecordings()
            } else {
                Toast.makeText(this, org.fossify.commons.R.string.no_storage_permissions, Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    // ------------------------------------------------------------------ recording

    /** A line tapped while idle: start recording on it, asking for what is missing first. */
    fun startLine(action: RecordAction) {
        ensureStoragePermission { storage ->
            if (!storage) {
                toast(getString(org.fossify.commons.R.string.no_storage_permissions))
                return@ensureStoragePermission
            }
            handleNotificationPermission { granted ->
                if (!granted) {
                    toast(getString(org.fossify.commons.R.string.allow_notifications_voice_recorder))
                    openNotificationSettings()
                    return@handleNotificationPermission
                }
                if (config.preferBluetooth &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
                ) {
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), BLUETOOTH_REQUEST)
                }
                RecorderService.start(this, action.id)
            }
        }
    }

    fun requestBluetoothPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), BLUETOOTH_REQUEST)
        }
    }

    // ------------------------------------------------------------------ recordings

    fun reloadRecordings() {
        if (!storageReady.value) return
        ensureBackgroundThread {
            val list = runCatching { getAllRecordings(false).sortedByDescending { it.timestamp } }.getOrDefault(emptyList())
            val bin = if (config.useRecycleBin) {
                runCatching { getAllRecordings(true).sortedByDescending { it.timestamp } }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
            runOnUiThread {
                recordings.value = list
                trashed.value = bin
                loading.value = false
            }
        }
    }

    fun trash(r: Recording) = trashRecordings(listOf(r)) { done(it, true) }

    fun restore(r: Recording) = restoreRecordings(listOf(r)) { done(it, true) }

    fun deleteForever(r: Recording) = deleteRecordings(listOf(r)) { done(it, false) }

    fun emptyRecycleBin() = ensureBackgroundThread {
        deleteTrashedRecordings()
        runOnUiThread { done(true, true) }
    }

    private fun done(success: Boolean, trashChanged: Boolean) {
        runOnUiThread {
            if (success && trashChanged) EventBus.getDefault().post(Events.RecordingTrashUpdated())
            reloadRecordings()
        }
    }

    fun share(r: Recording) = sharePathsIntent(listOf(r.path), BuildConfig.APPLICATION_ID)

    fun openWith(r: Recording) = openPathIntent(path = r.path, forceChooser = true, applicationId = BuildConfig.APPLICATION_ID, forceMimeType = "audio/*")

    fun rename(r: Recording, newTitle: String, onDone: (String?) -> Unit) {
        app.thirdear.dialogs.renameRecording(this, r, newTitle) { error ->
            runOnUiThread {
                onDone(error)
                reloadRecordings()
            }
        }
    }

    /** The transcript Third Ear made for a recording (matched by its saved file name). */
    fun transcriptOf(r: Recording): String? {
        val store = NoteStore(this)
        val note = store.bySavedName(r.title) ?: return null
        return store.transcript(note.id)
    }

    // ------------------------------------------------------------------ small things

    fun copy(text: String, sensitive: Boolean = false, confirmation: Int = R.string.te_wn_copied) {
        val clip = ClipData.newPlainText("Third Ear", text)
        if (sensitive) clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        // Android 13+ shows its own clipboard confirmation
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) toast(getString(confirmation))
    }

    fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    fun isThirdPartyIntent() = intent?.action == MediaStore.Audio.Media.RECORD_SOUND_ACTION

    @Suppress("unused")
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun recordingSaved(event: Events.RecordingSaved) {
        if (isThirdPartyIntent()) {
            Intent().apply {
                data = event.uri!!
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                setResult(Activity.RESULT_OK, this)
            }
            finish()
        } else {
            reloadRecordings()
        }
    }

    @Suppress("unused")
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun recordingCompleted(@Suppress("UNUSED_PARAMETER") event: Events.RecordingCompleted) = reloadRecordings()

    @Suppress("unused")
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun trashUpdated(@Suppress("UNUSED_PARAMETER") event: Events.RecordingTrashUpdated) = reloadRecordings()
}
