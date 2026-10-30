package app.thirdear.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.thirdear.BuildConfig
import app.thirdear.R
import app.thirdear.actions.ActionStore
import app.thirdear.activities.MainActivity
import app.thirdear.extensions.config
import app.thirdear.extensions.getAllRecordings
import app.thirdear.extensions.hasRecordings
import app.thirdear.extensions.launchFolderPicker
import app.thirdear.extensions.moveRecordings
import app.thirdear.extensions.openOsttPage
import app.thirdear.extensions.recordActions
import app.thirdear.helpers.BITRATES
import app.thirdear.helpers.DEFAULT_BITRATE
import app.thirdear.helpers.DEFAULT_SAMPLING_RATE
import app.thirdear.helpers.EXTENSION_M4A
import app.thirdear.helpers.EXTENSION_MP3
import app.thirdear.helpers.EXTENSION_OGG
import app.thirdear.helpers.SAMPLING_RATES
import app.thirdear.helpers.SAMPLING_RATE_BITRATE_LIMITS
import app.thirdear.pipeline.Cues
import app.thirdear.pipeline.MediaPauser
import app.thirdear.pipeline.Transcriber
import app.thirdear.tiles.ActionTileService
import app.thirdear.whitenoise.MarmotHost
import app.thirdear.whitenoise.WhiteNoiseService
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.launch
import org.fossify.commons.extensions.formatSize
import org.fossify.commons.extensions.humanizePath
import org.fossify.commons.extensions.isAValidFilename
import org.fossify.commons.helpers.ensureBackgroundThread
import kotlin.math.abs

private sealed interface Pick {
    data object Format : Pick
    data object Bitrate : Pick
    data object SampleRate : Pick
    data object MicMode : Pick
    data object MicWarning : Pick
    data object Transcriber : Pick
    data class Tile(val slot: Int) : Pick
    data object Widget : Pick
    data object Pattern : Pick
    data class MoveTo(val from: String, val to: String) : Pick
    data object CreateGroup : Pick
    data object NsecWarning : Pick
    data object ImportNsec : Pick
    data object EmptyBin : Pick
    data object ListeningCue : Pick
    data object ProgressCue : Pick
}

