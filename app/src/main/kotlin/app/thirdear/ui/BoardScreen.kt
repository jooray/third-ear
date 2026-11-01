package app.thirdear.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.thirdear.R
import app.thirdear.actions.ActionPlan
import app.thirdear.actions.ActionStore
import app.thirdear.actions.LineCode
import app.thirdear.activities.MainActivity
import app.thirdear.audio.BluetoothMics
import app.thirdear.audio.Source
import app.thirdear.extensions.config
import app.thirdear.extensions.inputLabel
import app.thirdear.extensions.openOsttPage
import app.thirdear.extensions.recordActions
import app.thirdear.extensions.setKeepScreenAwake
import app.thirdear.pipeline.Note
import app.thirdear.pipeline.NotePipeline
import app.thirdear.pipeline.NoteState
import app.thirdear.pipeline.NoteStore
import app.thirdear.pipeline.Transcriber
import app.thirdear.services.RecorderService
import app.thirdear.whitenoise.IncomingMessage
import app.thirdear.whitenoise.IncomingStore
import app.thirdear.whitenoise.WhiteNoise
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.log10
import kotlin.math.roundToInt

@Composable
fun BoardScreen(host: MainActivity, wide: Boolean, onOpenReplies: () -> Unit, onEditLine: (String) -> Unit) {
    val p = Te.colors
    val context = LocalContext.current
    val live by RecorderService.live.collectAsStateWithLifecycle()
    val version by ActionStore.changes.collectAsStateWithLifecycle()
    val notes by NoteStore.notes.collectAsStateWithLifecycle()
    val messages by IncomingStore.messages.collectAsStateWithLifecycle()
    val actions = remember(version) { context.recordActions() }
    val lines = remember(actions, notes, messages) { context.lines(actions, notes, messages) }
    val recording = live != null
    var confirmDiscard by remember { mutableStateOf(false) }
    var switchFrom by remember { mutableStateOf<Source?>(null) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            NoteStore(context).reload()
            IncomingStore(context).load()
        }
    }
    androidx.compose.runtime.LaunchedEffect(recording) {
        if (context.config.keepScreenOn) runCatching { host.setKeepScreenAwake(recording) }
    }

    Column(Modifier.fillMaxWidth()) {
    BoardBody(host, wide, live, lines, actions, notes, messages, onOpenReplies, onEditLine, { confirmDiscard = true }, { switchFrom = it })
    }

    if (confirmDiscard) {
        ConfirmDialog(
            title = stringResource(R.string.discard_recording),
            message = stringResource(R.string.discard_recording_confirmation),
            confirm = stringResource(R.string.te_discard),
            destructive = true,
            onConfirm = { RecorderService.discard(context) },
            onDismiss = { confirmDiscard = false },
        )
    }
    switchFrom?.let { from -> SourceSwitch(host, from, recording) { switchFrom = null } }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.BoardBody(
    host: MainActivity,
    wide: Boolean,
    live: RecorderService.Companion.Live?,
    lines: List<Line>,
    actions: List<app.thirdear.actions.RecordAction>,
    notes: List<Note>,
    messages: List<IncomingMessage>,
    onOpenReplies: () -> Unit,
    onEditLine: (String) -> Unit,
    onDiscard: () -> Unit,
    onSwitchFrom: (Source) -> Unit,
) {
    val p = Te.colors
    val context = LocalContext.current
    val fontScale = LocalDensity.current.fontScale
    val width = LocalConfiguration.current.screenWidthDp - if (wide) 88 else 0
    val columns = when {
        fontScale >= 1.3f && !wide -> 1
        wide -> (width / 200).coerceIn(2, 4)
        else -> 2
    }

    // search lives in the Log and settings in Setup: the board's top holds only the name and the input
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 4.dp)) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.te_wordmark), style = TeType.wordmark, color = p.ink)
        }
        // pinned: the microphone is always in view, also while scrolling the lines
        InputBar(live, onSwitch = onSwitchFrom)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .weight(1f)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
    ) {

        val l = live
        if (l != null) {
            Spacer(Modifier.height(12.dp))
            RecordingPlate(l)
            RecordingControls(host, l, lines, columns, onDiscard)
        } else {
            val transcriberMissing = remember(actions) {
                actions.any { ActionPlan.of(it).transcribe } && Transcriber(context).let { !it.osttInstalled() || !it.available(context.config.transcriber) }
            }
            if (transcriberMissing) {
                Spacer(Modifier.height(12.dp))
                Notice(stringResource(R.string.te_board_ostt_missing), action = stringResource(R.string.te_get), onAction = { context.openOsttPage() })
            }
            Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
                Label(stringResource(R.string.te_board_tap_to_record))
                Spacer(Modifier.weight(1f))
                Figures(pluralStringResource(R.plurals.te_board_lines, lines.size, lines.size), style = TeType.small)
            }
            LineField(host, lines, columns, recording = false, onEditLine = onEditLine)
            RepliesPlate(lines, messages, onOpenReplies)
            LastPlate(host, notes, lines)
        }
    }

}

