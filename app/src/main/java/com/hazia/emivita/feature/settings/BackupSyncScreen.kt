package com.hazia.emivita.feature.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.hazia.emivita.core.data.backup.AutoSyncManager
import com.hazia.emivita.core.data.backup.AutoSyncPassphraseStore
import com.hazia.emivita.core.data.backup.BackupData
import com.hazia.emivita.core.data.backup.BackupRepository
import com.hazia.emivita.core.data.backup.BackupZipReadResult
import com.hazia.emivita.core.data.backup.BackupZipWriter
import com.hazia.emivita.core.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Arrays
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import androidx.compose.ui.res.stringResource
import com.hazia.emivita.R
import androidx.compose.ui.platform.LocalResources

private val EXPORT_FILE_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd")

/**
 * Backup & Sync (spec §9 #9) -- (a) manual export/restore and (b) the
 * auto-sync folder, both driven by the same [BackupRepository]/
 * [BackupZipWriter] shared writer, per the spec's "one writer, two triggers"
 * framing. Kept as one screen/file (not split) since the two flows share so
 * much of the same state shape (a folder-or-file picker result, a password,
 * a status line) that splitting them would mostly duplicate that plumbing.
 */
@Composable
fun BackupSyncScreen(
    backupRepository: BackupRepository,
    settingsRepository: SettingsRepository,
    autoSyncManager: AutoSyncManager,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    // LocalResources, not LocalContext, for strings resolved inside a
    // callback: Compose's own lint (LocalContextGetResourceValueCall) flags
    // context.getString() on a captured context because it will not see a
    // configuration change; this one does (§9 #19).
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()

    // ---- Manual export (a) ------------------------------------------------
    var passwordProtectExport by remember { mutableStateOf(true) } // resets to on every screen open (spec)
    var exportPassword by remember { mutableStateOf("") }
    var showUnencryptedConfirm by remember { mutableStateOf(false) }
    var pendingExportPassword by remember { mutableStateOf<CharArray?>(null) }
    var exportStatus by remember { mutableStateOf<String?>(null) }

    val createDocumentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val password = pendingExportPassword
        scope.launch {
            exportStatus = null
            val tempFile = File.createTempFile("emivita_export", ".zip", context.cacheDir)
            try {
                // Serializing the whole DB, AES-256-encrypting it, and copying
                // the result through a SAF stream is all real work; `scope` is
                // rememberCoroutineScope(), which dispatches on Main. Only the
                // status writes below belong there.
                withContext(Dispatchers.IO) {
                    val json = Json.encodeToString(BackupData.serializer(), backupRepository.exportSnapshot(System.currentTimeMillis()))
                    BackupZipWriter.write(tempFile, json, password)
                    // Not `?.use { }`: a null stream made the whole block a
                    // no-op that fell through to "Backup exported." below,
                    // reporting success for a backup that wrote nothing.
                    // attemptRestore and AutoSyncManager both already treat
                    // null as an error. See docs/SPEC.md §7 R23.
                    val out = context.contentResolver.openOutputStream(uri)
                        ?: error("Couldn't open the chosen file for writing")
                    out.use { tempFile.inputStream().use { input -> input.copyTo(it) } }
                }
                exportStatus = resources.getString(R.string.backup_exported)
                exportPassword = "" // cleared after use, matching restorePasswordInput/syncPassphraseInput below
            } catch (e: Exception) {
                exportStatus = resources.getString(R.string.backup_export_failed, e.message ?: resources.getString(R.string.common_unknown_error))
            } finally {
                // Same wipe-after-use discipline AutoSyncPassphraseStore asks
                // for: this array was built from the text field only to hand to
                // zip4j, so don't leave the plaintext sitting in Compose state.
                password?.let { Arrays.fill(it, '\u0000') }
                pendingExportPassword = null
                withContext(Dispatchers.IO) { tempFile.delete() }
            }
        }
    }

    fun launchExport(password: CharArray?) {
        pendingExportPassword = password
        val fileName = "emivita_backup_${Instant.now().atZone(ZoneId.systemDefault()).format(EXPORT_FILE_STAMP)}.zip"
        createDocumentLauncher.launch(fileName)
    }

    // ---- Manual restore (a) ------------------------------------------------
    var restoreStatus by remember { mutableStateOf<String?>(null) }
    var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }
    var showRestorePasswordDialog by remember { mutableStateOf(false) }
    var restorePasswordInput by remember { mutableStateOf("") }
    var restorePasswordError by remember { mutableStateOf<String?>(null) }

    suspend fun attemptRestore(uri: Uri, password: CharArray?) {
        restoreStatus = null
        val tempFile = File.createTempFile("emivita_restore", ".zip", context.cacheDir)
        try {
            // Copying the file in and decrypting/reading the zip are the
            // expensive halves; same Main-dispatcher reasoning as the export
            // path above. A null here means the SAF stream couldn't be opened.
            val readResult = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { input -> tempFile.outputStream().use { input.copyTo(it) } }
                    ?: return@withContext null
                BackupZipWriter.read(tempFile, password)
            } ?: run { restoreStatus = resources.getString(R.string.backup_unreadable_file); return }
            when (readResult) {
                is BackupZipReadResult.Success -> {
                    // restoreSnapshot goes through Room's withTransaction, which
                    // dispatches off Main itself; parsing the snapshot JSON does not.
                    val data = withContext(Dispatchers.IO) {
                        Json.decodeFromString(BackupData.serializer(), readResult.json)
                    }
                    backupRepository.restoreSnapshot(data)
                    showRestorePasswordDialog = false
                    restorePasswordInput = ""
                    restoreStatus = resources.getString(R.string.backup_restore_complete)
                }
                BackupZipReadResult.PasswordRequired -> {
                    pendingRestoreUri = uri
                    restorePasswordError = null
                    showRestorePasswordDialog = true
                }
                BackupZipReadResult.WrongPassword -> {
                    pendingRestoreUri = uri
                    restorePasswordError = resources.getString(R.string.backup_wrong_password)
                    showRestorePasswordDialog = true
                }
                BackupZipReadResult.Corrupt -> {
                    restoreStatus = resources.getString(R.string.backup_invalid_file)
                }
            }
        } catch (e: Exception) {
            restoreStatus = resources.getString(R.string.backup_restore_failed, e.message ?: resources.getString(R.string.common_unknown_error))
        } finally {
            withContext(Dispatchers.IO) { tempFile.delete() }
        }
    }

    val openDocumentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { attemptRestore(uri, null) }
    }

    // ---- Auto-sync folder (b) ----------------------------------------------
    var syncFolderUri by remember { mutableStateOf<String?>(null) }
    var syncFolderLoaded by remember { mutableStateOf(false) }
    var pendingSyncFolderUri by remember { mutableStateOf<String?>(null) }
    var showSyncPassphraseDialog by remember { mutableStateOf(false) }
    var syncPassphraseInput by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        syncFolderUri = settingsRepository.autoSyncFolderUri.first()
        syncFolderLoaded = true
    }

    val openTreeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        pendingSyncFolderUri = uri.toString()
        syncPassphraseInput = ""
        showSyncPassphraseDialog = true
    }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        DetailHeader(stringResource(R.string.backup_sync_backup_sync), onBack)

        Text(stringResource(R.string.backup_sync_manual_backup), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.backup_sync_export_a_full_snapshot_for_a_new_phone),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = passwordProtectExport, onCheckedChange = { passwordProtectExport = it })
            Text(stringResource(R.string.backup_sync_password_protect_this_backup))
        }
        if (passwordProtectExport) {
            OutlinedTextField(
                value = exportPassword,
                onValueChange = { exportPassword = it },
                label = { Text(stringResource(R.string.backup_sync_password)) },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(8.dp))
        Button(
            enabled = !passwordProtectExport || exportPassword.isNotBlank(),
            onClick = {
                if (passwordProtectExport) {
                    launchExport(exportPassword.toCharArray())
                } else {
                    showUnencryptedConfirm = true
                }
            },
        ) { Text(stringResource(R.string.backup_sync_export_backup)) }
        exportStatus?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = MaterialTheme.typography.labelMedium)
        }

        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.backup_sync_restore_from_backup), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.backup_sync_replaces_everything_currently_on_this_device),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { openDocumentLauncher.launch(arrayOf("application/zip", "application/octet-stream")) }) {
            Text(stringResource(R.string.backup_sync_choose_backup_file))
        }
        restoreStatus?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = MaterialTheme.typography.labelMedium)
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(24.dp))

        Text(stringResource(R.string.backup_sync_auto_sync_folder), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.backup_sync_keeps_a_password_protected_zip_current_in_a),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        if (syncFolderLoaded) {
            val folder = syncFolderUri
            if (folder == null) {
                Button(onClick = { openTreeLauncher.launch(null) }) { Text(stringResource(R.string.backup_sync_choose_sync_folder)) }
            } else {
                Text(
                    stringResource(R.string.backup_syncing_to, folder.toUri().lastPathSegment ?: folder.toString()),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = {
                    scope.launch {
                        runCatching {
                            context.contentResolver.releasePersistableUriPermission(
                                folder.toUri(),
                                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                            )
                        }
                        settingsRepository.setAutoSyncFolderUri(null)
                        AutoSyncPassphraseStore.clear(context)
                        syncFolderUri = null
                    }
                }) { Text(stringResource(R.string.backup_sync_stop_syncing)) }
            }
        }
    }

    if (showUnencryptedConfirm) {
        AlertDialog(
            onDismissRequest = { showUnencryptedConfirm = false }, // any dismissal = not exported (spec)
            title = { Text(stringResource(R.string.backup_sync_export_without_a_password)) },
            text = { Text(stringResource(R.string.backup_sync_this_file_will_contain_readable_medication)) },
            confirmButton = {
                TextButton(onClick = {
                    showUnencryptedConfirm = false
                    launchExport(null)
                }) { Text(stringResource(R.string.backup_sync_export_anyway)) }
            },
            dismissButton = {
                TextButton(onClick = { showUnencryptedConfirm = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    if (showRestorePasswordDialog) {
        AlertDialog(
            onDismissRequest = { showRestorePasswordDialog = false },
            title = { Text(stringResource(R.string.backup_sync_password_required)) },
            text = {
                Column {
                    Text(stringResource(R.string.backup_sync_this_backup_is_password_protected))
                    restorePasswordError?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = restorePasswordInput,
                        onValueChange = { restorePasswordInput = it },
                        label = { Text(stringResource(R.string.backup_sync_password)) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = restorePasswordInput.isNotBlank(),
                    onClick = {
                        val uri = pendingRestoreUri ?: return@TextButton
                        val password = restorePasswordInput.toCharArray()
                        scope.launch {
                            try {
                                attemptRestore(uri, password)
                            } finally {
                                Arrays.fill(password, '\u0000')
                            }
                        }
                    },
                ) { Text(stringResource(R.string.common_unlock)) }
            },
            dismissButton = {
                TextButton(onClick = { showRestorePasswordDialog = false; restorePasswordInput = "" }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    if (showSyncPassphraseDialog) {
        AlertDialog(
            onDismissRequest = { }, // no skippable-dismiss: folder is already granted, needs a password to be usable
            title = { Text(stringResource(R.string.backup_sync_set_a_sync_password)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.backup_sync_this_is_the_only_copy_of_this_password_write),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = syncPassphraseInput,
                        onValueChange = { syncPassphraseInput = it },
                        label = { Text(stringResource(R.string.backup_sync_password)) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = syncPassphraseInput.isNotBlank(),
                    onClick = {
                        val folderUri = pendingSyncFolderUri ?: return@TextButton
                        val passphrase = syncPassphraseInput.toCharArray()
                        try {
                            AutoSyncPassphraseStore.save(context, passphrase)
                        } finally {
                            Arrays.fill(passphrase, '\u0000')
                        }
                        scope.launch {
                            settingsRepository.setAutoSyncFolderUri(folderUri)
                            syncFolderUri = folderUri
                            showSyncPassphraseDialog = false
                            syncPassphraseInput = ""
                            autoSyncManager.notifyChanged()
                        }
                    },
                ) { Text(stringResource(R.string.common_save)) }
            },
        )
    }
}
