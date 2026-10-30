package app.thirdear.ui

import android.content.Context
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.thirdear.R
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import app.thirdear.actions.Content
import app.thirdear.actions.RecordAction
import app.thirdear.actions.Destination as ActionDestination
import app.thirdear.activities.MainActivity
import app.thirdear.extensions.config
import app.thirdear.extensions.inputLabel
import app.thirdear.pipeline.Note
import app.thirdear.pipeline.NoteState
import app.thirdear.pipeline.NoteStore
import app.thirdear.services.RecorderService
import app.thirdear.actions.LineCode
import app.thirdear.extensions.recordActions
import app.thirdear.whitenoise.IncomingMessage
import app.thirdear.whitenoise.IncomingStore
import app.thirdear.whitenoise.MarmotHost
import app.thirdear.whitenoise.WhiteNoise
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One group in the list: its newest message (Third Ear's own included) and what is still unread. */
private data class Chat(val id: String, val name: String, val last: ChatLine?, val unread: Int, val joined: Boolean)

/** A message as the conversation shows it, from the group's timeline or, offline, from the saved list. */
private data class ChatLine(
    val id: String,
    val mine: Boolean,
    val sender: String?,
    val text: String,
    val at: Long,
    val voice: Boolean,
    val unread: Boolean,
    val ignored: Boolean,
    val timeline: MarmotHost.TimelineMessage? = null,
    val saved: IncomingMessage? = null,
)

private fun IncomingMessage.unread() = !read && ignoredBy == null

private fun IncomingMessage.line() = ChatLine(
    id, mine = false, sender = senderName ?: sender.take(10), text = text, at = at, voice = audio.isNotEmpty(),
    unread = unread(), ignored = ignoredBy != null, saved = this,
)

/** White Noise as a chat app: the groups with their messages, opened one at a time; Mark all as read on both. */
@Composable
fun MessagesScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val messages by IncomingStore.messages.collectAsStateWithLifecycle()
    val groups by MarmotHost.groups.collectAsStateWithLifecycle()
    val phase by MarmotHost.phase.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { IncomingStore(context).load() } }

    val ready = phase == MarmotHost.Phase.READY
    // the newest timeline message per group; reloaded when a message comes in
    var latest by remember { mutableStateOf<Map<String, ChatLine>>(emptyMap()) }
    LaunchedEffect(ready, groups, messages) {
        if (!ready) return@LaunchedEffect
        latest = withContext(Dispatchers.IO) {
            groups.filter { it.member && !it.pending }.mapNotNull { g ->
                runCatching { MarmotHost.timeline(g.id, 1).lastOrNull() }.getOrNull()?.let { g.id to it.line(messages) }
            }.toMap()
        }
    }
    val chats = remember(groups, messages, latest) {
        val joined = groups.filter { it.member && !it.pending }.map { it.id to it.name }
        val ids = joined.map { it.first }.toSet()
        // groups left or not loaded yet still show what was saved from them
        val saved = messages.map { it.groupId to it.groupName }
        (joined + saved).distinctBy { it.first }.map { (id, name) ->
            val own = messages.filter { it.groupId == id }
            val last = listOfNotNull(latest[id], own.maxByOrNull { it.at }?.line()).maxByOrNull { it.at }
            Chat(id, name, last, own.count { it.unread() }, joined = id in ids)
        }.sortedByDescending { it.last?.at ?: 0L }
    }

    val chat = open?.let { id -> chats.firstOrNull { it.id == id } }
    Box(modifier) { Panes(chats, chat, messages, ready, onOpen = { open = it }, onClose = { open = null }) }
}

@Composable
private fun Panes(chats: List<Chat>, chat: Chat?, messages: List<IncomingMessage>, ready: Boolean, onOpen: (String?) -> Unit, onClose: () -> Unit) {
    if (LocalConfiguration.current.screenWidthDp >= TWO_PANE_DP) {
        // a tablet: the groups on the left, the open one beside them, as in any chat app
        val shown = chat ?: chats.firstOrNull()
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(LIST_PANE_DP.dp).fillMaxHeight()) { ChatList(chats, ready, onOpen = onOpen, selected = shown?.id) }
            Box(Modifier.width(1.dp).fillMaxHeight().background(Te.colors.rule))
            Box(Modifier.weight(1f).fillMaxHeight()) {
                if (shown != null) Conversation(shown, messages, ready, onBack = null)
            }
        }
    } else if (chat != null) {
        BackHandler(onBack = onClose)
        Conversation(chat, messages, ready, onBack = onClose)
    } else {
        Box(Modifier.widthIn(max = 600.dp)) { ChatList(chats, ready, onOpen = onOpen) }
    }
}

