package com.hazia.emivita.ui.form

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import com.hazia.emivita.ui.components.UnsavedQuestion
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hazia.emivita.R
import kotlinx.coroutines.launch

/** The primary tier (§7 R124, C2): the form's Salva. */
internal val PrimaryControlHeight = 48.dp

/** The tertiary tier (§7 R124, C2): a text action such as a form's delete. */
val TertiaryControlHeight = 38.dp

/**
 * The Salva/Annulla foot (§7 R124, C5): the same width, on the right, Salva rightmost and filled,
 * Annulla tonal to its left. It sits on a tonal surface one step above its ground instead of a rule:
 * surfaceContainer under a form, surfaceContainerHigh in a sheet.
 */
@Composable
fun FormFootBar(saveLabel: String, saveEnabled: Boolean, onSave: () -> Unit, onCancel: () -> Unit, inSheet: Boolean = false) {
    val tone = if (inSheet) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer
    Surface(color = tone, modifier = Modifier.fillMaxWidth()) {
        ActionPairLayout(hasDismiss = true, hasTertiary = false, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            DismissButton(stringResource(R.string.common_cancel), enabled = true, onClick = onCancel)
            ConfirmButton(saveLabel, saveEnabled, destructive = false, onClick = onSave)
        }
    }
}

/**
 * A form: its title and content scroll, the foot stays fixed at the bottom, above the navigation
 * bar. [onDelete], on an edit form, is a tertiary text action at the end of the content. [onBack]
 * puts the back arrow of a Settings or stock screen before the title (§7 R125); [inSheet] is a
 * Settings sheet's form, its foot on the sheet's tone.
 */
@Composable
fun FormScaffold(
    title: String,
    saveLabel: String,
    saveEnabled: Boolean,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    deleteLabel: String? = null,
    onDelete: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    inSheet: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    // In a sheet the form takes the height it needs and the sheet grows upward; on a page it fills it.
    Column(if (inSheet) Modifier.fillMaxWidth() else Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f, fill = !inSheet).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
            if (onBack == null) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back)) }
                    Spacer(Modifier.width(4.dp))
                    Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(16.dp))
            content()
            if (onDelete != null && deleteLabel != null) {
                Spacer(Modifier.height(24.dp))
                TextButton(onClick = onDelete, modifier = Modifier.heightIn(min = TertiaryControlHeight)) {
                    Text(deleteLabel, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        FormFootBar(saveLabel, saveEnabled, onSave, onCancel, inSheet = inSheet)
    }
}

/**
 * A form in a bottom sheet: title, content that scrolls, and the same foot anchored at the bottom.
 * The sheet opens fully expanded and grows upward only; leaving with edits asks first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormSheetScaffold(title: String, dirty: Boolean, saveEnabled: Boolean, onSave: () -> Unit, close: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var asking by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = { if (dirty) asking = true else close() }, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(16.dp))
                content()
                Spacer(Modifier.height(16.dp))
            }
            FormFootBar(stringResource(R.string.common_save), saveEnabled, onSave = onSave, onCancel = { if (dirty) asking = true else close() }, inSheet = true)
        }
    }
    if (asking) {
        // Back and the scrim hide the sheet before asking; keeping the edits shows it again.
        UnsavedQuestion(onDiscard = { asking = false; close() }, onKeep = { asking = false; scope.launch { sheetState.show() } })
    }
}

/** A grouped settings row: its title, its current value in one line, a chevron; it opens the group's sheet. */
@Composable
fun FormGroupRow(title: String, summary: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(summary, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
    }
}

/** A switch row: the row is the one toggleable node, its help one line under the title. */
@Composable
fun FormSwitchRow(title: String, help: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = checked, role = Role.Switch, onValueChange = onChange).padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (help != null) {
                Text(help, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}