@Composable
fun SetupScreen(host: MainActivity) {
    val p = Te.colors
    val context = LocalContext.current
    val config = context.config
    val scope = rememberCoroutineScope()
    // the settings live in SharedPreferences; a tick redraws after each change
    var tick by remember { mutableIntStateOf(0) }
    val changed: () -> Unit = { tick++ }
    var pick by remember { mutableStateOf<Pick?>(null) }
    var binSize by remember { mutableIntStateOf(0) }
    val trashed by host.trashed
    LaunchedEffect(trashed) { binSize = trashed.sumOf { it.size } }

    val actionsVersion by ActionStore.changes.collectAsStateWithLifecycle()
    val store = remember { ActionStore(context) }
    val transcriber = remember { Transcriber(context) }
    val currentTranscriber = remember(tick) { transcriber.resolve(config.transcriber) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.te_nav_setup), style = TeType.screenTitle, color = p.ink)
        }

        Section(R.string.te_setup_recording)
        key(tick) {
            SettingRow(stringResource(R.string.save_recordings_in), value = context.humanizePath(config.saveRecordingsFolder), onClick = {
                val current = config.saveRecordingsFolder
                host.launchFolderPicker { folder ->
                    if (folder.isNullOrEmpty()) return@launchFolderPicker
                    ensureBackgroundThread {
                        val has = host.hasRecordings()
                        host.runOnUiThread {
                            if (folder != current && has) {
                                pick = Pick.MoveTo(current, folder)
                            } else {
                                config.saveRecordingsFolder = folder
                                changed()
                                host.reloadRecordings()
                            }
                        }
                    }
                }
            })
            SettingRow(stringResource(R.string.filename_pattern), value = config.filenamePattern, onClick = { pick = Pick.Pattern })
            SettingRow(stringResource(R.string.te_setup_format), value = config.getExtensionText(), onClick = { pick = Pick.Format })
            SettingRow(stringResource(R.string.bitrate), value = bitrateText(context, config.bitrate), onClick = { pick = Pick.Bitrate })
            SettingRow(stringResource(R.string.sample_rate), value = rateText(context, config.samplingRate), onClick = { pick = Pick.SampleRate })
            SettingRow(stringResource(R.string.microphone_mode), value = config.getMicrophoneModeText(config.microphoneMode), onClick = {
                pick = if (config.wasMicModeWarningShown) Pick.MicMode else Pick.MicWarning
            })
            SwitchRow(stringResource(R.string.te_setting_bluetooth), config.preferBluetooth, {
                config.preferBluetooth = it
                if (it) host.requestBluetoothPermission()
                changed()
            }, description = stringResource(R.string.te_setting_bluetooth_desc))
            SwitchRow(stringResource(R.string.record_after_launch), config.recordAfterLaunch, { config.recordAfterLaunch = it; changed() })
            SwitchRow(stringResource(R.string.keep_screen_on), config.keepScreenOn, { config.keepScreenOn = it; changed() })

            Section(R.string.te_setup_transcription)
            SettingRow(
                stringResource(R.string.te_setting_transcriber),
                value = currentTranscriber?.let { providerLabel(context, transcriber, it) } ?: stringResource(R.string.te_ostt_missing),
                onClick = {
                    val c = currentTranscriber
                    val usable = transcriber.providers().isNotEmpty() && c != null && transcriber.contractFor(c) != null
                    if (usable) pick = Pick.Transcriber else context.openOsttPage()
                },
            )
            SwitchRow(stringResource(R.string.te_setting_transcript_files), config.saveTranscriptFiles, { config.saveTranscriptFiles = it; changed() })

            Section(R.string.te_setup_feedback)
            SettingRow(
                stringResource(R.string.te_setting_listening_cue),
                value = listeningCues(context).toMap()[config.listeningCue],
                onClick = { pick = Pick.ListeningCue },
            )
            SettingRow(
                stringResource(R.string.te_setting_progress_cue),
                value = progressCues(context).toMap()[config.progressCue],
                onClick = { pick = Pick.ProgressCue },
            )
            SwitchRow(stringResource(R.string.te_setting_pause_media), config.pauseMedia && MediaPauser.hasAccess(context), {
                config.pauseMedia = it
                if (it && !MediaPauser.hasAccess(context)) runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                changed()
            }, description = stringResource(R.string.te_setting_pause_media_desc))

            Section(R.string.te_setup_shortcuts)
            androidx.compose.runtime.key(actionsVersion) {
                for (slot in 1..ActionStore.TILE_SLOTS) {
                    SettingRow(
                        stringResource(R.string.te_setting_tile, slot),
                        value = store.tileAction(slot)?.name ?: stringResource(R.string.te_tile_no_action),
                        onClick = { pick = Pick.Tile(slot) },
                    )
                }
                SettingRow(stringResource(R.string.te_setting_widget_action), value = store.defaultAction()?.name, onClick = { pick = Pick.Widget })
            }

            WhiteNoiseSection(host, onPick = { pick = it }, changed = changed)

            Section(R.string.te_setup_bin)
            SwitchRow(stringResource(org.fossify.commons.R.string.move_items_into_recycle_bin), config.useRecycleBin, {
                config.useRecycleBin = it
                changed()
                host.reloadRecordings()
            })
            if (config.useRecycleBin) {
                SettingRow(stringResource(R.string.te_empty_bin), value = binSize.formatSize(), onClick = {
                    if (binSize == 0) host.toast(context.getString(org.fossify.commons.R.string.recycle_bin_empty)) else pick = Pick.EmptyBin
                })
            }

            Section(R.string.te_setting_android_auto)
            Text(
                stringResource(R.string.te_setting_android_auto_desc),
                style = TeType.small,
                color = p.inkMuted,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            Section(R.string.te_setup_about)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                SettingRow(stringResource(R.string.te_setup_language), value = java.util.Locale.getDefault().displayLanguage, onClick = {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                    }
                })
            }
            SettingRow(stringResource(R.string.te_setup_version), value = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            SettingRow(stringResource(R.string.te_setup_source), value = stringResource(R.string.te_setup_source_desc), onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(context.getString(R.string.te_source_url)))) }
            })
        }
    }

    when (val k = pick) {
        null -> {}
        Pick.ListeningCue -> ChoiceDialog(
            stringResource(R.string.te_setting_listening_cue),
            listeningCues(context),
            config.listeningCue,
            { config.listeningCue = it; changed() },
            { pick = null },
        )
        Pick.ProgressCue -> ChoiceDialog(
            stringResource(R.string.te_setting_progress_cue),
            progressCues(context),
            config.progressCue,
            { config.progressCue = it; changed() },
            { pick = null },
        )
        Pick.Format -> ChoiceDialog(
            stringResource(R.string.te_setup_format),
            listOf(
                EXTENSION_M4A to stringResource(R.string.m4a),
                EXTENSION_OGG to stringResource(R.string.ogg_opus),
                EXTENSION_MP3 to stringResource(R.string.mp3_experimental),
            ),
            config.extension,
            { config.extension = it; adjustBitrate(context); adjustSamplingRate(context); changed() },
            { pick = null },
        )
        Pick.Bitrate -> ChoiceDialog(
            stringResource(R.string.bitrate),
            BITRATES.getValue(config.extension).map { it to bitrateText(context, it) },
            config.bitrate,
            { config.bitrate = it; adjustSamplingRate(context); changed() },
            { pick = null },
        )
        Pick.SampleRate -> ChoiceDialog(
            stringResource(R.string.sample_rate),
            samplingRates(context).map { it to rateText(context, it) },
            config.samplingRate,
            { config.samplingRate = it; changed() },
            { pick = null },
        )
        Pick.MicWarning -> ConfirmDialog(
            stringResource(R.string.microphone_mode),
            stringResource(R.string.change_microphone_mode_confirmation),
            stringResource(org.fossify.commons.R.string.ok),
            onConfirm = { config.wasMicModeWarningShown = true; pick = Pick.MicMode },
            onDismiss = { if (pick == Pick.MicWarning) pick = null },
        )
        Pick.MicMode -> ChoiceDialog(
            stringResource(R.string.microphone_mode),
            micModes().map { it to config.getMicrophoneModeText(it) },
            config.microphoneMode,
            { config.microphoneMode = it; changed() },
            { pick = null },
        )
        Pick.Transcriber -> {
            val providers = transcriber.providers()
            ChoiceDialog(
                stringResource(R.string.te_setting_transcriber),
                providers.map { it to providerLabel(context, transcriber, it) },
                currentTranscriber,
                { config.transcriber = it.flattenToString(); changed() },
                { pick = null },
            )
        }
        is Pick.Tile -> {
            val actions = context.recordActions()
            ChoiceDialog(
                stringResource(R.string.te_setting_tile, k.slot),
                actions.map { it.id to it.name },
                store.tileAction(k.slot)?.id,
                { store.setTile(k.slot, it); ActionTileService.refreshAll(context); changed() },
                { pick = null },
            )
        }
        Pick.Widget -> {
            val actions = context.recordActions()
            ChoiceDialog(
                stringResource(R.string.te_setting_widget_action),
                actions.map { it.id to it.name },
                store.defaultAction()?.id,
                { store.widgetActionId = it; changed() },
                { pick = null },
            )
        }
        Pick.Pattern -> TextDialog(
            title = stringResource(R.string.filename_pattern),
            initial = config.filenamePattern,
            confirm = stringResource(R.string.te_save),
            message = stringResource(R.string.te_setup_pattern_hint),
            onConfirm = { config.filenamePattern = it.trim(); changed() },
            onDismiss = { pick = null },
            validate = { v ->
                val t = v.trim()
                when {
                    t.isEmpty() -> context.getString(org.fossify.commons.R.string.filename_cannot_be_empty)
                    !t.isAValidFilename() -> context.getString(org.fossify.commons.R.string.invalid_name)
                    listOf("%Y", "%M", "%D", "%h", "%m", "%s").any { it !in t } -> context.getString(R.string.filename_pattern_must_contain_all)
                    else -> null
                }
            },
        )
        is Pick.MoveTo -> ConfirmDialog(
            stringResource(R.string.move_recordings),
            stringResource(R.string.move_recordings_to_new_folder_desc),
            stringResource(R.string.te_move),
            onConfirm = {
                ensureBackgroundThread {
                    host.moveRecordings(host.getAllRecordings(), k.from, k.to) {
                        host.runOnUiThread {
                            config.saveRecordingsFolder = k.to
                            changed()
                            host.reloadRecordings()
                        }
                    }
                }
            },
            neutral = stringResource(R.string.te_keep_them),
            onNeutral = { config.saveRecordingsFolder = k.to; changed(); host.reloadRecordings() },
            onDismiss = { pick = null },
        )
        Pick.CreateGroup -> TextDialog(
            title = stringResource(R.string.te_wn_create_group),
            initial = stringResource(R.string.te_wn_test_group_name),
            confirm = stringResource(R.string.te_create),
            message = stringResource(R.string.te_wn_create_group_desc),
            onConfirm = { name ->
                val n = name.trim().ifEmpty { context.getString(R.string.te_wn_test_group_name) }
                scope.launch { runCatching { MarmotHost.createGroup(n) }.onFailure { host.toast(it.message ?: it.javaClass.simpleName) } }
            },
            onDismiss = { pick = null },
        )
        Pick.NsecWarning -> NsecBackup(host, onDone = { pick = null; changed() })
        Pick.ImportNsec -> TextDialog(
            title = stringResource(R.string.te_wn_import_nsec),
            initial = "",
            confirm = stringResource(R.string.te_wn_import),
            message = stringResource(R.string.te_wn_import_warning),
            hint = "nsec1…",
            secret = true,
            onConfirm = { key -> scope.launch { runCatching { MarmotHost.importNsec(context, key.trim()) }.onFailure { host.toast(it.message ?: "?") } } },
            onDismiss = { pick = null },
            validate = { if (!it.trim().startsWith("nsec1")) context.getString(R.string.te_wn_not_nsec) else null },
        )
        Pick.EmptyBin -> ConfirmDialog(
            stringResource(R.string.te_empty_bin),
            stringResource(org.fossify.commons.R.string.empty_recycle_bin_confirmation),
            stringResource(R.string.te_empty_bin),
            destructive = true,
            onConfirm = { host.emptyRecycleBin() },
            onDismiss = { pick = null },
        )
    }
}

