package app.thirdear.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.thirdear.R
import app.thirdear.support.SupportAsk

/**
 * The support ask (spec/ask.md, "switchboard" voice): a plate with a 1 dp rule and 6 dp corners,
 * an outlined Support (never the filled key), Not now and Don't ask again as plain text. No cord
 * colours, no amber. Never a modal; it sits under the recordings and takes no focus.
 */
@Composable
fun SupportStrip(modifier: Modifier = Modifier) {
    val visible by SupportAsk.visible.collectAsStateWithLifecycle()
    AnimatedVisibility(visible = visible, enter = fadeIn(tween(160)), exit = ExitTransition.None, modifier = modifier) {
        SupportPlate()
    }
}

@Composable
private fun SupportPlate() {
    val p = Te.colors
    val context = LocalContext.current
    // the checkout's control edge: Strong Rule raised to 3:1 against the plate
    val mark = if (p.dark) Color(0xFF7C7364) else Color(0xFF7A725F)
    val label = stringResource(R.string.te_support_yes)
    Column(
        Modifier
            .fillMaxWidth()
            .background(p.plate, PlateShape)
            .border(1.dp, p.rule, PlateShape)
            .padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 4.dp)
            .semantics {
                liveRegion = LiveRegionMode.Polite
                paneTitle = label
            },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.te_support_ask), style = TeType.body.copy(fontSize = 15.sp, lineHeight = 22.sp), color = p.ink)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = { SupportAsk.support(context, p.dark) },
                modifier = Modifier.heightIn(min = 48.dp),
                shape = PlateShape,
                border = BorderStroke(1.dp, mark),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = p.ink),
                contentPadding = PaddingValues(horizontal = 16.dp),
            ) { Text(label, style = TeType.button) }
            TextButton(
                onClick = { SupportAsk.later() },
                modifier = Modifier.heightIn(min = 48.dp),
                shape = PlateShape,
                colors = ButtonDefaults.textButtonColors(contentColor = p.inkMuted),
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) { Text(stringResource(R.string.te_support_later), style = TeType.button) }
        }
        TextButton(
            onClick = { SupportAsk.never(context) },
            // a small link (spec: 32 px); 40 dp keeps it an easy target
            modifier = Modifier.heightIn(min = 40.dp),
            shape = PlateShape,
            colors = ButtonDefaults.textButtonColors(contentColor = p.inkMuted),
            contentPadding = PaddingValues(horizontal = 0.dp),
        ) { Text(stringResource(R.string.te_support_never), style = TeType.small.copy(textDecoration = TextDecoration.Underline)) }
    }
}
