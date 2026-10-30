package app.thirdear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.thirdear.R
import app.thirdear.actions.ActionPlan
import app.thirdear.actions.ActionStore
import app.thirdear.actions.Content
import app.thirdear.actions.Destination
import app.thirdear.actions.LineCode
import app.thirdear.actions.RecordAction
import app.thirdear.activities.MainActivity
import app.thirdear.extensions.config
import app.thirdear.extensions.contentLabel
import app.thirdear.extensions.describe
import app.thirdear.extensions.destinationLabel
import app.thirdear.extensions.recordActions
import app.thirdear.pipeline.Transcriber
import app.thirdear.tiles.ActionTileService
import app.thirdear.whitenoise.GroupSettings
import app.thirdear.whitenoise.GroupSettingsStore
import app.thirdear.whitenoise.MarmotHost
import app.thirdear.whitenoise.ReadMode
import app.thirdear.whitenoise.ReplyMode
import app.thirdear.whitenoise.WhiteNoise
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun LinesScreen(host: MainActivity, editId: String?, onEdited: () -> Unit, onOpenSetup: () -> Unit) {
    val p = Te.colors
    val context = LocalContext.current
    val store = remember { ActionStore(context) }
    val version by ActionStore.changes.collectAsStateWithLifecycle()
    val actions = remember(version) { context.recordActions() }
    val transcriber = remember(version) { Transcriber(context).available(context.config.transcriber) }
    var editing by remember { mutableStateOf<RecordAction?>(null) }
    var creating by remember { mutableStateOf(false) }
    // a new line with its fields filled in (from a group without a line)
    var draft by remember { mutableStateOf<RecordAction?>(null) }
    var deleting by remember { mutableStateOf<RecordAction?>(null) }

    LaunchedEffect(editId, actions) {
        if (editId != null) {
            editing = actions.firstOrNull { it.id == editId }
            onEdited()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 96.dp)) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.te_nav_lines), style = TeType.screenTitle, color = p.ink)
            }
            Text(
                stringResource(R.string.te_lines_intro),
                style = TeType.small,
                color = p.inkMuted,
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp),
            )
            Rule()
            actions.forEachIndexed { i, a ->
                val problems = ActionPlan.of(a).problems(transcriber)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { editing = a }
                        .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(60.dp)) { CodeCell(LineCode.of(a), cordOf(a, i)) }
                    Column(Modifier.weight(1f)) {
                        Text(a.name, style = TeType.bodyStrong, color = p.ink)
                        Text(context.describe(a), style = TeType.small, color = p.inkMuted, modifier = Modifier.padding(top = 2.dp))
                        problems.forEach {
                            Text(context.problemText(it), style = TeType.smallStrong, color = p.faultInk, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                    Row {
                        IconButton(onClick = { store.move(a.id, -1); ActionTileService.refreshAll(context) }, enabled = i > 0) {
                            Icon(Icons.Outlined.ArrowUpward, stringResource(R.string.te_action_move_up), tint = if (i > 0) p.ink else p.rule)
                        }
                        IconButton(onClick = { store.move(a.id, 1); ActionTileService.refreshAll(context) }, enabled = i < actions.lastIndex) {
                            Icon(Icons.Outlined.ArrowDownward, stringResource(R.string.te_action_move_down), tint = if (i < actions.lastIndex) p.ink else p.rule)
                        }
                    }
                }
                Rule(inset = 76.dp)
            }

            GroupsSection(host, actions, onOpenSetup, onNewLine = { g ->
                draft = RecordAction(name = g.name, destination = Destination.WHITE_NOISE, groupId = g.id, groupName = g.name)
                creating = true
            })
        }
        ExtendedFloatingActionButton(
            onClick = { draft = null; creating = true },
            icon = { Icon(Icons.Outlined.Add, null) },
            text = { Text(stringResource(R.string.te_add_line), style = TeType.button) },
            containerColor = p.ink,
            contentColor = p.face,
            shape = PlateShape,
            // flat like every plate: no elevation in any state
            elevation = androidx.compose.material3.FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).border(1.dp, p.ruleStrong, PlateShape),
        )
    }

    if (creating || editing != null) {
        val original = editing
        LineEditor(
            original = original,
            draft = if (original == null) draft else null,
            index = original?.let { o -> actions.indexOfFirst { it.id == o.id } } ?: actions.size,
            usedCords = actions.mapIndexed { i, a -> cordOf(a, i) }.toSet(),
            onSave = { a ->
                store.upsert(a)
                ActionTileService.refreshAll(context)
            },
            onDelete = original?.let { o ->
                {
                    if (actions.size <= 1) host.toast(context.getString(R.string.te_at_least_one_action)) else deleting = o
                }
            },
            onDismiss = { creating = false; editing = null; draft = null },
        )
    }
    deleting?.let { a ->
        ConfirmDialog(
            title = stringResource(R.string.te_action_delete_confirm, a.name),
            message = null,
            confirm = stringResource(R.string.te_delete),
            destructive = true,
            onConfirm = {
                store.delete(a.id)
                ActionTileService.refreshAll(context)
                editing = null
            },
            onDismiss = { deleting = null },
        )
    }
}

