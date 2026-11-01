package app.brisa.browser

import android.content.SharedPreferences
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.compose.foundation.text.KeyboardOptions

/** A titled group of settings rows. */
@Composable
fun PrefCategory(@StringRes title: Int, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
        )
        content()
    }
}

/** The basic settings row: title, optional summary, optional leading icon and trailing control. */
@Composable
fun PrefRow(
    title: String,
    summary: String? = null,
    iconRes: Int? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val titleColor = if (enabled) scheme.onSurface else scheme.onSurface.copy(alpha = 0.38f)
    val summaryColor = if (enabled) scheme.onSurfaceVariant else scheme.onSurfaceVariant.copy(alpha = 0.38f)
    ListItem(
        modifier = if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = iconRes?.let { res ->
            { Icon(painterResource(res), contentDescription = null, tint = scheme.onSurfaceVariant) }
        },
        headlineContent = { Text(title, color = titleColor) },
        supportingContent = summary?.takeIf { it.isNotEmpty() }?.let { text ->
            { Text(text, color = summaryColor) }
        },
        trailingContent = trailing,
    )
}

@Composable
fun SwitchRow(
    title: String,
    summary: String?,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    PrefRow(
        title = title,
        summary = summary,
        enabled = enabled,
        onClick = { onCheckedChange(!checked) },
        trailing = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
    )
}

/** A switch stored under [key]. */
@Composable
fun SwitchPreference(
    prefs: SharedPreferences,
    key: String,
    default: Boolean,
    @StringRes title: Int,
    @StringRes summary: Int? = null,
    enabled: Boolean = true,
) {
    var checked by remember(key) { mutableStateOf(prefs.getBoolean(key, default)) }
    SwitchRow(
        title = stringResource(title),
        summary = summary?.let { stringResource(it) },
        checked = checked,
        enabled = enabled,
    ) {
        checked = it
        prefs.edit { putBoolean(key, it) }
    }
}

/** A single-choice setting stored as a string under [key]; the chosen entry is shown as the summary. */
@Composable
fun ListPreference(
    prefs: SharedPreferences,
    key: String,
    default: String,
    @StringRes title: Int,
    entries: List<String>,
    values: List<String>,
    enabled: Boolean = true,
) {
    var value by remember(key) { mutableStateOf(prefs.getString(key, default) ?: default) }
    var open by remember(key) { mutableStateOf(false) }
    val index = values.indexOf(value).takeIf { it >= 0 } ?: values.indexOf(default).coerceAtLeast(0)
    PrefRow(
        title = stringResource(title),
        summary = entries.getOrNull(index),
        enabled = enabled,
        onClick = { open = true },
    )
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(stringResource(title)) },
            text = {
                Column(Modifier.selectableGroup()) {
                    entries.forEachIndexed { i, label ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = i == index,
                                    role = Role.RadioButton,
                                    onClick = {
                                        value = values[i]
                                        prefs.edit { putString(key, values[i]) }
                                        open = false
                                    },
                                )
                                .padding(vertical = 12.dp),
                        ) {
                            RadioButton(selected = i == index, onClick = null)
                            Text(label, Modifier.padding(start = 16.dp))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

/** A free-text setting; empty means "not set". */
@Composable
fun TextPreference(prefs: SharedPreferences, key: String, @StringRes title: Int) {
    var value by remember(key) { mutableStateOf(prefs.getString(key, "").orEmpty()) }
    var open by remember(key) { mutableStateOf(false) }
    var draft by remember(key) { mutableStateOf("") }
    PrefRow(
        title = stringResource(title),
        summary = value.ifEmpty { stringResource(R.string.not_set) },
        onClick = {
            draft = value
            open = true
        },
    )
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(stringResource(title)) },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    value = draft.trim()
                    prefs.edit { putString(key, value) }
                    open = false
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

/** An integer range setting. The value is saved when the thumb is released. */
@Composable
fun SliderPreference(
    prefs: SharedPreferences,
    key: String,
    default: Int,
    min: Int,
    max: Int,
    step: Int,
    @StringRes title: Int,
    @StringRes summary: Int,
) {
    var saved by remember(key) { mutableStateOf(prefs.getInt(key, default)) }
    var dragging by remember(key) { mutableFloatStateOf(saved.toFloat()) }
    PrefRow(title = stringResource(title), summary = stringResource(summary)) {
        Text(
            stringResource(R.string.text_size_value, dragging.toInt()),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Slider(
        value = dragging,
        onValueChange = { dragging = it },
        onValueChangeFinished = {
            saved = dragging.toInt()
            prefs.edit { putInt(key, saved) }
        },
        valueRange = min.toFloat()..max.toFloat(),
        steps = (max - min) / step - 1,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}
