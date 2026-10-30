package app.thirdear.ui

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Forward10
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Notes
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Replay10
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.thirdear.R
import app.thirdear.actions.ActionStore
import app.thirdear.actions.LineCode
import app.thirdear.activities.MainActivity
import app.thirdear.extensions.config
import app.thirdear.extensions.recordActions
import app.thirdear.models.Recording
import app.thirdear.pipeline.NoteStore
import app.thirdear.whitenoise.IncomingMessage
import app.thirdear.whitenoise.IncomingStore
import app.thirdear.whitenoise.WhiteNoise
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fossify.commons.extensions.formatSize

enum class LogTab { RECORDINGS, REPLIES, DELETED }

/** The line a saved recording ran: its code and cord, matched through Third Ear's notes. */
private data class Tag(val code: String, val cord: Int)

@Composable
fun LogScreen(host: MainActivity, tab: LogTab, onTab: (LogTab) -> Unit, replies: Boolean = true) {
    val p = Te.colors
    val context = LocalContext.current
    val recordings by host.recordings
    val trashed by host.trashed
    val loading by host.loading
    val messages by IncomingStore.messages.collectAsStateWithLifecycle()
    val notes by NoteStore.notes.collectAsStateWithLifecycle()
    val version by ActionStore.changes.collectAsStateWithLifecycle()
    val player = rememberLogPlayer()
    var query by remember { mutableStateOf<String?>(null) }
    val useBin = context.config.useRecycleBin
    val shownTab = if ((tab == LogTab.DELETED && !useBin) || (tab == LogTab.REPLIES && !replies)) LogTab.RECORDINGS else tab

    LaunchedEffect(Unit) {
        host.reloadRecordings()
        withContext(Dispatchers.IO) { IncomingStore(context).load() }
    }

    val tags = remember(notes, version) {
        val actions = context.recordActions()
        notes.mapNotNull { n ->
            val name = n.savedName ?: return@mapNotNull null
            val i = actions.indexOfFirst { it.id == n.action.id }
            val action = actions.getOrNull(i) ?: n.action
            name to Tag(LineCode.of(action), cordOf(action, i.coerceAtLeast(0)))
        }.toMap()
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (query == null) {
                Text(stringResource(R.string.te_nav_log), style = TeType.screenTitle, color = p.ink, modifier = Modifier.weight(1f))
                IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Search, stringResource(R.string.te_search), tint = p.ink) }
            } else {
                OutlinedTextField(
                    value = query.orEmpty(),
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.te_search_log), style = TeType.body) },
                    textStyle = TeType.body,
                    shape = PlateShape,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                )
                IconButton(onClick = { query = null }) { Icon(Icons.Outlined.Close, stringResource(R.string.te_close), tint = p.ink) }
            }
        }

        // with White Noise on, replies live under Messages
        val tabs = listOfNotNull(LogTab.RECORDINGS, LogTab.REPLIES.takeIf { replies }, LogTab.DELETED.takeIf { useBin })
        val unread = messages.count { !it.read && it.ignoredBy == null }
        SecondaryTabRow(
            selectedTabIndex = tabs.indexOf(shownTab).coerceAtLeast(0),
            containerColor = p.face,
            contentColor = p.ink,
            indicator = {
                TabRowDefaults.SecondaryIndicator(
                    Modifier.tabIndicatorOffset(tabs.indexOf(shownTab).coerceAtLeast(0)),
                    height = 3.dp,
                    color = p.ink,
                )
            },
            divider = { Rule() },
        ) {
            tabs.forEach { t ->
                val count = when (t) {
                    LogTab.RECORDINGS -> recordings.size
                    LogTab.REPLIES -> unread
                    LogTab.DELETED -> trashed.size
                }
                Tab(
                    selected = t == shownTab,
                    onClick = { onTab(t) },
                    selectedContentColor = p.ink,
                    unselectedContentColor = p.inkMuted,
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(t.label()), style = TeType.smallStrong, maxLines = 1)
                            if (count > 0) {
                                Spacer(Modifier.width(6.dp))
                                Figures("$count", color = if (t == LogTab.REPLIES) p.ink else p.inkMuted, style = TeType.code)
                            }
                        }
                    },
                )
            }
        }

        val q = query?.trim().orEmpty()
        Box(Modifier.weight(1f)) {
            when (shownTab) {
                LogTab.RECORDINGS -> RecordingList(
                    host, recordings.filter { q.isEmpty() || it.title.contains(q, true) || host.transcriptOf(it)?.contains(q, true) == true },
                    tags, player, loading, searching = q.isNotEmpty(),
                )
                LogTab.REPLIES -> ReplyList(
                    messages.filter { q.isEmpty() || it.text.contains(q, true) || it.groupName.contains(q, true) },
                    context.recordActions(),
                )
                LogTab.DELETED -> DeletedList(host, trashed.filter { q.isEmpty() || it.title.contains(q, true) }, player)
            }
        }
        if (player.current != null && shownTab == LogTab.RECORDINGS) PlayerPlate(player)
    }
}