/** From this width the list and the conversation are shown side by side. */
private const val TWO_PANE_DP = 800
private const val LIST_PANE_DP = 360

private fun MarmotHost.TimelineMessage.line(saved: List<IncomingMessage>): ChatLine {
    val s = saved.firstOrNull { it.id == id }
    return ChatLine(
        id, mine, sender = if (mine) null else s?.senderName ?: MarmotHost.displayName(sender) ?: sender.take(10),
        text = text, at = at, voice = audio.isNotEmpty(), unread = s?.unread() == true, ignored = s?.ignoredBy != null,
        timeline = this, saved = s,
    )
}

@Composable
private fun ChatList(chats: List<Chat>, ready: Boolean, onOpen: (String) -> Unit, selected: String? = null) {
    val p = Te.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val actions = remember { context.recordActions() }
    val unread = chats.sumOf { it.unread }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.te_nav_messages), style = TeType.screenTitle, color = p.ink, modifier = Modifier.weight(1f))
            if (unread > 0) {
                PlainButton(
                    stringResource(R.string.te_mark_all_read),
                    { scope.launch(Dispatchers.IO) { WhiteNoise.markAllRead(context) } },
                    icon = Icons.Outlined.DoneAll,
                )
            }
        }
        Rule()
        if (!ready && chats.isNotEmpty()) Notice(stringResource(R.string.te_chats_offline), Modifier.padding(16.dp))
        if (chats.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.TopCenter) {
                Text(stringResource(R.string.te_chats_no_groups), style = TeType.body, color = p.inkMuted, modifier = Modifier.padding(top = 48.dp))
            }
            return
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
            items(chats, key = { it.id }) { c ->
                val i = actions.indexOfFirst { it.groupId == c.id }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (c.id == selected) p.plateHi else if (c.unread > 0) p.plate else p.face)
                        .clickable { onOpen(c.id) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Box(Modifier.width(52.dp).padding(top = 2.dp)) {
                        actions.getOrNull(i)?.let { CodeCell(LineCode.of(it), cordOf(it, i)) } ?: Label("—")
                    }
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                c.name,
                                style = TeType.bodyStrong,
                                color = p.ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            c.last?.let { Figures(listTime(it.at), Modifier.padding(start = 8.dp)) }
                        }
                        Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                c.last?.let { preview(context, it) } ?: "",
                                style = TeType.small,
                                color = if (c.unread > 0) p.ink else p.inkMuted,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (c.unread > 0) {
                                Spacer(Modifier.width(8.dp))
                                Lamp(LampState.REPLY, size = 12.dp)
                                Figures("${c.unread}", Modifier.padding(start = 4.dp), color = p.ink, style = TeType.code)
                            }
                        }
                    }
                }
                Rule(inset = 68.dp)
            }
        }
    }
}

@Composable
private fun Conversation(chat: Chat, messages: List<IncomingMessage>, ready: Boolean, onBack: (() -> Unit)?) {
    val p = Te.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var timeline by remember(chat.id) { mutableStateOf<List<MarmotHost.TimelineMessage>?>(null) }
    // what Third Ear itself sent (a typed message, a note through the pipeline) shows up as well
    val notes by NoteStore.notes.collectAsStateWithLifecycle()
    var sent by remember { mutableIntStateOf(0) }
    LaunchedEffect(chat.id, ready, messages, notes, sent) {
        if (ready) timeline = withContext(Dispatchers.IO) { runCatching { MarmotHost.timeline(chat.id) }.getOrNull() }
    }
    val saved = messages.filter { it.groupId == chat.id }
    val player = rememberChatPlayer()
    val speed = remember(chat.id) { WhiteNoise.speedFor(context, chat.id) }
    // the timeline is a window (the last 100); saved replies outside it are still part of the conversation
    val lines = timeline?.let { t ->
        val ids = t.map { it.id }.toSet()
        (t.map { it.line(saved) } + saved.filter { it.id !in ids }.map { it.line() }).sortedBy { it.at }
    } ?: saved.sortedBy { it.at }.map { it.line() }

    // the keyboard shrinks the conversation, it doesn't push the header away
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.te_back), tint = p.ink) }
            } else {
                Spacer(Modifier.width(16.dp))
            }
            Text(chat.name, style = TeType.title, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (chat.unread > 0) {
                PlainButton(
                    stringResource(R.string.te_mark_all_read),
                    { scope.launch(Dispatchers.IO) { WhiteNoise.markAllRead(context, chat.id) } },
                    icon = Icons.Outlined.DoneAll,
                )
            }
        }
        Rule()
        Box(Modifier.weight(1f)) {
            if (lines.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.TopCenter) {
                    Text(stringResource(R.string.te_chats_empty), style = TeType.body, color = p.inkMuted, modifier = Modifier.padding(top = 48.dp))
                }
            } else {
                // newest at the bottom, where the list starts, as in any chat
                val listState = rememberLazyListState()
                val newest = lines.lastOrNull()?.id
                // a new message shows itself when the reader is at the bottom (not while reading back)
                LaunchedEffect(newest) { if (listState.firstVisibleItemIndex <= 1) listState.animateScrollToItem(0) }
                LazyColumn(
                    Modifier.fillMaxSize(),
                    state = listState,
                    reverseLayout = true,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                    // a short conversation sits at the bottom, by the composer
                    verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom),
                ) {
                    items(lines.asReversed(), key = { it.id }) { m -> Bubble(chat.id, m, player, speed) }
                }
            }
        }
        if (chat.joined && ready) Composer(chat, notes, onSendDone = { sent++ })
    }
}

