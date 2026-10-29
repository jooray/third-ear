package app.thirdear.debug

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.thirdear.actions.ActionStore
import app.thirdear.actions.RecordAction
import app.thirdear.audio.Container
import app.thirdear.extensions.config
import app.thirdear.pipeline.Note
import app.thirdear.pipeline.NoteState
import app.thirdear.pipeline.NoteStore
import app.thirdear.services.RecorderService
import app.thirdear.whitenoise.IncomingStore
import app.thirdear.whitenoise.MarmotHost
import app.thirdear.whitenoise.WhiteNoiseService
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Debug builds only. Device checks without touching the screen (works over the lock screen, so a
 * recording can start while the phone is locked):
 *
 *   A="adb shell am start -n app.thirdear.debug/app.thirdear.debug.DebugActivity"
 *   $A --es cmd start --es action save          # start recording with an action id
 *   $A --es cmd stop  --es action transcript     # stop and run that action
 *   $A --es cmd set --es key extension --es int 2     # / --es bool true / --es str …
 *   $A --es cmd add_action --es json '{"id":"t","name":"T","destination":"SAVE","transcribe":true}'
 *   $A --es cmd import --es path /sdcard/Android/data/app.thirdear.debug/files/x.wav --es action t
 *   $A --es cmd wn_enable ;  $A --es cmd wn_group --es name "Third Ear test" ;  $A --es cmd wn_status
 *   $A --es cmd cleanup                          # delete notes and incoming messages
 * Results go to logcat, tag ThirdEarDebug.
 */
class DebugActivity : Activity() {
    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val i = intent
        val cmd = i.getStringExtra("cmd")
        log("cmd $cmd")
        try {
            when (cmd) {
                "start" -> RecorderService.start(this, i.getStringExtra("action"))
                "stop" -> RecorderService.stop(this, i.getStringExtra("action"))
                "switch" -> RecorderService.switchSource(this, app.thirdear.audio.Source.valueOf(i.getStringExtra("source")!!))
                "set" -> {
                    val key = i.getStringExtra("key")!!
                    val e = getSharedPreferences("Prefs", MODE_PRIVATE).edit()
                    i.getStringExtra("int")?.let { e.putInt(key, it.toInt()) }
                    i.getStringExtra("bool")?.let { e.putBoolean(key, it.toBoolean()) }
                    i.getStringExtra("str")?.let { e.putString(key, it) }
                    e.apply()
                    log("set $key")
                }
                "add_action" -> {
                    val a = RecordAction.fromJson(JSONObject(i.getStringExtra("json")!!))
                    ActionStore(this).upsert(a)
                    log("action ${a.id} ${a.name}")
                }
                "delete_action" -> ActionStore(this).delete(i.getStringExtra("action")!!)
                "import" -> import(File(i.getStringExtra("path")!!), i.getStringExtra("action")!!)
                "wn_enable" -> {
                    config.whiteNoiseEnabled = true
                    File(noBackupFilesDir, "marmot").mkdirs()
                    WhiteNoiseService.start(this)
                }
                "wn_group" -> scope.launch {
                    runCatching { MarmotHost.start(this@DebugActivity); MarmotHost.createGroup(i.getStringExtra("name") ?: "Third Ear test") }
                        .onSuccess { log("group $it") }.onFailure { log("group failed: ${it.message}") }
                }
                "wn_status" -> scope.launch {
                    MarmotHost.start(this@DebugActivity)
                    log("wn ${MarmotHost.phase.value} ${MarmotHost.npub.value} groups=${MarmotHost.groups.value}")
                }
                "wn_disable" -> {
                    config.whiteNoiseEnabled = false
                    WhiteNoiseService.stop(this)
                }
                "cleanup" -> {
                    File(filesDir, "notes").deleteRecursively()
                    File(filesDir, "incoming").deleteRecursively()
                    NoteStore(this).reload()
                    log("cleaned")
                }
            }
        } catch (t: Throwable) {
            log("failed: $t")
        }
        // stay resumed a moment: Android lets a visible app start the microphone service
        Handler(Looper.getMainLooper()).postDelayed({ finish() }, 1_500)
    }

    /** Runs an existing audio file through the pipeline as if it had just been recorded. */
    private fun import(source: File, actionId: String) {
        val action = ActionStore(this).get(actionId) ?: error("no action $actionId")
        val ext = source.extension.lowercase()
        val container = when (ext) { "ogg" -> Container.OGG; "mp3" -> Container.MP3; else -> Container.MPEG_4 }
        val store = NoteStore(this)
        val note = Note(
            id = "import-" + System.currentTimeMillis(), startedAt = System.currentTimeMillis(), action = action,
            container = container, baseName = "import_" + System.currentTimeMillis(), state = NoteState.PROCESSING,
        )
        store.put(note)
        // the pipeline transcribes whatever is in the note's audio file (any format OSTT decodes)
        source.copyTo(store.audio(note), overwrite = true)
        RecorderService.retry(this, note.id)
        log("imported ${note.id}")
    }

    private fun log(s: String) = Log.i("ThirdEarDebug", s)
}