private fun LogTab.label() = when (this) {
    LogTab.RECORDINGS -> R.string.te_log_recordings
    LogTab.REPLIES -> R.string.te_log_replies
    LogTab.DELETED -> R.string.te_log_deleted
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordingList(host: MainActivity, list: List<Recording>, tags: Map<String, Tag>, player: LogPlayer, loading: Boolean, searching: Boolean) {
    val p = Te.colors
    val context = LocalContext.current
    var menuFor by remember { mutableStateOf<Recording?>(null) }
    var renaming by remember { mutableStateOf<Recording?>(null) }
    var deleting by remember { mutableStateOf<Recording?>(null) }
    var reading by remember { mutableStateOf<Pair<Recording, String>?>(null) }

    if (list.isEmpty()) {
        Empty(
            if (loading) stringResource(R.string.te_log_loading)
            else if (searching) stringResource(R.string.te_log_no_match)
            else stringResource(R.string.te_log_empty),
        )
    } else {
        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
            items(list, key = { it.id }) { r ->
                val tag = tags[r.title]
                val transcript = remember(r.title) { host.transcriptOf(r) }
                val active = player.current?.id == r.id
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (active) p.plate else p.face)
                        .clickable { player.play(r) }
                        .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Box(Modifier.width(52.dp).padding(top = 2.dp)) {
                        if (tag != null) CodeCell(tag.code, tag.cord) else Label("—")
                    }
                    Column(Modifier.weight(1f)) {
                        Text(r.title, style = TeType.bodyStrong, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Figures(
                            listOf(
                                DateUtils.formatDateTime(context, r.timestamp, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH),
                                "%d:%02d".format(r.duration / 60, r.duration % 60),
                                r.size.formatSize(),
                            ).joinToString(" · "),
                            modifier = Modifier.padding(top = 2.dp),
                        )
                        if (transcript != null) {
                            Text(transcript, style = TeType.small, color = p.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                    if (active) Lamp(if (player.playing) LampState.BUSY else LampState.OFF, Modifier.padding(top = 4.dp), size = 12.dp)
                    IconButton(onClick = { menuFor = r }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.te_more), tint = p.inkMuted) }
                }
                Rule(inset = 68.dp)
            }
        }
    }

    menuFor?.let { r ->
        val transcript = remember(r.title) { host.transcriptOf(r) }
        ModalBottomSheet(onDismissRequest = { menuFor = null }, containerColor = p.plate, shape = PlateShape) {
            Text(r.title, style = TeType.title, color = p.ink, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp), maxLines = 2)
            val close = { menuFor = null }
            if (transcript != null) {
                SheetItem(stringResource(R.string.te_show_transcript), Icons.Outlined.Notes, { reading = r to transcript; close() })
                SheetItem(stringResource(R.string.te_copy_transcript), Icons.Outlined.ContentCopy, { host.copy(transcript, confirmation = R.string.te_transcript_copied); close() })
            }
            SheetItem(stringResource(R.string.te_share), Icons.Outlined.Share, { host.share(r); close() })
            SheetItem(stringResource(R.string.te_open_with), Icons.AutoMirrored.Outlined.OpenInNew, { host.openWith(r); close() })
            SheetItem(stringResource(R.string.te_rename), Icons.Outlined.DriveFileRenameOutline, { renaming = r; close() })
            SheetItem(stringResource(R.string.te_delete), Icons.Outlined.Delete, { deleting = r; close() }, color = p.faultInk)
            Spacer(Modifier.height(24.dp))
        }
    }
    renaming?.let { r ->
        TextDialog(
            title = stringResource(R.string.te_rename),
            initial = r.title.substringBeforeLast('.'),
            confirm = stringResource(R.string.te_rename),
            onConfirm = { title -> host.rename(r, title) { err -> err?.let(host::toast) } },
            onDismiss = { renaming = null },
            validate = { if (it.isBlank()) context.getString(org.fossify.commons.R.string.empty_name) else null },
        )
    }
    deleting?.let { r ->
        val bin = context.config.useRecycleBin
        ConfirmDialog(
            title = stringResource(if (bin) R.string.te_move_to_bin_title else R.string.te_delete_title),
            message = r.title,
            confirm = stringResource(if (bin) R.string.te_move_to_bin else R.string.te_delete),
            destructive = true,
            onConfirm = { player.stopIf(r); if (bin) host.trash(r) else host.deleteForever(r) },
            neutral = if (bin) stringResource(R.string.te_delete_now) else null,
            onNeutral = if (bin) ({ player.stopIf(r); host.deleteForever(r) }) else null,
            onDismiss = { deleting = null },
        )
    }
    reading?.let { (_, text) ->
        ReadDialog(stringResource(R.string.te_transcript), text, onCopy = { host.copy(text, confirmation = R.string.te_transcript_copied) }, onDismiss = { reading = null })
    }
}