@Composable
private fun key(tick: Int, content: @Composable () -> Unit) = androidx.compose.runtime.key(tick) { content() }

@Composable
private fun Section(title: Int) {
    Column {
        Label(stringResource(title), Modifier.padding(start = 16.dp, end = 16.dp, top = 28.dp, bottom = 8.dp))
        Rule()
    }
}

/** Third Ear's White Noise identity: on/off, npub with QR, a test group, key backup. */
@Composable
private fun WhiteNoiseSection(host: MainActivity, onPick: (Pick) -> Unit, changed: () -> Unit) {
    val p = Te.colors
    val context = LocalContext.current
    val config = context.config
    val scope = rememberCoroutineScope()
    val phase by MarmotHost.phase.collectAsStateWithLifecycle()
    val npub by MarmotHost.npub.collectAsStateWithLifecycle()
    val error by MarmotHost.error.collectAsStateWithLifecycle()
    val on = config.whiteNoiseEnabled
    val ready = phase == MarmotHost.Phase.READY

    LaunchedEffect(on) {
        if (on && MarmotHost.phase.value == MarmotHost.Phase.OFF) {
            WhiteNoiseService.start(context)
            runCatching { MarmotHost.start(context) }
        }
    }

    Section(R.string.te_setting_white_noise)
    SwitchRow(stringResource(R.string.te_wn_use), on, { enable ->
        if (!MarmotHost.supported()) {
            host.toast(context.getString(R.string.te_wn_unsupported))
            return@SwitchRow
        }
        config.whiteNoiseEnabled = enable
        if (enable) {
            config.whiteNoiseRestored = false
            // the user's choice: from here on a missing MDK directory is not a restored backup
            java.io.File(context.noBackupFilesDir, "marmot").mkdirs()
            WhiteNoiseService.start(context)
            scope.launch { runCatching { MarmotHost.start(context) } }
        } else {
            WhiteNoiseService.stop(context)
        }
        changed()
    })
    val status = when {
        !MarmotHost.supported() -> stringResource(R.string.te_wn_unsupported)
        !on && config.whiteNoiseRestored -> stringResource(R.string.te_wn_restored)
        !on -> stringResource(R.string.te_wn_intro)
        phase == MarmotHost.Phase.STARTING -> stringResource(R.string.te_wn_starting)
        phase == MarmotHost.Phase.FAILED -> stringResource(R.string.te_wn_failed, error ?: "?")
        else -> stringResource(R.string.te_wn_ready)
    }
    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.Top) {
        if (on) {
            Lamp(
                when (phase) {
                    MarmotHost.Phase.READY -> LampState.REPLY
                    MarmotHost.Phase.FAILED -> LampState.FAULT
                    else -> LampState.BUSY
                },
                size = 12.dp,
            )
        }
        Text(
            status,
            style = TeType.small,
            color = if (phase == MarmotHost.Phase.FAILED) p.faultInk else p.inkMuted,
            modifier = Modifier.padding(top = 2.dp, start = 4.dp),
        )
    }
    val n = npub
    if (on && ready && n != null) {
        Plate(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Label(stringResource(R.string.te_wn_identity), Modifier.fillMaxWidth())
                SelectionContainer { Text(n, style = TeType.mono, color = p.ink, modifier = Modifier.padding(vertical = 8.dp)) }
                val qr = remember(n) { qr(n) }
                Box(Modifier.size(200.dp).clip(CellShape).background(androidx.compose.ui.graphics.Color.White).padding(10.dp)) {
                    Image(qr.asImageBitmap(), contentDescription = stringResource(R.string.te_wn_qr), modifier = Modifier.fillMaxSize())
                }
                Spacer(Modifier.height(8.dp))
                Row {
                    PlainButton(stringResource(R.string.te_wn_copy_npub), { host.copy(n) }, icon = Icons.Outlined.ContentCopy)
                    PlainButton(stringResource(R.string.te_share), {
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, n), null))
                    }, icon = Icons.Outlined.Share)
                }
            }
        }
        SettingRow(
            stringResource(R.string.te_wn_create_group),
            value = stringResource(R.string.te_wn_create_group_short),
            onClick = { onPick(Pick.CreateGroup) },
        )
        Label(stringResource(R.string.te_wn_backup), Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp))
        if (context.config.nsecShownFor != n) {
            Notice(stringResource(R.string.te_wn_backup_nudge), Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        SettingRow(stringResource(R.string.te_wn_show_nsec), onClick = { onPick(Pick.NsecWarning) })
        SettingRow(stringResource(R.string.te_wn_import_nsec), onClick = { onPick(Pick.ImportNsec) })
    }
}