/** The id prefix of a conversation's record key, an action that is not a line. */
const val CHAT_ACTION_PREFIX = "chat-"

/** Recording from a conversation: to this group, the audio or only the transcript. */
fun chatAction(groupId: String, groupName: String, transcript: Boolean) = RecordAction(
    id = CHAT_ACTION_PREFIX + groupId.take(12) + if (transcript) "-t" else "-a",
    name = groupName,
    destination = ActionDestination.WHITE_NOISE,
    content = if (transcript) Content.TRANSCRIPT else Content.AUDIO,
    groupId = groupId,
    groupName = groupName,
)

/**
 * Record (the primary key) or type, for any joined group, also one without a line on the Board.
 * A recording sends the audio or only the transcript, as the switch above the key says.
 */
@Composable
private fun Composer(chat: Chat, notes: List<Note>, onSendDone: () -> Unit) {
    val p = Te.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val live by RecorderService.live.collectAsStateWithLifecycle()
    var transcript by remember { mutableStateOf(context.config.chatSendsTranscript) }
    var text by rememberSaveable(chat.id) { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val mine = live?.takeIf { it.action.groupId == chat.id && it.action.id.startsWith(CHAT_ACTION_PREFIX) }
    // the newest note for this group still on its way, or stuck
    val pending = notes.firstOrNull { it.action.groupId == chat.id && it.state != NoteState.RECORDING }
        ?.takeIf { it.state == NoteState.PROCESSING || it.state == NoteState.FAILED }

    Column(Modifier.fillMaxWidth().background(p.plate)) {
        Rule()
        pending?.let { n ->
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                val failed = n.state == NoteState.FAILED
                Lamp(if (failed) LampState.FAULT else LampState.BUSY, size = 10.dp)
                Text(
                    if (failed) context.plainFailure(n.error) else context.stepWord(n),
                    style = TeType.small,
                    color = if (failed) p.faultInk else p.inkMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
                if (failed) PlainButton(stringResource(R.string.te_retry), { RecorderService.retry(context, n.id) }, icon = Icons.Outlined.Refresh)
            }
        }
        val l = mine
        if (l != null) {
            // recording: what it hears, then Discard or Send
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                // the amber working lamp, as on the Board; the label says whether it already hears
                Lamp(LampState.BUSY, size = 12.dp)
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    val secs = l.status.durationMs / 1000
                    Figures("%d:%02d".format(secs / 60, secs % 60), color = p.ink, style = TeType.title)
                    Text(context.inputLabel(l.status), style = TeType.small, color = p.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                PlainButton(stringResource(R.string.te_discard), { RecorderService.discard(context) }, color = p.faultInk)
                Spacer(Modifier.width(8.dp))
                KeyButton(
                    stringResource(if (l.action.content == Content.TRANSCRIPT) R.string.te_chat_send_transcript else R.string.te_chat_send_audio),
                    { RecorderService.stop(context, null) },
                    icon = Icons.AutoMirrored.Outlined.Send,
                    modifier = Modifier.heightIn(min = 56.dp),
                )
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Label(stringResource(R.string.te_chat_record_sends), Modifier.weight(1f))
                Toggle(stringResource(R.string.te_chat_audio), !transcript) { transcript = false; context.config.chatSendsTranscript = false }
                Spacer(Modifier.width(6.dp))
                Toggle(stringResource(R.string.te_chat_transcript), transcript) { transcript = true; context.config.chatSendsTranscript = true }
            }
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                val busy = live != null
                Box(
                    Modifier
                        .size(56.dp)
                        .clip(PlateShape)
                        .background(if (busy) p.rule else p.ink)
                        .clickable(enabled = !busy) { (context as? MainActivity)?.startLine(chatAction(chat.id, chat.name, transcript)) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Mic, stringResource(R.string.te_chat_record), tint = p.face, modifier = Modifier.size(28.dp))
                }
                Spacer(Modifier.width(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text(stringResource(R.string.te_chat_message), style = TeType.body) },
                    textStyle = TeType.body,
                    shape = PlateShape,
                    maxLines = 5,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                )
                IconButton(
                    enabled = text.isNotBlank() && !sending,
                    onClick = {
                        val body = text.trim()
                        sending = true
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { runCatching { WhiteNoise.sendText(context, chat.id, body) } }
                            sending = false
                            ok.onSuccess { text = ""; onSendDone() }
                                .onFailure { (context as? MainActivity)?.toast(context.getString(R.string.te_chat_not_sent, it.message ?: "?")) }
                        }
                    },
                ) {
                    Icon(Icons.AutoMirrored.Outlined.Send, stringResource(R.string.te_chat_send), tint = if (text.isNotBlank()) p.ink else p.inkMuted)
                }
            }
        }
    }
}