/** The player docked under the list: title, scrubber, −10 / play / +10. */
@Composable
private fun PlayerPlate(player: LogPlayer) {
    val p = Te.colors
    val r = player.current ?: return
    Column(Modifier.fillMaxWidth().background(p.plate)) {
        Rule()
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.title, style = TeType.smallStrong, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                IconButton(onClick = { player.pause(); player.stopIf(r) }) { Icon(Icons.Outlined.Close, stringResource(R.string.te_close), tint = p.inkMuted) }
            }
            player.error?.let { Text(it, style = TeType.small, color = p.faultInk) }
            Slider(
                value = player.positionMs.toFloat(),
                onValueChange = { player.seekTo(it.toInt()) },
                valueRange = 0f..player.durationMs.coerceAtLeast(1).toFloat(),
                colors = SliderDefaults.colors(thumbColor = p.ink, activeTrackColor = p.ink, inactiveTrackColor = p.rule),
                modifier = Modifier.padding(end = 8.dp),
            )
            Row(Modifier.fillMaxWidth().padding(end = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Figures(clock(player.positionMs))
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { player.skip(-10_000) }) { Icon(Icons.Outlined.Replay10, stringResource(R.string.te_back_10), tint = p.ink) }
                Box(
                    Modifier
                        .size(56.dp)
                        .clip(PlateShape)
                        .background(p.ink)
                        .clickable { player.toggle() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (player.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                        stringResource(if (player.playing) R.string.pause_recording else R.string.te_play),
                        tint = p.face,
                        modifier = Modifier.size(30.dp),
                    )
                }
                IconButton(onClick = { player.skip(10_000) }) { Icon(Icons.Outlined.Forward10, stringResource(R.string.te_forward_10), tint = p.ink) }
                Spacer(Modifier.weight(1f))
                Figures(clock(player.durationMs))
            }
        }
    }
}

