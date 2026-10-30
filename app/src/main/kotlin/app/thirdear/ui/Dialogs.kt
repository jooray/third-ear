package app.thirdear.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.thirdear.R

/** One choice from a list, applied on tap (as the platform's single-choice dialogs do). */
@Composable
fun <T> ChoiceDialog(title: String, options: List<Pair<T, String>>, selected: T?, onPick: (T) -> Unit, onDismiss: () -> Unit) {
    val p = Te.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = p.plate,
        shape = PlateShape,
        title = { Text(title, style = TeType.title, color = p.ink) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { (value, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .selectable(selected = value == selected, role = Role.RadioButton) { onPick(value); onDismiss() },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = value == selected,
                            onClick = null,
                            colors = RadioButtonDefaults.colors(selectedColor = p.ink, unselectedColor = p.inkMuted),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(label, style = TeType.body, color = p.ink)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { PlainButton(stringResource(android.R.string.cancel), onDismiss) },
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String?,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    neutral: String? = null,
    onNeutral: (() -> Unit)? = null,
) {
    val p = Te.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = p.plate,
        shape = PlateShape,
        title = { Text(title, style = TeType.title, color = p.ink) },
        text = message?.let { { Text(it, style = TeType.body, color = p.ink, modifier = Modifier.verticalScroll(rememberScrollState())) } },
        confirmButton = {
            PlainButton(confirm, { onConfirm(); onDismiss() }, color = if (destructive) p.faultInk else p.ink)
        },
        dismissButton = {
            Row {
                if (neutral != null && onNeutral != null) PlainButton(neutral, { onNeutral(); onDismiss() })
                PlainButton(stringResource(android.R.string.cancel), onDismiss, color = p.inkMuted)
            }
        },
    )
}

/** A text field in a dialog; [validate] returns the problem to show, or null. */
@Composable
fun TextDialog(
    title: String,
    initial: String,
    confirm: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    message: String? = null,
    hint: String? = null,
    secret: Boolean = false,
    singleLine: Boolean = true,
    validate: (String) -> String? = { null },
) {
    val p = Te.colors
    var value by remember { mutableStateOf(initial) }
    val problem = validate(value)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = p.plate,
        shape = PlateShape,
        title = { Text(title, style = TeType.title, color = p.ink) },
        text = {
            Column {
                if (message != null) {
                    Text(message, style = TeType.small, color = p.ink)
                    Spacer(Modifier.padding(top = 12.dp))
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = singleLine,
                    minLines = if (singleLine) 1 else 3,
                    placeholder = hint?.let { { Text(it, style = TeType.body) } },
                    isError = problem != null && value.isNotEmpty(),
                    supportingText = problem?.takeIf { value.isNotEmpty() }?.let { { Text(it, style = TeType.small) } },
                    visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = if (secret) KeyboardOptions(keyboardType = KeyboardType.Password) else KeyboardOptions.Default,
                    textStyle = TeType.body,
                    shape = PlateShape,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            PlainButton(confirm, { if (problem == null) { onConfirm(value); onDismiss() } })
        },
        dismissButton = { PlainButton(stringResource(android.R.string.cancel), onDismiss, color = p.inkMuted) },
    )
}

/** Text to read (a transcript, a key), with Copy. */
@Composable
fun ReadDialog(title: String, text: String, onCopy: () -> Unit, onDismiss: () -> Unit, mono: Boolean = false) {
    val p = Te.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = p.plate,
        shape = PlateShape,
        title = { Text(title, style = TeType.title, color = p.ink) },
        text = {
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(text, style = if (mono) TeType.mono else TeType.body, color = p.ink, modifier = Modifier.verticalScroll(rememberScrollState()))
            }
        },
        confirmButton = { PlainButton(stringResource(R.string.te_wn_copy), { onCopy(); onDismiss() }) },
        dismissButton = { PlainButton(stringResource(R.string.te_close), onDismiss, color = p.inkMuted) },
    )
}

/** A row that reads as a menu entry inside a bottom sheet. */
@Composable
fun SheetItem(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    color: androidx.compose.ui.graphics.Color = Te.colors.ink,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Icon(icon, contentDescription = null, tint = color)
        Spacer(Modifier.width(20.dp))
        Text(text, style = TeType.body, color = color)
    }
}
