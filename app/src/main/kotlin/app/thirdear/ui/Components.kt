package app.thirdear.ui

import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

val PlateShape = RoundedCornerShape(6.dp)
val CellShape = RoundedCornerShape(3.dp)

/** True when the system asks for no animations (Remove animations / animator scale 0). */
@Composable
fun reduceMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/**
 * An opal line lamp in a dark bezel. Busy lamps flash like a supervisory lamp, in hard steps (no
 * easing: a relay, not a fade); with animations off they stay lit.
 */
@Composable
fun Lamp(lamp: LampState, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    val p = Te.colors
    val still = reduceMotion()
    val lit by produceState(true, lamp, still) {
        value = true
        if (lamp == LampState.BUSY && !still) {
            while (true) {
                delay(700)
                value = false
                delay(300)
                value = true
            }
        }
    }
    val fill = when (lamp) {
        LampState.OFF -> p.lampOff
        LampState.BUSY -> if (lit) p.busy else p.lampOff
        LampState.REPLY -> p.reply
        LampState.FAULT -> p.fault
    }
    Box(modifier.size(size + 8.dp), contentAlignment = Alignment.Center) {
        if (lamp == LampState.REPLY) {
            Box(Modifier.size(size + 8.dp).clip(CircleShape).background((if (p.dark) p.reply else p.busy).copy(alpha = if (p.dark) 0.16f else 0.28f)))
        }
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(fill)
                .border(2.dp, if (lamp == LampState.OFF || (lamp == LampState.BUSY && !lit)) p.lampOffBezel else p.lampBezel, CircleShape),
        )
    }
}

/** The line code in its cord colour: "HER" on red. */
@Composable
fun CodeCell(code: String, cord: Int, modifier: Modifier = Modifier, style: TextStyle = TeType.code) {
    val p = Te.colors
    val fill = Cords.color(cord)
    // the white cord vanishes on the paper face without an edge
    val edge = if (!p.dark && cord == 4) Modifier.border(1.dp, p.ruleStrong, CellShape) else Modifier
    Box(
        modifier
            .clip(CellShape)
            .background(fill)
            .then(edge)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Text(code, style = style, color = Cords.ink(cord), maxLines = 1)
    }
}

/** The printed designation strip: cells in condensed caps, divided by hairlines. */
@Composable
fun DesignationStrip(cells: List<String>, modifier: Modifier = Modifier) {
    val p = Te.colors
    Row(
        modifier
            .fillMaxWidth()
            .background(p.strip)
            .height(IntrinsicSize.Min),
    ) {
        cells.forEachIndexed { i, cell ->
            if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(p.stripRule))
            Text(
                cell.uppercase(),
                style = TeType.strip,
                color = p.stripInk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 5.dp)
                    .then(if (i == cells.lastIndex) Modifier.weight(1f, fill = false) else Modifier),
            )
        }
    }
}

/** Engraved section label: condensed tracked caps. */
@Composable
fun Label(text: String, modifier: Modifier = Modifier, color: Color = Te.colors.inkMuted) {
    Text(text.uppercase(), style = TeType.label, color = color, modifier = modifier.semantics { heading() })
}

/** A mounted plate: tonal step plus a 1 dp rule; no shadow. */
@Composable
fun Plate(modifier: Modifier = Modifier, fill: Color = Te.colors.plate, content: @Composable () -> Unit) {
    Box(
        modifier
            .clip(PlateShape)
            .background(fill)
            .border(1.dp, Te.colors.rule, PlateShape),
    ) { content() }
}

/**
 * One line on the board: code cell, lamp, name, state word and the designation strip. The whole
 * key is the target; pressed is an instant tonal step, and [reversed] turns it into the one
 * reversed plate on the screen (the line a recording started on).
 */