/** The jack field: line keys in equal-height rows; while recording every key is a "Stop →". */
@Composable
private fun LineField(host: MainActivity, lines: List<Line>, columns: Int, recording: Boolean, onEditLine: (String) -> Unit) {
    val context = LocalContext.current
    lines.chunked(columns).forEach { row ->
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max).padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { line ->
                LineKey(
                    code = line.code,
                    cord = line.cord,
                    name = line.action.name,
                    strip = line.strip,
                    lamp = if (recording) LampState.OFF else line.lamp,
                    word = if (recording) null else line.word,
                    description = if (recording) stringResource(R.string.te_stop_with, line.action.name) + ". " + line.description else line.description,
                    kicker = if (recording) stringResource(R.string.te_board_stop) else null,
                    patchOnClick = recording,
                    onClick = {
                        if (RecorderService.live.value != null) RecorderService.stop(context, line.action.id) else host.startLine(line.action)
                    },
                    onLongClick = if (recording) null else ({ onEditLine(line.action.id) }),
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/**
 * While recording, the line it started on is the plan: one big "Stop → line" key does it. Pause
 * sits below with room between them, the other lines fold away behind one row (picked at the stop
 * when the plan changed), and Discard is last and furthest, behind a confirmation.
 */
@Composable
private fun RecordingControls(
    host: MainActivity,
    live: RecorderService.Companion.Live,
    lines: List<Line>,
    columns: Int,
    onDiscard: () -> Unit,
) {
    val p = Te.colors
    val context = LocalContext.current
    val started = lines.firstOrNull { it.action.id == live.action.id }
    val others = lines.filter { it.action.id != live.action.id }
    // a line deleted mid-recording has no key of its own: the field opens so the stop is still one tap
    var otherLines by remember(live.action.id) { mutableStateOf(started == null && !live.action.id.startsWith(CHAT_ACTION_PREFIX)) }
    val paused = live.status.paused

    if (started != null) {
        Spacer(Modifier.height(16.dp))
        LineKey(
            code = started.code,
            cord = started.cord,
            name = started.action.name,
            strip = started.strip,
            lamp = LampState.OFF,
            word = null,
            description = stringResource(R.string.te_stop_with, started.action.name) + ". " + started.description,
            kicker = stringResource(R.string.te_board_stop),
            reversed = true,
            patchOnClick = true,
            nameStyle = TeType.screenTitle,
            onClick = { RecorderService.stop(context, started.action.id) },
            onLongClick = null,
            modifier = Modifier.fillMaxWidth().heightIn(min = 168.dp),
        )
    }
    if (started == null && live.action.id.startsWith(CHAT_ACTION_PREFIX)) {
        // started from a conversation in Messages: no line of its own, but the stop is still its own
        Spacer(Modifier.height(16.dp))
        KeyButton(
            stringResource(R.string.te_stop_with, live.action.name),
            { RecorderService.stop(context, null) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp),
            icon = Icons.Outlined.Stop,
        )
    }
    // a gap, not a hairline: a slipped thumb on Pause must not send half a thought
    Spacer(Modifier.height(20.dp))
    OutlineButton(
        stringResource(if (paused) R.string.resume_recording else R.string.pause_recording),
        { RecorderService.togglePause(context) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
        icon = if (paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
    )

    if (others.isNotEmpty()) {
        Spacer(Modifier.height(20.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(PlateShape)
                .clickable(onClickLabel = stringResource(R.string.te_board_other_line)) { otherLines = !otherLines }
                .heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Label(stringResource(R.string.te_board_other_line))
            Spacer(Modifier.weight(1f))
            Figures(pluralStringResource(R.plurals.te_board_lines, others.size, others.size), style = TeType.small)
            Spacer(Modifier.width(4.dp))
            Icon(
                if (otherLines) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = p.inkMuted,
                modifier = Modifier.size(24.dp),
            )
        }
        if (otherLines) {
            Spacer(Modifier.height(8.dp))
            LineField(host, others, columns, recording = true, onEditLine = {})
        }
    }

    Spacer(Modifier.height(40.dp))
    Rule()
    PlainButton(stringResource(R.string.te_discard), onDiscard, modifier = Modifier.padding(top = 8.dp), icon = Icons.Outlined.Delete, color = p.faultInk)
}

/** The microphone that records (or will), with the live level, as one plate; tap to switch. */
@Composable
private fun InputBar(live: RecorderService.Companion.Live?, onSwitch: (Source) -> Unit) {
    val p = Te.colors
    val context = LocalContext.current
    val status = live?.status
    val bluetooth: Boolean
    val label: String
    if (status != null) {
        label = context.inputLabel(status)
        bluetooth = status.source == Source.BLUETOOTH
    } else {
        bluetooth = context.config.preferBluetooth && BluetoothMics.available(context)
        label = if (bluetooth) {
            BluetoothMics.name(context)?.let { context.getString(R.string.te_input_bluetooth_named, it) } ?: context.getString(R.string.te_input_bluetooth)
        } else {
            context.getString(R.string.te_input_phone)
        }
    }
    val note = when {
        status == null -> null
        status.silent && !status.paused -> context.getString(R.string.microphone_silent_text)
        status.fallbackReason != null && status.source == Source.PHONE -> context.getString(R.string.te_fallback_reason, status.fallbackReason)
        else -> null
    }
    val current = status?.source ?: if (bluetooth) Source.BLUETOOTH else Source.PHONE
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(PlateShape)
                .background(p.plate)
                .border(1.dp, p.rule, PlateShape)
                .clickable {
                    if (current == Source.PHONE && !BluetoothMics.available(context)) {
                        (context as? MainActivity)?.toast(context.getString(R.string.te_no_bluetooth_mic))
                    } else {
                        onSwitch(current)
                    }
                }
                .heightIn(min = 64.dp)
                .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (bluetooth) Icons.Outlined.Bluetooth else Icons.Outlined.Smartphone,
                contentDescription = null,
                tint = p.ink,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = TeType.bodyStrong, color = p.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(
                        when {
                            bluetooth -> R.string.te_route_bluetooth
                            context.config.preferBluetooth -> R.string.te_route_waiting_bluetooth
                            else -> R.string.te_route_phone
                        },
                    ),
                    style = TeType.small,
                    color = p.inkMuted,
                )
            }
            if (status != null && !status.paused && !status.starting) {
                Spacer(Modifier.width(8.dp))
                LevelMeter(status.amplitude)
            } else {
                Text(stringResource(R.string.te_switch), style = TeType.smallStrong, color = p.inkMuted)
            }
        }
        if (note != null) {
            Spacer(Modifier.height(8.dp))
            Notice(note, fault = status?.silent == true)
        }
    }
}

/** Twelve segments, log scale: the last two light in amber near clipping. */
@Composable
private fun LevelMeter(amplitude: Int) {
    val p = Te.colors
    val db = if (amplitude <= 0) -60.0 else 20 * log10(amplitude / 32767.0)
    val lit = (((db + 54) / 54) * 12).roundToInt().coerceIn(0, 12)
    Row(Modifier.height(28.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(12) { i ->
            val on = i < lit
            Box(
                Modifier
                    .width(4.dp)
                    .height((10 + i * 1.5).dp)
                    .clip(CellShape)
                    .background(if (!on) p.rule else if (i >= 10) p.busy else p.ink),
            )
        }
    }
}

/** While recording: the one giant figure, the state word and the line's code. */
@Composable
private fun RecordingPlate(live: RecorderService.Companion.Live) {
    val p = Te.colors
    val s = live.status
    val secs = s.durationMs / 1000
    val time = if (secs >= 3600) "%d:%02d:%02d".format(secs / 3600, secs / 60 % 60, secs % 60) else "%02d:%02d".format(secs / 60, secs % 60)
    val state = stringResource(
        when {
            s.starting -> R.string.te_rec_starting
            s.paused -> R.string.paused
            else -> R.string.recording
        },
    )
    Plate(Modifier.fillMaxWidth(), fill = p.recess) {
        // compact on purpose: the stop key must stay within reach below it
        Column(Modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // red means "not delivered" everywhere; a recording in progress is the amber working lamp
                Lamp(LampState.BUSY, size = 12.dp)
                Spacer(Modifier.width(4.dp))
                Label(state, color = p.ink)
                Spacer(Modifier.width(8.dp))
                CodeCell(LineCode.of(live.action), cord = cordForLive(live))
            }
            Text(
                time,
                // an hour adds three figures; the plate keeps its width
                style = if (secs >= 3600) TeType.timer.copy(fontSize = 60.sp, lineHeight = 64.sp) else TeType.timer,
                color = p.ink,
                maxLines = 1,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

@Composable
private fun cordForLive(live: RecorderService.Companion.Live): Int {
    val context = LocalContext.current
    val list = remember(live.action.id) { context.recordActions() }
    val i = list.indexOfFirst { it.id == live.action.id }
    return cordOf(live.action, if (i < 0) 0 else i)
}

/** Replies waiting, per line: the white lamps, with Play and Mark read. */
@Composable
private fun RepliesPlate(lines: List<Line>, messages: List<IncomingMessage>, onOpenReplies: () -> Unit) {
    val p = Te.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val waiting = lines.filter { it.unread > 0 }.distinctBy { it.action.groupId }
    if (waiting.isEmpty()) return
    Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Label(stringResource(R.string.te_board_replies))
        Spacer(Modifier.weight(1f))
        if (waiting.size > 1) {
            Text(
                stringResource(R.string.te_mark_all_read),
                style = TeType.smallStrong,
                color = p.inkMuted,
                modifier = Modifier.clickable { scope.launch(Dispatchers.IO) { WhiteNoise.markAllRead(context) } }.padding(
                    vertical = 14.dp,
                    horizontal = 12.dp,
                ),
            )
        }
        Text(
            stringResource(R.string.te_board_all_replies),
            style = TeType.smallStrong,
            color = p.ink,
            modifier = Modifier.clickable(onClick = onOpenReplies).padding(vertical = 14.dp),
        )
    }
    Plate(Modifier.fillMaxWidth()) {
        Column {
            waiting.forEachIndexed { i, line ->
                if (i > 0) Rule()
                Column(Modifier.padding(start = 12.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CodeCell(line.code, line.cord)
                        Spacer(Modifier.width(10.dp))
                        Text(line.action.groupName ?: line.action.name, style = TeType.bodyStrong, color = p.ink, modifier = Modifier.weight(1f))
                        Lamp(LampState.REPLY)
                    }
                    val latest = messages
                        .filter { it.groupId == line.action.groupId && !it.read && it.ignoredBy == null }
                        .maxByOrNull { it.at }
                    if (latest != null) {
                        Text(
                            listOfNotNull(
                                (latest.senderName ?: latest.sender.take(10)).takeIf { it != latest.groupName }?.let { "$it:" },
                                latest.text.ifBlank { stringResource(R.string.te_voice_message) },
                            ).joinToString(" "),
                            style = TeType.body,
                            color = p.ink,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 8.dp, end = 4.dp),
                        )
                    }
                    Text(
                        pluralStringResource(R.plurals.te_word_replies, line.unread, line.unread),
                        style = TeType.small,
                        color = p.inkMuted,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    Row {
                        PlainButton(stringResource(R.string.te_play), {
                            scope.launch(Dispatchers.IO) { line.action.groupId?.let { WhiteNoise.playUnread(context, it) } }
                        }, icon = Icons.AutoMirrored.Outlined.VolumeUp)
                        PlainButton(stringResource(R.string.te_mark_read), {
                            scope.launch(Dispatchers.IO) { markGroupRead(context, line.action.groupId) }
                        }, color = p.inkMuted)
                    }
                }
            }
        }
    }
}

private suspend fun markGroupRead(context: Context, groupId: String?) {
    groupId ?: return
    val ids = IncomingStore(context).unread(groupId).map { it.id }
    runCatching { WhiteNoise.markRead(context, groupId, ids) }
}

/** The last recording: where it went, how far it got, its transcript. */
@Composable
private fun LastPlate(host: MainActivity, notes: List<Note>, lines: List<Line>) {
    val p = Te.colors
    val context = LocalContext.current
    val n = notes.firstOrNull { it.state != NoteState.RECORDING } ?: return
    val transcript = remember(n.id, n.state, n.transcript) { if (n.transcript) NoteStore(context).transcript(n.id) else null }
    var reading by remember { mutableStateOf(false) }
    val line = lines.firstOrNull { it.action.id == n.action.id }
    val secs = n.durationMs / 1000
    var details by remember { mutableStateOf(false) }
    val (lamp, status) = when (n.state) {
        NoteState.PROCESSING -> LampState.BUSY to context.stepWord(n)
        NoteState.FAILED -> LampState.FAULT to context.plainFailure(n.error)
        else -> LampState.OFF to (n.savedName?.let { context.getString(R.string.te_state_saved_as, it) } ?: context.getString(R.string.te_state_done))
    }
    Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
        Label(stringResource(R.string.te_last_recording))
        Spacer(Modifier.weight(1f))
        Figures("%d:%02d · %s".format(
            secs / 60,
            secs % 60,
            android.text.format.DateUtils.formatDateTime(context, n.startedAt, android.text.format.DateUtils.FORMAT_SHOW_TIME),
        ))
    }
    Plate(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 12.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CodeCell(line?.code ?: LineCode.of(n.action), line?.cord ?: 0)
                Spacer(Modifier.width(10.dp))
                Text(n.action.name, style = TeType.bodyStrong, color = p.ink, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Lamp(lamp)
            }
            Text(
                status,
                style = TeType.small,
                color = when (lamp) {
                    LampState.FAULT -> p.faultInk
                    LampState.BUSY -> p.busyInk
                    else -> p.inkMuted
                },
                modifier = Modifier.padding(top = 6.dp, end = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
            n.transcriptError?.let {
                Text(context.getString(R.string.te_transcript_failed, it), style = TeType.small, color = p.faultInk, modifier = Modifier.padding(top = 2.dp))
            }
            PipelineStrip(n, Modifier.padding(top = 10.dp, end = 4.dp))
            if (transcript != null) {
                Text(
                    transcript,
                    style = TeType.body,
                    color = p.ink,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp, end = 4.dp).clickable { reading = true },
                )
            }
            Row(Modifier.padding(top = 4.dp)) {
                if (transcript != null) {
                    PlainButton(stringResource(R.string.te_copy_transcript), {
                        NotePipeline(context).copyToClipboard(transcript)
                        host.toast(context.getString(R.string.te_transcript_copied))
                    }, icon = Icons.Outlined.ContentCopy)
                }
                if (n.state == NoteState.FAILED) {
                    PlainButton(stringResource(R.string.te_retry), { RecorderService.retry(context, n.id) }, icon = Icons.Outlined.Refresh)
                    if (n.error != null && context.plainFailure(n.error) != n.error) {
                        PlainButton(stringResource(R.string.te_details), { details = true }, color = p.inkMuted)
                    }
                }
            }
        }
    }
    if (details) {
        ReadDialog(
            stringResource(R.string.te_details),
            n.error.orEmpty(),
            onCopy = { host.copy(n.error.orEmpty()) },
            onDismiss = { details = false },
            mono = true,
        )
    }
    if (reading && transcript != null) {
        ReadDialog(
            stringResource(R.string.te_transcript),
            transcript,
            onCopy = { NotePipeline(context).copyToClipboard(transcript) },
            onDismiss = { reading = false },
        )
    }
}

/** The microphone switch, as in OSTT's keyboard: it asks first; mid-recording the file continues. */
@Composable
private fun SourceSwitch(host: MainActivity, from: Source, recording: Boolean, onDone: () -> Unit) {
    val context = LocalContext.current
    val to = if (from == Source.BLUETOOTH) Source.PHONE else Source.BLUETOOTH
    val toBluetooth = to == Source.BLUETOOTH
    val keep = stringResource(if (toBluetooth) R.string.te_switch_keep_bluetooth else R.string.te_switch_keep_phone)
    val apply = { keepIt: Boolean ->
        if (keepIt) {
            context.config.preferBluetooth = toBluetooth
            if (toBluetooth) host.requestBluetoothPermission()
        }
        if (RecorderService.live.value != null) RecorderService.switchSource(context, to)
    }
    ConfirmDialog(
        title = stringResource(if (toBluetooth) R.string.te_switch_to_bluetooth_title else R.string.te_switch_to_phone_title),
        message = stringResource(
            when {
                recording -> R.string.te_switch_message
                toBluetooth -> R.string.te_setting_bluetooth_desc
                else -> R.string.te_input_phone
            },
        ),
        confirm = if (recording) stringResource(R.string.te_switch_once) else keep,
        onConfirm = { apply(!recording) },
        neutral = if (recording) keep else null,
        onNeutral = if (recording) ({ apply(true) }) else null,
        onDismiss = onDone,
    )
}

/** A failure in words: the pipeline's own messages pass through, anything technical gets a plain sentence. */
fun Context.plainFailure(error: String?): String {
    val known = listOf(
        R.string.te_error_network, R.string.te_error_no_transcriber, R.string.te_error_no_speech, R.string.te_error_no_speech_no_ostt, R.string.te_error_no_webhook,
        R.string.te_error_no_group, R.string.te_error_folder_access, R.string.te_error_background_start,
    ).map { getString(it) }
    return if (error != null && (error in known || error.startsWith(getString(R.string.te_error_save, "").trim()))) {
        getString(R.string.te_not_delivered_why, error)
    } else {
        getString(R.string.te_not_delivered_saved)
    }
}

/** The pipeline as strip cells lit in sequence: saved, transcribed, delivered. */
@Composable
private fun PipelineStrip(n: Note, modifier: Modifier = Modifier) {
    val p = Te.colors
    val context = LocalContext.current
    val plan = ActionPlan.of(n.action)
    data class Cell(val label: String, val state: LampState?)
    val deliver = when (n.action.destination) {
        app.thirdear.actions.Destination.SAVE -> null
        app.thirdear.actions.Destination.COPY_TRANSCRIPT -> R.string.te_pipe_copied
        app.thirdear.actions.Destination.SHARE -> R.string.te_pipe_shared
        app.thirdear.actions.Destination.WHITE_NOISE -> R.string.te_pipe_sent
        app.thirdear.actions.Destination.WEBHOOK -> R.string.te_pipe_posted
    }
    val steps = listOfNotNull(R.string.te_pipe_saved, R.string.te_pipe_transcribed.takeIf { plan.transcribe }, deliver)
    val current = when {
        n.state == NoteState.DONE -> steps.size
        n.state == NoteState.FAILED -> if (n.savedName == null) 0 else steps.lastIndex
        n.step == app.thirdear.pipeline.NoteStep.SENDING -> steps.lastIndex
        n.step == app.thirdear.pipeline.NoteStep.TRANSCRIBING || n.step == app.thirdear.pipeline.NoteStep.WAITING_FOR_TRANSCRIBER -> 1
        else -> 0
    }
    val cells = steps.mapIndexed { i, res ->
        val state = when {
            i < current -> LampState.OFF // done
            i == current && n.state == NoteState.FAILED -> LampState.FAULT
            i == current -> LampState.BUSY
            else -> null // not reached
        }
        val transcriptFailed = res == R.string.te_pipe_transcribed && n.transcriptError != null
        Cell(context.getString(res), if (transcriptFailed) LampState.FAULT else state)
    }
    Row(modifier.fillMaxWidth().clip(CellShape).background(p.strip).height(IntrinsicSize.Min)) {
        cells.forEachIndexed { i, c ->
            if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(p.stripRule))
            val (bg, fg) = when (c.state) {
                LampState.BUSY -> p.busy to androidx.compose.ui.graphics.Color(0xFF17140F)
                LampState.FAULT -> p.fault to androidx.compose.ui.graphics.Color(0xFFF7F4EA)
                LampState.OFF -> p.strip to p.stripInk
                else -> p.strip to p.stripInk.copy(alpha = 0.4f)
            }
            Row(Modifier.weight(1f).background(bg).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (c.state == LampState.OFF) {
                    Icon(Icons.Outlined.Check, contentDescription = null, tint = fg, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Text(c.label.uppercase(), style = TeType.strip, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
