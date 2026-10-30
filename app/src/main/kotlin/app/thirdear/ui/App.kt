package app.thirdear.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cable
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.thirdear.R
import app.thirdear.activities.MainActivity
import app.thirdear.extensions.config
import app.thirdear.whitenoise.IncomingStore
import app.thirdear.whitenoise.MarmotHost

enum class Destination { BOARD, MESSAGES, LOG, LOG_REPLIES, LINES, SETUP }

private data class NavItem(val dest: Destination, val label: Int, val icon: ImageVector)

private val NAV = listOf(
    NavItem(Destination.BOARD, R.string.te_nav_board, Icons.Outlined.GridView),
    NavItem(Destination.MESSAGES, R.string.te_nav_messages, Icons.Outlined.ChatBubbleOutline),
    NavItem(Destination.LOG, R.string.te_nav_log, Icons.Outlined.History),
    NavItem(Destination.LINES, R.string.te_nav_lines, Icons.Outlined.Cable),
    NavItem(Destination.SETUP, R.string.te_nav_setup, Icons.Outlined.Tune),
)

/** Width from which the destinations move to a rail and the board takes more columns. */
const val EXPANDED_DP = 600

@Composable
fun ThirdEarApp(host: MainActivity, recordToResult: Boolean) {
    val p = Te.colors
    var dest by rememberSaveable { mutableStateOf(Destination.BOARD) }
    var logTab by rememberSaveable { mutableStateOf(LogTab.RECORDINGS) }
    var editId by rememberSaveable { mutableStateOf<String?>(null) }
    val requested by host.requested
    // read again whenever White Noise starts or stops (the switch in Setup does both)
    val phase by MarmotHost.phase.collectAsStateWithLifecycle()
    val chats = host.config.whiteNoiseEnabled || phase != MarmotHost.Phase.OFF
    LaunchedEffect(requested) {
        when (val r = requested) {
            null -> {}
            Destination.LOG_REPLIES -> if (chats) dest = Destination.MESSAGES else { dest = Destination.LOG; logTab = LogTab.REPLIES }
            else -> dest = r
        }
        host.requested.value = null
    }
    BackHandler(enabled = dest != Destination.BOARD && !recordToResult) { dest = Destination.BOARD }

    val messages by IncomingStore.messages.collectAsStateWithLifecycle()
    val unread = messages.count { !it.read && it.ignoredBy == null }
    val wide = LocalConfiguration.current.screenWidthDp >= EXPANDED_DP
    val shown = when {
        dest == Destination.LOG_REPLIES -> Destination.LOG
        dest == Destination.MESSAGES && !chats -> Destination.BOARD
        else -> dest
    }
    // with White Noise on, its messages have their own destination and the badge moves there
    val nav = NAV.filter { it.dest != Destination.MESSAGES || chats }
    val badged = if (chats) Destination.MESSAGES else Destination.LOG
    val openReplies = { if (chats) dest = Destination.MESSAGES else { logTab = LogTab.REPLIES; dest = Destination.LOG } }

    val content: @Composable (Modifier) -> Unit = { m ->
        // the bottom bar takes the navigation inset on phones; the rail leaves it to the content
        val sides = if (wide) WindowInsetsSides.Top + WindowInsetsSides.Bottom + WindowInsetsSides.End else WindowInsetsSides.Top + WindowInsetsSides.Horizontal
        Box(m.windowInsetsPadding(WindowInsets.safeDrawing.only(sides))) {
            // lists and settings keep a reading measure on wide screens; the board takes the width
            val measure = Modifier.widthIn(max = 600.dp)
            when (shown) {
                Destination.BOARD -> BoardScreen(host, wide, onOpenReplies = openReplies, onEditLine = { editId = it; dest = Destination.LINES })
                // wide enough, the list and the open conversation sit side by side and take the width
                Destination.MESSAGES -> MessagesScreen()
                Destination.LOG, Destination.LOG_REPLIES -> Box(measure) { LogScreen(host, logTab, onTab = { logTab = it }, replies = !chats) }
                Destination.LINES -> Box(measure) { LinesScreen(host, editId, onEditDone = { editId = null }, onOpenSetup = { dest = Destination.SETUP }) }
                Destination.SETUP -> Box(measure) { SetupScreen(host) }
            }
        }
    }

    if (wide) {
        Row(Modifier.fillMaxSize().background(p.face)) {
            NavigationRail(
                containerColor = p.plate,
                // the rail's plate runs under the system bars; only its content keeps clear of them
                windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical),
                modifier = Modifier.fillMaxHeight(),
                header = { Text("3E", style = TeType.wordmark, color = p.ink, modifier = Modifier.padding(vertical = 12.dp)) },
            ) {
                nav.forEach { item ->
                    NavigationRailItem(
                        selected = shown == item.dest,
                        onClick = { dest = item.dest },
                        icon = { NavIcon(item, if (item.dest == badged) unread else 0) },
                        label = { Text(stringResource(item.label), style = TeType.small) },
                        colors = NavigationRailItemDefaults.colors(
                            selectedIconColor = p.ink, selectedTextColor = p.ink, indicatorColor = p.plateHi,
                            unselectedIconColor = p.inkMuted, unselectedTextColor = p.inkMuted,
                        ),
                    )
                }
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(p.rule))
            content(Modifier.weight(1f).fillMaxHeight())
        }
    } else {
        Column(Modifier.fillMaxSize().background(p.face)) {
            content(Modifier.weight(1f))
            Rule()
            NavigationBar(containerColor = p.plate, tonalElevation = 0.dp) {
                nav.forEach { item ->
                    NavigationBarItem(
                        selected = shown == item.dest,
                        onClick = { dest = item.dest },
                        icon = { NavIcon(item, if (item.dest == badged) unread else 0) },
                        label = { Text(stringResource(item.label), style = TeType.small) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = p.ink, selectedTextColor = p.ink, indicatorColor = p.plateHi,
                            unselectedIconColor = p.inkMuted, unselectedTextColor = p.inkMuted,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun NavIcon(item: NavItem, badge: Int) {
    val p = Te.colors
    if (badge > 0) {
        // a waiting reply is the white lamp; on the paper face it prints in ink
        val fill = if (p.dark) p.reply else p.ink
        val ink = if (p.dark) p.face else p.plate
        BadgedBox(badge = { Badge(containerColor = fill, contentColor = ink) { Text("$badge", style = TeType.code) } }) {
            Icon(item.icon, contentDescription = null)
        }
    } else {
        Icon(item.icon, contentDescription = null)
    }
}
