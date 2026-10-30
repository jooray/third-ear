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
import app.thirdear.actions.LineCode
import app.thirdear.extensions.recordActions
import app.thirdear.whitenoise.IncomingMessage
import app.thirdear.whitenoise.IncomingStore
import app.thirdear.whitenoise.MarmotHost
import app.thirdear.whitenoise.WhiteNoise
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One group in the list: its newest message (Third Ear's own included) and what is still unread. */
private data class Chat(val id: String, val name: String, val last: ChatLine?, val unread: Int)

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
fun MessagesScreen() {
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
        // groups left or not loaded yet still show what was saved from them
        val saved = messages.map { it.groupId to it.groupName }
        (joined + saved).distinctBy { it.first }.map { (id, name) ->
            val own = messages.filter { it.groupId == id }
            val last = listOfNotNull(latest[id], own.maxByOrNull { it.at }?.line()).maxByOrNull { it.at }
            Chat(id, name, last, own.count { it.unread() })
        }.sortedByDescending { it.last?.at ?: 0L }
    }

    val chat = open?.let { id -> chats.firstOrNull { it.id == id } }
    if (chat != null) {
        BackHandler { open = null }
        Conversation(chat, messages, ready, onBack = { open = null })
    } else {
        ChatList(chats, ready, onOpen = { open = it })
    }
}

private fun MarmotHost.TimelineMessage.line(saved: List<IncomingMessage>): ChatLine {
    val s = saved.firstOrNull { it.id == id }
    return ChatLine(
        id, mine, sender = if (mine) null else s?.senderName ?: MarmotHost.displayName(sender) ?: sender.take(10),
        text = text, at = at, voice = audio.isNotEmpty(), unread = s?.unread() == true, ignored = s?.ignoredBy != null,
        timeline = this, saved = s,
    )
}

@Composable
private fun ChatList(chats: List<Chat>, ready: Boolean, onOpen: (String) -> Unit) {
    val p = Te.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val actions = remember { context.recordActions() }
    val unread = chats.sumOf { it.unread }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.te_nav_messages), style = TeType.screenTitle, color = p.ink, modifier = Modifier.weight(1f))
            if (unread > 0) {
                PlainButton(stringResource(R.string.te_mark_all_read), { scope.launch(Dispatchers.IO) { WhiteNoise.markAllRead(context) } }, icon = Icons.Outlined.DoneAll)
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
                        .background(if (c.unread > 0) p.plate else p.face)
                        .clickable { onOpen(c.id) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Box(Modifier.width(52.dp).padding(top = 2.dp)) {
                        actions.getOrNull(i)?.let { CodeCell(LineCode.of(it), cordOf(it, i)) } ?: Label("—")
                    }
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(c.name, style = TeType.bodyStrong, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            c.last?.let { Figures(ago(it.at), Modifier.padding(start = 8.dp)) }
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
private fun Conversation(chat: Chat, messages: List<IncomingMessage>, ready: Boolean, onBack: () -> Unit) {
    val p = Te.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var timeline by remember(chat.id) { mutableStateOf<List<MarmotHost.TimelineMessage>?>(null) }
    LaunchedEffect(chat.id, ready, messages) {
        if (ready) timeline = withContext(Dispatchers.IO) { runCatching { MarmotHost.timeline(chat.id) }.getOrNull() }
    }
    val saved = messages.filter { it.groupId == chat.id }
    val lines = timeline?.map { it.line(saved) } ?: saved.sortedBy { it.at }.map { it.line() }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.te_back), tint = p.ink) }
            Text(chat.name, style = TeType.title, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (chat.unread > 0) {
                PlainButton(stringResource(R.string.te_mark_all_read), { scope.launch(Dispatchers.IO) { WhiteNoise.markAllRead(context, chat.id) } }, icon = Icons.Outlined.DoneAll)
            }
        }
        Rule()
        if (lines.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.TopCenter) {
                Text(stringResource(R.string.te_chats_empty), style = TeType.body, color = p.inkMuted, modifier = Modifier.padding(top = 48.dp))
            }
            return
        }
        // newest at the bottom, where the list starts, as in any chat
        LazyColumn(
            Modifier.fillMaxSize(),
            reverseLayout = true,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(lines.asReversed(), key = { it.id }) { m -> Bubble(chat.id, m) }
        }
    }
}

@Composable
private fun Bubble(groupId: String, m: ChatLine) {
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
                    Figures(ago(m.at), Modifier.padding(start = 8.dp))
                    if (m.unread) Lamp(LampState.REPLY, Modifier.padding(start = 6.dp), size = 10.dp)
                }
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.Top) {
                    if (m.voice) Icon(Icons.Outlined.Mic, stringResource(R.string.te_voice_message), tint = p.inkMuted, modifier = Modifier.padding(top = 2.dp, end = 6.dp).size(18.dp))
                    Text(m.text.ifBlank { stringResource(R.string.te_voice_message) }, style = TeType.body, color = if (m.ignored) p.inkMuted else p.ink)
                }
                if (m.ignored) Label(stringResource(R.string.te_ignored_list), Modifier.padding(top = 4.dp))
                Row {
                    if (m.voice) {
                        PlainButton(stringResource(R.string.te_play), { scope.launch(Dispatchers.IO) { play(context, groupId, m, voice = true) } }, icon = Icons.Outlined.PlayArrow)
                    }
                    if (m.text.isNotBlank() && !m.mine) {
                        PlainButton(stringResource(R.string.te_read_aloud), { scope.launch(Dispatchers.IO) { play(context, groupId, m, voice = false) } }, icon = Icons.AutoMirrored.Outlined.VolumeUp)
                    }
                }
            }
        }
    }
}

private suspend fun play(context: Context, groupId: String, m: ChatLine, voice: Boolean) {
    when {
        voice && m.timeline != null -> WhiteNoise.playTimeline(context, groupId, m.timeline)
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

private fun ago(at: Long) = DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