@Composable
private fun Toggle(text: String, selected: Boolean, onClick: () -> Unit) {
    val p = Te.colors
    Text(
        text,
        style = TeType.smallStrong,
        color = if (selected) p.face else p.inkMuted,
        modifier = Modifier
            .clip(CellShape)
            .background(if (selected) p.ink else p.plateHi)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun Bubble(groupId: String, m: ChatLine, player: ChatPlayer, speed: Float) {
    val p = Te.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxWidth(), contentAlignment = if (m.mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Plate(Modifier.widthIn(max = 320.dp), fill = if (m.mine) p.plateHi else p.plate) {
            Column(Modifier.padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (m.mine) stringResource(R.string.te_chats_me) else m.sender.orEmpty(),
                        style = TeType.smallStrong,
                        color = p.inkMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Figures(clockTime(context, m.at), Modifier.padding(start = 8.dp))
                    if (m.unread) Lamp(LampState.REPLY, Modifier.padding(start = 6.dp), size = 10.dp)
                }
                if (m.voice) {
                    VoicePlayer(
                        id = m.id,
                        groupSpeed = speed,
                        player = player,
                        load = { voiceFiles(context, groupId, m) },
                        onPlayToEnd = { if (m.unread) scope.launch(Dispatchers.IO) { runCatching { WhiteNoise.markRead(context, groupId, listOf(m.id)) } } },
                        modifier = Modifier.padding(top = 8.dp, end = 4.dp),
                    )
                }
                // a voice message's caption is its transcript
                if (m.text.isNotBlank() || !m.voice) {
                    Text(
                        m.text.ifBlank { stringResource(R.string.te_voice_message) },
                        style = TeType.body,
                        color = if (m.ignored) p.inkMuted else p.ink,
                        modifier = Modifier.padding(top = if (m.voice) 6.dp else 4.dp),
                    )
                }
                if (m.ignored) Label(stringResource(R.string.te_ignored_list), Modifier.padding(top = 4.dp))
                Row {
                    if (m.text.isNotBlank() && !m.mine) {
                        PlainButton(
                            stringResource(R.string.te_read_aloud),
                            { scope.launch(Dispatchers.IO) { play(context, groupId, m, voice = false) } },
                            icon = Icons.AutoMirrored.Outlined.VolumeUp,
                        )
                    }
                }
            }
        }
    }
}

private suspend fun voiceFiles(context: Context, groupId: String, m: ChatLine): List<File> = when {
    m.timeline != null -> WhiteNoise.audioFiles(context, groupId, m.timeline)
    else -> m.saved?.audio.orEmpty().map(::File).filter { it.exists() }
}

/** Reads a text message aloud (voice messages play in their bubble). */
private suspend fun play(context: Context, groupId: String, m: ChatLine, voice: Boolean) {
    when {
        m.saved != null -> WhiteNoise.play(context, m.saved, audio = voice, text = !voice)
        !voice -> WhiteNoise.play(context, IncomingMessage(m.id, groupId, "", "", null, m.text, m.at), audio = false, text = true)
    }
    if (m.unread) runCatching { WhiteNoise.markRead(context, groupId, listOf(m.id)) }
}

private fun preview(context: Context, m: ChatLine): String {
    val who = if (m.mine) context.getString(R.string.te_chats_me) else m.sender
    val text = m.text.ifBlank { context.getString(R.string.te_voice_message) }
    return listOfNotNull(who?.let { "$it:" }, if (m.voice) "🎤 $text" else text).joinToString(" ")
}

/** In a bubble: the time, with the day when it is not today. */
private fun clockTime(context: Context, at: Long): String = DateUtils.formatDateTime(
    context,
    at,
    if (DateUtils.isToday(at)) DateUtils.FORMAT_SHOW_TIME else DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
)

/** In the list, as chat apps do: the time today, the day before that. */
private fun listTime(at: Long): String =
    DateUtils.formatSameDayTime(at, System.currentTimeMillis(), java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).toString()