fun android.content.Context.problemText(p: ActionPlan.Problem) = getString(
    when (p) {
        ActionPlan.Problem.NO_NAME -> R.string.te_problem_no_name
        ActionPlan.Problem.NO_GROUP -> R.string.te_problem_no_group
        ActionPlan.Problem.NO_WEBHOOK_URL -> R.string.te_problem_no_webhook
        ActionPlan.Problem.NO_TRANSCRIBER -> R.string.te_problem_no_transcriber
    },
)

/** White Noise groups: invites to accept, and how each group's replies reach the ear. */
@Composable
private fun GroupsSection(host: MainActivity, actions: List<RecordAction>, onOpenSetup: () -> Unit, onNewLine: (MarmotHost.Group) -> Unit) {
    val p = Te.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val groups by MarmotHost.groups.collectAsStateWithLifecycle()
    val phase by MarmotHost.phase.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Pair<String, String>?>(null) }
    var leaving by remember { mutableStateOf<Pair<String, String>?>(null) }
    var backingUp by remember { mutableStateOf(false) }
    val npub by MarmotHost.npub.collectAsStateWithLifecycle()
    var settingsVersion by remember { mutableStateOf(0) }
    val on = context.config.whiteNoiseEnabled

    LaunchedEffect(on) {
        // invites arrive through relays; look again while the screen is open
        while (on && isActive) {
            runCatching { MarmotHost.refreshGroups() }
            delay(15_000)
        }
    }

    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 28.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
        Label(stringResource(R.string.te_wn_groups))
        Spacer(Modifier.weight(1f))
        Label(stringResource(R.string.te_setting_white_noise))
    }
    Rule()
    if (!on || phase != MarmotHost.Phase.READY) {
        SettingRow(
            title = stringResource(if (!on) R.string.te_lines_wn_off else R.string.te_wn_starting),
            value = stringResource(R.string.te_setting_white_noise_desc),
            onClick = onOpenSetup,
        )
        Rule()
        return
    }
    val invites = groups.filter { it.pending }
    invites.forEach { g ->
        Column(Modifier.fillMaxWidth().background(p.plate).padding(start = 16.dp, end = 8.dp, top = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Lamp(LampState.REPLY, size = 12.dp)
                Spacer(Modifier.width(6.dp))
                Label(stringResource(R.string.te_wn_invite), color = p.ink)
            }
            Text(g.name.ifBlank { g.id.take(12) }, style = TeType.bodyStrong, color = p.ink, modifier = Modifier.padding(top = 4.dp))
            Row {
                PlainButton(stringResource(R.string.te_wn_accept), { scope.launch { runCatching { MarmotHost.accept(g.id) }.onFailure { host.toast(it.message ?: "?") } } }, icon = Icons.Outlined.Check)
                PlainButton(stringResource(R.string.te_wn_decline), { scope.launch { runCatching { MarmotHost.decline(g.id) }.onFailure { host.toast(it.message ?: "?") } } }, color = p.inkMuted)
            }
        }
        Rule()
    }
    val joined = groups.filter { it.member && !it.pending }
    if (joined.isEmpty()) {
        Text(stringResource(R.string.te_no_groups), style = TeType.small, color = p.inkMuted, modifier = Modifier.padding(16.dp))
        Rule()
    }
    val settingsStore = remember { GroupSettingsStore(context) }
    joined.forEach { g ->
        val s = remember(g.id, settingsVersion) { settingsStore.get(g.id) }
        SettingRow(title = g.name, value = context.groupSummary(s), onClick = { editing = g.id to g.name })
        // joined but nothing records into it yet: say what to do next
        if (actions.none { it.groupId == g.id }) {
            Notice(
                stringResource(R.string.te_wn_no_line_yet, g.name),
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                action = stringResource(R.string.te_wn_make_line),
                onAction = { onNewLine(g) },
            )
        }
        Rule(inset = 16.dp)
    }
    if (joined.isNotEmpty() && npub != null && context.config.nsecShownFor != npub) {
        Notice(
            stringResource(R.string.te_wn_backup_nudge),
            Modifier.padding(16.dp),
            action = stringResource(R.string.te_wn_back_up),
            onAction = { backingUp = true },
        )
    }
    if (backingUp) NsecBackup(host, onDone = { backingUp = false; settingsVersion++ })
    editing?.let { (id, name) ->
        GroupDialog(name, settingsStore.get(id), onSave = { settingsStore.put(id, it); settingsVersion++ }, onLeave = { leaving = id to name }, onDismiss = { editing = null })
    }
    leaving?.let { (id, name) ->
        ConfirmDialog(
            title = stringResource(R.string.te_wn_leave_confirm, name),
            message = stringResource(R.string.te_wn_leave_confirm_desc),
            confirm = stringResource(R.string.te_wn_leave),
            destructive = true,
            onConfirm = {
                editing = null
                scope.launch { runCatching { MarmotHost.leave(id) }.onFailure { host.toast(it.message ?: "?") } }
            },
            onDismiss = { leaving = null },
        )
    }
}