private fun qr(text: String): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 512, 512)
    val bmp = Bitmap.createBitmap(m.width, m.height, Bitmap.Config.RGB_565)
    for (x in 0 until m.width) for (y in 0 until m.height) bmp.setPixel(x, y, if (m[x, y]) AColor.BLACK else AColor.WHITE)
    return bmp
}

private fun bitrateText(context: android.content.Context, value: Int) = context.getString(R.string.bitrate_value).format(value / 1000)

private fun rateText(context: android.content.Context, value: Int) = context.getString(R.string.sampling_rate_value).format(value)

private fun samplingRates(context: android.content.Context): List<Int> {
    val c = context.config
    val limits = SAMPLING_RATE_BITRATE_LIMITS.getValue(c.extension)
    return SAMPLING_RATES.getValue(c.extension).filter { c.bitrate in limits.getValue(it)[0]..limits.getValue(it)[1] }
}

private fun adjustBitrate(context: android.content.Context) {
    val c = context.config
    val available = BITRATES.getValue(c.extension)
    if (c.bitrate !in available) c.bitrate = available.minByOrNull { abs(it - c.bitrate) } ?: DEFAULT_BITRATE
}

private fun adjustSamplingRate(context: android.content.Context) {
    val c = context.config
    val available = samplingRates(context)
    if (c.samplingRate !in available) {
        c.samplingRate = if (DEFAULT_SAMPLING_RATE in available) DEFAULT_SAMPLING_RATE else available.lastOrNull() ?: DEFAULT_SAMPLING_RATE
    }
}