private fun clock(ms: Int): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** Incoming White Noise messages, newest first: unread lamps lit, Play and Read aloud. */
@Composable
private fun ReplyList(list: List<IncomingMessage>, actions: List<app.thirdear.actions.RecordAction>) {
    val p = Te.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    if (list.isEmpty()) {
        Empty(stringResource(R.string.te_no_messages))
        return
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
        items(list, key = { it.id }) { m ->
            val i = actions.indexOfFirst { it.groupId == m.groupId }
            val line = actions.getOrNull(i)
            val unread = !m.read && m.ignoredBy == null
            Column(Modifier.fillMaxWidth().background(if (unread) p.plate else p.face).padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (line != null) {
                        CodeCell(LineCode.of(line), cordOf(line, i))
                        Spacer(Modifier.width(10.dp))
                    }
                    val sender = (m.senderName ?: m.sender.take(10)).takeIf { it != m.groupName }
                    Text(
                        listOfNotNull(m.groupName, sender).joinToString(" · "),
                        style = TeType.bodyStrong,
                        color = p.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (unread) Lamp(LampState.REPLY, size = 12.dp)
                    Figures(DateUtils.getRelativeTimeSpanString(m.at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(), Modifier.padding(start = 4.dp, end = 8.dp))
                }
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.Top) {
                    if (m.audio.isNotEmpty()) {
                        Icon(Icons.Outlined.Mic, contentDescription = stringResource(R.string.te_voice_message), tint = p.inkMuted, modifier = Modifier.padding(top = 2.dp, end = 6.dp).size(18.dp))
                    }
                    Text(m.text.ifBlank { stringResource(R.string.te_voice_message) }, style = TeType.body, color = if (m.ignoredBy != null) p.inkMuted else p.ink)
                }
                val state = when {
                    m.ignoredBy != null -> stringResource(R.string.te_ignored_list)
                    unread -> stringResource(R.string.te_unread)
                    else -> null
                }
                if (state != null) Label(state, Modifier.padding(top = 4.dp))
                Row {
                    if (m.audio.isNotEmpty()) {
                        PlainButton(stringResource(R.string.te_play), { scope.launch(Dispatchers.IO) { play(context, m, audio = true, text = false) } }, icon = Icons.Outlined.PlayArrow)
                    }
                    if (m.text.isNotBlank()) {
                        PlainButton(stringResource(R.string.te_read_aloud), { scope.launch(Dispatchers.IO) { play(context, m, audio = false, text = true) } }, icon = Icons.AutoMirrored.Outlined.VolumeUp)
                    }
                }
            }
            Rule()
        }
    }
}

private suspend fun play(context: android.content.Context, m: IncomingMessage, audio: Boolean, text: Boolean) {
    WhiteNoise.play(context, m, audio, text)
    runCatching { WhiteNoise.markRead(context, m.groupId, listOf(m.id)) }
}

@Composable
private fun DeletedList(host: MainActivity, list: List<Recording>, player: LogPlayer) {
    val p = Te.colors
    val context = LocalContext.current
    var emptying by remember { mutableStateOf(false) }
    var forever by remember { mutableStateOf<Recording?>(null) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.te_log_bin_note, list.sumOf { it.size }.formatSize()),
                style = TeType.small,
                color = p.inkMuted,
                modifier = Modifier.weight(1f),
            )
            if (list.isNotEmpty()) PlainButton(stringResource(R.string.te_empty_bin), { emptying = true }, color = p.faultInk)
        }
        if (list.isEmpty()) {
            Empty(stringResource(R.string.te_log_bin_empty))
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                items(list, key = { it.id }) { r ->
                    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp)) {
                        Text(r.title, style = TeType.bodyStrong, color = p.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Figures(
                            DateUtils.formatDateTime(context, r.timestamp, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH) + " · " + r.size.formatSize(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            PlainButton(stringResource(R.string.te_restore), { host.restore(r) }, icon = Icons.Outlined.RestoreFromTrash)
                            PlainButton(stringResource(R.string.te_delete_forever), { forever = r }, icon = Icons.Outlined.DeleteForever, color = p.faultInk)
                        }
                    }
                    Rule()
                }
            }
        }
    }
    if (emptying) {
        ConfirmDialog(
            title = stringResource(R.string.te_empty_bin),
            message = stringResource(org.fossify.commons.R.string.empty_recycle_bin_confirmation),
            confirm = stringResource(R.string.te_empty_bin),
            destructive = true,
            onConfirm = { host.emptyRecycleBin() },
            onDismiss = { emptying = false },
        )
    }
    forever?.let { r ->
        ConfirmDialog(
            title = stringResource(R.string.te_delete_title),
            message = r.title,
            confirm = stringResource(R.string.te_delete_forever),
            destructive = true,
            onConfirm = { player.stopIf(r); host.deleteForever(r) },
            onDismiss = { forever = null },
        )
    }
}

@Composable
private fun Empty(text: String) {
    val p = Te.colors
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.TopCenter) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(48.dp))
            Box(Modifier.size(width = 64.dp, height = 22.dp).clip(CellShape).border(1.dp, p.ruleStrong, CellShape))
            Spacer(Modifier.height(16.dp))
            Text(text, style = TeType.body, color = p.inkMuted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}