fun android.content.Context.groupSummary(s: GroupSettings): String {
    val replies = getString(
        when (s.replies) {
            ReplyMode.OFF -> R.string.te_replies_off
            ReplyMode.VOICE -> R.string.te_replies_voice
            ReplyMode.VOICE_AND_TEXT -> R.string.te_replies_voice_text
        },
    )
    if (s.replies == ReplyMode.OFF) return replies
    val read = getString(if (s.read == ReadMode.AUTO) R.string.te_reading_auto else R.string.te_reading_ask)
    return "$replies · $read"
}

@Composable
private fun GroupDialog(name: String, initial: GroupSettings, onSave: (GroupSettings) -> Unit, onLeave: () -> Unit, onDismiss: () -> Unit) {
    val p = Te.colors
    var replies by remember { mutableStateOf(initial.replies) }
    var read by remember { mutableStateOf(initial.read) }
    var ignore by remember { mutableStateOf(initial.ignore.joinToString("\n")) }
    FullDialog(
        title = name,
        onDismiss = onDismiss,
        onSave = { onSave(GroupSettings(replies, read, GroupSettings.parseIgnore(ignore))); onDismiss() },
    ) {
        Label(stringResource(R.string.te_group_replies), Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        listOf(ReplyMode.OFF to R.string.te_replies_off, ReplyMode.VOICE to R.string.te_replies_voice, ReplyMode.VOICE_AND_TEXT to R.string.te_replies_voice_text)
            .forEach { (m, label) -> RadioRow(stringResource(label), replies == m) { replies = m } }
        if (replies != ReplyMode.OFF) {
            Label(stringResource(R.string.te_group_reading), Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp))
            RadioRow(stringResource(R.string.te_reading_auto), read == ReadMode.AUTO) { read = ReadMode.AUTO }
            RadioRow(stringResource(R.string.te_reading_ask), read == ReadMode.ASK) { read = ReadMode.ASK }
            Text(stringResource(R.string.te_reading_car_note), style = TeType.small, color = p.inkMuted, modifier = Modifier.padding(16.dp))
        }
        Label(stringResource(R.string.te_group_ignore), Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp))
        OutlinedTextField(
            value = ignore,
            onValueChange = { ignore = it },
            minLines = 2,
            textStyle = TeType.body,
            shape = PlateShape,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(24.dp))
        Rule()
        PlainButton(stringResource(R.string.te_wn_leave), onLeave, modifier = Modifier.padding(horizontal = 8.dp), color = p.faultInk)
    }
}