private fun micModes(): List<Int> = listOf(
    MediaRecorder.AudioSource.DEFAULT,
    MediaRecorder.AudioSource.CAMCORDER,
    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
    MediaRecorder.AudioSource.VOICE_PERFORMANCE,
    MediaRecorder.AudioSource.VOICE_RECOGNITION,
    MediaRecorder.AudioSource.UNPROCESSED,
)

private fun providerLabel(context: android.content.Context, transcriber: Transcriber, c: ComponentName): String {
    val pm = context.packageManager
    val name = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(c.packageName, 0)).toString() }.getOrDefault(c.packageName)
    return when (transcriber.contractFor(c)) {
        Transcriber.Contract.FILE -> context.getString(R.string.te_transcriber_file_api, name)
        Transcriber.Contract.CALLER_AUDIO -> context.getString(R.string.te_transcriber_caller_audio, name)
        null -> name
    }
}

private fun listeningCues(context: Context) = listOf(
    Cues.Mode.OFF to context.getString(R.string.te_cue_off),
    Cues.Mode.BEEP to context.getString(R.string.te_cue_tone),
    Cues.Mode.VOICE to context.getString(R.string.te_cue_go_ahead),
)

private fun progressCues(context: Context) = listOf(
    Cues.Mode.OFF to context.getString(R.string.te_cue_off),
    Cues.Mode.BEEP to context.getString(R.string.te_cue_progress_tones),
    Cues.Mode.VOICE to context.getString(R.string.te_cue_progress_voice),
)

/**
 * Shows the identity's nsec to back it up: a warning first, then the key with Copy. Remembers
 * which identity was shown, so the backup reminders go away.
 */
@Composable
fun NsecBackup(host: MainActivity, onDone: () -> Unit) {
    val context = LocalContext.current
    var nsec by remember { mutableStateOf<String?>(null) }
    val shown = nsec
    if (shown == null) {
        ConfirmDialog(
            stringResource(R.string.te_wn_show_nsec),
            stringResource(R.string.te_wn_nsec_warning),
            stringResource(R.string.te_wn_reveal),
            onConfirm = {
                runCatching { MarmotHost.revealNsec() }
                    .onSuccess { nsec = it; context.config.nsecShownFor = MarmotHost.npub.value }
                    .onFailure { host.toast(it.message ?: "?") }
            },
            destructive = true,
            onDismiss = { if (nsec == null) onDone() },
        )
    } else {
        ReadDialog(stringResource(R.string.te_wn_show_nsec), shown, onCopy = { host.copy(shown, sensitive = true) }, onDismiss = onDone, mono = true)
    }
}
