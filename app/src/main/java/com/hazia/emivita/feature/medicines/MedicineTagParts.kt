package com.hazia.emivita.feature.medicines

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hazia.emivita.R
import com.hazia.emivita.core.data.entity.Tag
import com.hazia.emivita.core.data.repository.MedicineRepository
import com.hazia.emivita.ui.form.DialogActionPair
import com.hazia.emivita.ui.form.FormSheetScaffold
import com.hazia.emivita.ui.form.FormTextField
import com.hazia.emivita.ui.form.GridActionCell
import com.hazia.emivita.ui.form.GridToggleCell
import kotlinx.coroutines.launch

/** What the tags sheet's Salva hands back: the new names by tag id, and the ids deleted. */
internal class TagEdits(val renamed: Map<Long, String>, val deleted: Set<Long>, val namesBefore: Map<Long, String>) {
    /** [names] with each renamed tag's new name and each deleted tag gone, in their order. */
    fun appliedTo(names: List<String>): List<String> {
        val byName = namesBefore.entries.associate { it.value to it.key }
        return names.mapNotNull { n ->
            val id = byName[n] ?: return@mapNotNull n
            if (id in deleted) null else renamed[id] ?: n
        }.distinct()
    }
}

/**
 * The medicine form's tags (§7 R127, C-31 V1): every tag as a toggle cell that keeps its own width
 * and wraps, ragged right (on = this medicine has it), and "Gestisci i tag" as the last cell. The
 * sheet's edits reach the database on its Salva and the form's chips at once.
 */
@Composable
internal fun TagChips(
    repository: MedicineRepository,
    medicineId: Long?,
    shown: List<String>,
    pending: List<String>,
    onPending: (List<String>) -> Unit,
    onEdited: (TagEdits) -> Unit,
) {
    val tags by produceState(initialValue = emptyList<Tag>(), repository) { repository.observeAllTags().collect { value = it } }
    val use by produceState(initialValue = emptyMap<Long, Set<Long>>(), repository) { repository.observeTagUse().collect { value = it } }
    var managing by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val names = (tags.map { it.name } + shown).distinct()
    if (names.isEmpty()) return
    Spacer(Modifier.height(8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        names.forEach { n -> GridToggleCell(n, n in pending) { on -> onPending(if (on) pending + n else pending - n) } }
        if (tags.isNotEmpty()) GridActionCell(stringResource(R.string.add_medicine_manage_tags)) { managing = true }
    }
    if (managing) {
        ManageTagsSheet(
            tags = tags,
            usedElsewhere = { t -> use[t.id].orEmpty().any { it != medicineId } },
            close = { managing = false },
        ) { edits ->
            onEdited(edits)
            scope.launch { repository.applyTagEdits(edits.renamed, edits.deleted) }
        }
    }
}

/**
 * Gestisci i tag (§7 R127, C-31 V1): each tag's name to rename, and its delete, which asks first
 * only for a tag another medicine has (the owner: "Only tags in use"). Everything is a draft its
 * Salva applies; Annulla or Back drops it, asking when edited.
 */
@Composable
internal fun ManageTagsSheet(tags: List<Tag>, usedElsewhere: (Tag) -> Boolean, close: () -> Unit, apply: (TagEdits) -> Unit) {
    val before = remember(tags) { tags.associate { it.id to it.name } }
    // A read-only Map in the state, a HashMap underneath so the saver can bundle it.
    var names by rememberSaveable { mutableStateOf<Map<Long, String>>(HashMap(before)) }
    var gone by rememberSaveable { mutableStateOf(setOf<Long>()) }
    var asking by remember { mutableStateOf<Tag?>(null) }
    val kept = tags.filter { it.id !in gone }
    fun nameOf(t: Tag) = names[t.id] ?: t.name
    val errors = tagNameErrors(kept.associate { it.id to nameOf(it) })
    val renamed = kept.mapNotNull { t -> nameOf(t).trim().takeIf { it != t.name }?.let { t.id to it } }.toMap()
    FormSheetScaffold(
        title = stringResource(R.string.add_medicine_manage_tags),
        dirty = gone.isNotEmpty() || renamed.isNotEmpty(),
        saveEnabled = errors.isEmpty(),
        onSave = { apply(TagEdits(renamed, gone, before)); close() },
        close = close,
    ) {
        kept.forEach { tag ->
            FormTextField(
                nameOf(tag), { v -> names = HashMap(names).apply { put(tag.id, v) } }, stringResource(R.string.common_name),
                error = errors[tag.id]?.let { stringResource(it) }, reserve = true,
                trailingIcon = {
                    IconButton(onClick = { if (usedElsewhere(tag)) asking = tag else gone = gone + tag.id }) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.tag_delete_a11y, tag.name))
                    }
                },
            )
        }
    }
    asking?.let { tag ->
        AlertDialog(
            onDismissRequest = { asking = null },
            title = { Text(stringResource(R.string.tag_delete_title, tag.name)) },
            text = { Text(stringResource(R.string.tag_delete_text)) },
            confirmButton = {
                DialogActionPair(stringResource(R.string.medicine_detail_delete), destructive = true, onDismiss = { asking = null }, onConfirm = {
                    gone = gone + tag.id; asking = null
                })
            },
        )
    }
}

/** A name left blank, or one another kept tag also has (the database keeps names unique), by tag id. */
internal fun tagNameErrors(names: Map<Long, String>): Map<Long, Int> {
    val counts = names.values.groupingBy { it.trim() }.eachCount()
    return names.mapNotNull { (id, n) ->
        when {
            n.isBlank() -> id to R.string.tag_name_blank
            (counts[n.trim()] ?: 0) > 1 -> id to R.string.tag_name_taken
            else -> null
        }
    }.toMap()
}
