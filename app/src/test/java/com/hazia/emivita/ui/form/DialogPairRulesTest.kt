package com.hazia.emivita.ui.form

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import com.hazia.emivita.R
import com.hazia.emivita.core.data.entity.DoseStatus
import com.hazia.emivita.feature.alarm.SnoozePickerDialog
import com.hazia.emivita.feature.alarm.TakeAmountDialog
import com.hazia.emivita.feature.history.ExportRange
import com.hazia.emivita.feature.history.ExportScopeDialog
import com.hazia.emivita.feature.medicines.NotesJournal
import com.hazia.emivita.feature.recovery.DatabaseRecoveredDialog
import com.hazia.emivita.feature.settings.BackupPasswordDialog
import com.hazia.emivita.feature.settings.PurgeConfirmDialog
import com.hazia.emivita.ui.components.ColorPickerDialog
import com.hazia.emivita.ui.components.DoseEditDialog
import com.hazia.emivita.ui.components.MonthYearField
import com.hazia.emivita.core.data.entity.MedicineNote
import com.hazia.emivita.core.data.repository.MedicineRepository
import com.hazia.emivita.ui.theme.EmivitaTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The owner's decision of 2026-10-23 for dialogs, "Yes, same order and look" (§7 R125): every
 * dialog keeps its place on screen and its confirm/dismiss pair follows the Salva/Annulla rule,
 * measured on the drawn pixels by [FormRules.dialogPair], one dialog per test. Since §7 R126
 * ("Shorter labels, side by side") none stacks: every pair is on one line with neither label cut.
 */
class DialogPairRulesTest : FormScreenTestBase() {

    private fun pair(dialog: String, confirm: String, dismiss: String = s(R.string.common_cancel)) {
        waitFor("$dialog's pair") { windows() > 1 && inTop(hasText(confirm) and hasClickAction()) }
        val check = rules.dialogPair(confirm, dismiss)
        println("DIALOG-PAIR $dialog: $check")
        assertTrue("$dialog: $check", check.pass)
    }

    /** [dialog] behind a button, opened by a tap as on the phone, in the app's theme (a dialog needs no container). */
    private fun hostDialog(dialog: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            EmivitaTheme {
            var open by remember { mutableStateOf(false) }
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) { TextButton(onClick = { open = true }) { Text(OPEN) } }
            if (open) dialog()
            }
        }
        waitFor("the opener") { inTop(button(OPEN)) }
        tap(button(OPEN))
        waitFor("the dialog") { windows() > 1 }
    }

    private fun hostPlain(content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent { EmivitaTheme { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) { content() } } }
    }

    @Test fun dose_edit() {
        hostDialog {
            DoseEditDialog(
                medicineName = "Metformina", doseEventId = 1L, originalStatus = DoseStatus.TAKEN, originalDoseAmount = 1.0,
                originalResolvedAtEpochMillis = 0L, scheduledEpochMillis = 0L, doseEventRepository = mockk(relaxed = true), onDismiss = {},
            )
        }
        pair("DoseEditDialog", s(R.string.common_save))
    }

    @Test fun dose_edit_delete() {
        hostDialog {
            DoseEditDialog(
                medicineName = "Metformina", doseEventId = 1L, originalStatus = DoseStatus.TAKEN, originalDoseAmount = 1.0,
                originalResolvedAtEpochMillis = 0L, scheduledEpochMillis = 0L, doseEventRepository = mockk(relaxed = true), onDismiss = {},
            )
        }
        tap(button(s(R.string.dose_edit_delete)))
        reveal(button(s(R.string.dose_edit_delete_confirm)))
        pair("DoseEditDialog delete", s(R.string.dose_edit_delete_confirm))
    }

    @Test fun color_picker() {
        hostDialog { ColorPickerDialog(initialColor = null, onDismiss = {}, onConfirm = {}) }
        pair("ColorPickerDialog", s(R.string.color_picker_use_color))
    }

    @Test fun journal_note_edit() {
        val repository = mockk<MedicineRepository>(relaxed = true)
        every { repository.observeNotes(1L) } returns flowOf(listOf(MedicineNote(id = 1, medicineId = 1, text = "Dopo cena", createdAtEpochMillis = 0L)))
        hostPlain { NotesJournal(medicineId = 1L, medicineRepository = repository) }
        waitFor("the note") { inTop(hasText("Dopo cena") and hasClickAction()) }
        tap(hasText("Dopo cena") and hasClickAction())
        pair("NotesJournal edit", s(R.string.common_save))
    }

    @Test fun backup_password() {
        hostDialog { BackupPasswordDialog(password = "segreta", onPassword = {}, error = null, onDismiss = {}, onUnlock = {}) }
        pair("BackupPasswordDialog", s(R.string.common_unlock))
    }

    @Test fun take_amount() {
        hostDialog { TakeAmountDialog(name = "Metformina", planned = 1.0, unit = "compresse", onTake = {}, onDismiss = {}) }
        pair("TakeAmountDialog", s(R.string.common_take))
    }

    @Test fun snooze_picker() {
        hostDialog { SnoozePickerDialog(choices = listOf(5, 15, 30, 60), onSnooze = {}, onDismiss = {}) }
        typeInto(field(s(R.string.snooze_picker_custom)), "10")
        pair("SnoozePickerDialog", s(R.string.common_snooze))
    }

    @Test fun purge_confirm() {
        hostDialog { PurgeConfirmDialog(onDismiss = {}, onConfirm = {}) }
        tap(toggle and hasText(s(R.string.purge_confirm_check)))
        pair("PurgeConfirmDialog", s(R.string.purge_button))
    }

    @Test fun export_scope() {
        hostDialog { ExportScopeDialog(range = ExportRange.LAST_30_DAYS, onRange = {}, medicines = emptyList(), medicineId = null, onMedicine = {}, onDismiss = {}, onConfirm = {}) }
        pair("ExportScopeDialog", s(R.string.history_export))
    }

    @Test fun unsaved_changes() {
        hostDialog {
            AlertDialog(
                onDismissRequest = {},
                title = { Text(s(R.string.unsaved_title)) },
                confirmButton = { DialogActionPair(s(R.string.unsaved_discard), onConfirm = {}, onDismiss = {}, dismissLabel = s(R.string.unsaved_keep)) },
            )
        }
        // "Scarta" / "Modifica" side by side (§7 R126; R125 stacked "Continua a modificare").
        pair("unsaved changes", s(R.string.unsaved_discard), s(R.string.unsaved_keep))
    }

    @Test fun month_year_picker() {
        hostPlain { MonthYearField(label = s(R.string.stock_expiration_expiration_date), epochDay = null) {} }
        waitFor("the field") { inTop(described(s(R.string.form_pick_date, s(R.string.stock_expiration_expiration_date)))) }
        tap(described(s(R.string.form_pick_date, s(R.string.stock_expiration_expiration_date))))
        pair("MonthYearField", s(R.string.common_ok))
    }

    /** "Ripristina" / "Senza dati" side by side (§7 R126; R125 stacked its two long choices). */
    @Test fun database_recovered() {
        hostDialog { DatabaseRecoveredDialog(keyLost = false, onRestore = {}, onContinueEmpty = {}) }
        pair("DatabaseRecoveredDialog", s(R.string.recovery_restore), s(R.string.recovery_continue_empty))
    }

    private companion object {
        /** The opener's label; it is not a string the app shows. */
        const val OPEN = "open"
    }
}