@Composable
fun LineKey(
    code: String,
    cord: Int,
    name: String,
    strip: List<String>,
    lamp: LampState,
    word: String?,
    description: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    kicker: String? = null,
    reversed: Boolean = false,
    /** Flash the strip in the cord colour on tap: the recording going down this line. */
    patchOnClick: Boolean = false,
    nameStyle: TextStyle = TeType.keyName,
) {
    val p = Te.colors
    var patching by remember { mutableStateOf(false) }
    val still = reduceMotion()
    LaunchedEffect(patching) {
        if (patching) {
            // relay step: two hard frames, no easing
            delay(90)
            patching = false
        }
    }
    val haptics = LocalHapticFeedback.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val fill = when {
        reversed -> p.strip
        pressed -> p.plateHi
        else -> p.plate
    }
    val ink = if (reversed) p.stripInk else p.ink
    val muted = if (reversed) p.stripInk.copy(alpha = 0.75f) else p.inkMuted
    Column(
        modifier
            .heightIn(min = 136.dp)
            .clip(PlateShape)
            .background(fill)
            .border(1.dp, if (reversed) p.strip else p.rule, PlateShape)
            .combinedClickable(
                interactionSource = source,
                indication = null,
                role = Role.Button,
                onLongClick = onLongClick?.let { { haptics.performHapticFeedback(HapticFeedbackType.LongPress); it() } },
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                    if (patchOnClick && !still) patching = true
                    onClick()
                },
            )
            .semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            CodeCell(code, cord)
            if (kicker != null) {
                Spacer(Modifier.width(8.dp))
                Text(kicker.uppercase(), style = TeType.label, color = muted, maxLines = 1)
            }
            Spacer(Modifier.weight(1f))
            Lamp(lamp)
        }
        Text(
            name,
            style = nameStyle,
            color = ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp),
        )
        if (word != null) {
            Text(
                word,
                style = TeType.smallStrong,
                color = when {
                    reversed -> ink
                    lamp == LampState.FAULT -> p.faultInk
                    lamp == LampState.BUSY -> p.busyInk
                    else -> ink
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 2.dp),
            )
        }
        Spacer(Modifier.weight(1f).heightIn(min = 10.dp))
        if (patching) {
            Box(Modifier.fillMaxWidth().height(26.dp).background(Cords.color(cord)))
        } else {
            DesignationStrip(strip, Modifier.then(if (reversed) Modifier.border(1.dp, p.stripRule) else Modifier))
        }
    }
}

/** Primary key: ink on the face, the one strong action of a plate. */
@Composable
fun KeyButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true) {
    val p = Te.colors
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
        shape = PlateShape,
        colors = ButtonDefaults.buttonColors(containerColor = p.ink, contentColor = p.face),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) { ButtonContent(text, icon) }
}

@Composable
fun OutlineButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true) {
    val p = Te.colors
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
        shape = PlateShape,
        border = BorderStroke(1.dp, p.ruleStrong),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = p.ink),
        contentPadding = PaddingValues(horizontal = 14.dp),
    ) { ButtonContent(text, icon) }
}

@Composable
fun PlainButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, color: Color = Te.colors.ink) {
    TextButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        shape = PlateShape,
        colors = ButtonDefaults.textButtonColors(contentColor = color),
        contentPadding = PaddingValues(horizontal = 12.dp),
    ) { ButtonContent(text, icon) }
}

@Composable
private fun RowScope.ButtonContent(text: String, icon: ImageVector?) {
    if (icon != null) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
    }
    Text(text, style = TeType.button, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** A settings row: title, optional value or description, the whole row tappable (48 dp and up). */
@Composable
fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val p = Te.colors
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = TeType.body, color = if (enabled) p.ink else p.inkMuted)
            if (value != null) Text(value, style = TeType.small, color = p.inkMuted)
        }
        if (trailing != null) trailing()
    }
}

/** Hairline between rows. */
@Composable
fun Rule(modifier: Modifier = Modifier, inset: Dp = 0.dp) {
    Box(modifier.padding(start = inset).fillMaxWidth().height(1.dp).background(Te.colors.rule))
}

/** A notice band: a word of state and what to do, never colour alone. */
@Composable
fun Notice(
    text: String,
    modifier: Modifier = Modifier,
    fault: Boolean = false,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    calm: Boolean = false,
) {
    val p = Te.colors
    // calm: advice or a next step, not something going on: a steady unlit lamp and a plain border
    Row(
        modifier
            .fillMaxWidth()
            .clip(PlateShape)
            .background(p.recess)
            .border(1.dp, if (fault) p.fault else if (calm) p.ruleStrong else p.busy, PlateShape)
            .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Lamp(if (fault) LampState.FAULT else if (calm) LampState.OFF else LampState.BUSY, size = 12.dp)
        Spacer(Modifier.width(6.dp))
        Text(text, style = TeType.small, color = p.ink, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) PlainButton(action, onAction)
    }
}

/** Remembers a toggle-like state seeded once from [initial]. */
@Composable
fun <T> rememberSeeded(initial: () -> T) = remember { mutableStateOf(initial()) }

/** Text used for numbers that change while on screen. */
@Composable
fun Figures(text: String, modifier: Modifier = Modifier, color: Color = Te.colors.inkMuted, style: TextStyle = TeType.figures) {
    Text(text, style = style, color = color, modifier = modifier, maxLines = 1)
}

/** A row of icon-led controls with the same baseline. */
@Composable
fun ControlRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
}