@Composable
fun RadioRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    val p = Te.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = p.ink, unselectedColor = p.inkMuted))
        Spacer(Modifier.width(14.dp))
        Text(label, style = TeType.body, color = p.ink)
    }
}

@Composable
fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, description: String? = null, enabled: Boolean = true) {
    val p = Te.colors
    SettingRow(
        title = title,
        value = description,
        enabled = enabled,
        onClick = { onChange(!checked) },
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = if (enabled) onChange else null,
                enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = p.face, checkedTrackColor = p.ink, checkedBorderColor = p.ink,
                    uncheckedThumbColor = p.inkMuted, uncheckedTrackColor = p.recess, uncheckedBorderColor = p.ruleStrong,
                ),
            )
        },
    )
}

/** A full-screen editor: close, title, Save; content scrolls above the keyboard. */
@Composable
fun FullDialog(title: String, onDismiss: () -> Unit, onSave: () -> Unit, saveEnabled: Boolean = true, content: @Composable () -> Unit) {
    val p = Te.colors
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        ThirdEarTheme(p.dark) {
            Column(Modifier.fillMaxSize().background(p.face).windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
                Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, stringResource(R.string.te_close), tint = p.ink) }
                    Text(title, style = TeType.title, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 8.dp))
                    PlainButton(stringResource(R.string.te_save), onSave, color = if (saveEnabled) p.ink else p.inkMuted)
                }
                Rule()
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) { content() }
            }
        }
    }
}

/** Creates or edits a line: name, code, cord, destination, content, transcript; shows the key as it will look. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LineEditor(
    original: RecordAction?,
    draft: RecordAction?,
    index: Int,
    usedCords: Set<Int>,
    onSave: (RecordAction) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val p = Te.colors
    val context = LocalContext.current
    val transcriber = remember { Transcriber(context).available(context.config.transcriber) }
    val firstFree = remember { (0 until Cords.all.size).firstOrNull { it !in usedCords } ?: index }
    var a by remember { mutableStateOf(original ?: draft?.copy(cord = firstFree) ?: RecordAction(name = "", cord = firstFree)) }
    var pickGroup by remember { mutableStateOf(false) }
    var tried by remember { mutableStateOf(false) }
    val cord = cordOf(a, index)
    val problems = ActionPlan.of(a).problems(transcriber)
    val blocking = problems.filter { it != ActionPlan.Problem.NO_TRANSCRIBER }

    FullDialog(
        title = stringResource(if (original == null) R.string.te_new_line else R.string.te_edit_line),
        onDismiss = onDismiss,
        onSave = {
            tried = true
            if (blocking.isEmpty()) {
                onSave(a.copy(name = a.name.trim(), code = a.code?.trim()?.uppercase()?.ifEmpty { null }, cord = cord))
                onDismiss()
            }
        },
        saveEnabled = blocking.isEmpty(),
    ) {
        // the key as it will sit on the board
        Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth(0.5f)) {
            LineKey(
                code = LineCode.of(a),
                cord = cord,
                name = a.name.ifBlank { stringResource(R.string.te_action_name) },
                strip = context.stripCells(a),
                lamp = LampState.OFF,
                word = null,
                description = context.describe(a),
                onClick = {},
                onLongClick = null,
            )
        }
        Text(context.describe(a), style = TeType.small, color = p.inkMuted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))

        Field(stringResource(R.string.te_action_name), a.name, { a = a.copy(name = it) }, error = tried && ActionPlan.Problem.NO_NAME in problems)
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = a.code.orEmpty(),
                onValueChange = { v -> a = a.copy(code = v.filter { it.isLetterOrDigit() }.uppercase().take(LineCode.MAX)) },
                label = { Text(stringResource(R.string.te_line_code), style = TeType.small) },
                placeholder = { Text(LineCode.fromName(a.name), style = TeType.body) },
                singleLine = true,
                textStyle = TeType.body,
                shape = PlateShape,
                modifier = Modifier.width(140.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.te_line_code_hint), style = TeType.small, color = p.inkMuted, modifier = Modifier.weight(1f))
        }

        Label(stringResource(R.string.te_line_cord), Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp))
        FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), maxItemsInEachRow = 4) {
            Cords.all.indices.forEach { c ->
                val selected = c == cord
                val name = stringResource(CORD_NAMES[c])
                Box(
                    Modifier
                        .size(48.dp)
                        .selectable(selected = selected, role = Role.RadioButton) { a = a.copy(cord = c) }
                        .semantics { contentDescription = name },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Cords.color(c))
                            .border(if (selected) 3.dp else 1.dp, if (selected) p.ink else p.rule, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) Icon(Icons.Outlined.Check, null, tint = Cords.ink(c), modifier = Modifier.size(18.dp))
                    }
                }
            }
        }

        Label(stringResource(R.string.te_action_destination), Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp))
        Destination.entries.forEach { d -> RadioRow(context.destinationLabel(d), a.destination == d) { a = a.copy(destination = d) } }

        when (a.destination) {
            Destination.WHITE_NOISE -> SettingRow(
                title = stringResource(R.string.te_action_group),
                value = a.groupName ?: stringResource(R.string.te_action_pick_group),
                onClick = { pickGroup = true },
            )
            Destination.WEBHOOK -> {
                Field(stringResource(R.string.te_action_webhook_url), a.webhookUrl.orEmpty(), { a = a.copy(webhookUrl = it.trim().ifEmpty { null }) }, error = tried && ActionPlan.Problem.NO_WEBHOOK_URL in problems)
                Field(stringResource(R.string.te_action_webhook_auth), a.webhookAuth.orEmpty(), { a = a.copy(webhookAuth = it.trim().ifEmpty { null }) })
            }
            else -> {}
        }

        if (a.destination in setOf(Destination.SHARE, Destination.WHITE_NOISE, Destination.WEBHOOK)) {
            Label(stringResource(R.string.te_action_content), Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp))
            Content.entries.forEach { c -> RadioRow(context.contentLabel(c), a.content == c) { a = a.copy(content = c) } }
        }

        Spacer(Modifier.height(12.dp))
        val implied = ActionPlan.of(a.copy(transcribe = false, copyTranscript = false)).transcribe
        SwitchRow(stringResource(R.string.te_action_transcribe), a.transcribe || implied, { a = a.copy(transcribe = it) }, enabled = !implied)
        if (a.destination != Destination.COPY_TRANSCRIPT) {
            SwitchRow(stringResource(R.string.te_action_copy), a.copyTranscript, { a = a.copy(copyTranscript = it) })
        }

        problems.filter { tried || it == ActionPlan.Problem.NO_TRANSCRIBER }.forEach {
            Notice(context.problemText(it), Modifier.padding(horizontal = 16.dp, vertical = 4.dp), fault = it != ActionPlan.Problem.NO_TRANSCRIBER)
        }

        if (onDelete != null) {
            Spacer(Modifier.height(16.dp))
            Rule()
            PlainButton(stringResource(R.string.te_delete_line), onDelete, icon = Icons.Outlined.Delete, color = p.faultInk, modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp))
        }
    }

    if (pickGroup) {
        val groups = remember { WhiteNoise.joinedGroups() }
        when {
            !context.config.whiteNoiseEnabled -> { (context as? MainActivity)?.toast(context.getString(R.string.te_white_noise_off)); pickGroup = false }
            groups.isEmpty() -> { (context as? MainActivity)?.toast(context.getString(R.string.te_no_groups)); pickGroup = false }
            else -> ChoiceDialog(
                title = stringResource(R.string.te_action_group),
                options = groups.map { it to it.name },
                selected = groups.firstOrNull { it.id == a.groupId },
                onPick = { g -> a = a.copy(groupId = g.id, groupName = g.name) },
                onDismiss = { pickGroup = false },
            )
        }
    }
}

private val CORD_NAMES = listOf(
    R.string.te_cord_red, R.string.te_cord_green, R.string.te_cord_blue, R.string.te_cord_yellow,
    R.string.te_cord_white, R.string.te_cord_violet, R.string.te_cord_orange, R.string.te_cord_teal,
)

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, error: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, style = TeType.small) },
        singleLine = true,
        isError = error,
        textStyle = TeType.body,
        shape = PlateShape,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    )
}
