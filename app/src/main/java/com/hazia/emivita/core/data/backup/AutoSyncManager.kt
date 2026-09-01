package com.hazia.emivita.core.data.backup

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.hazia.emivita.core.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Arrays

private const val AUTO_SYNC_DEBOUNCE_MILLIS = 12_000L // vs #4's cache at 1s, per spec §9 #9(b)
const val AUTO_SYNC_FILE_NAME = "emivita_autosync.zip"
private const val TAG = "AutoSyncManager"

/**
 * Debounced auto-sync writer (spec §9 #9(b)) -- the same conflated-channel +
 * delay idiom [com.hazia.emivita.core.scheduling.OccurrenceProjectionCache]
 * already established, deliberately not WorkManager (spec §9 item 0
 * housekeeping: this app has one background-work paradigm, not two).
 * [notifyChanged] is called from the same repository methods that already
 * call the projection cache's `invalidate()` -- dose resolution, PRN logging,
 * refill, medicine/reminder edits.
 *
 * A no-op until both a folder and a passphrase exist (folder granted but the
 * user backs out of the passphrase prompt, or the folder was never set up at
 * all) -- every trigger during that window is simply dropped, not queued.
 */
class AutoSyncManager(
    private val appContext: Context,
    private val settingsRepository: SettingsRepository,
    private val backupRepository: BackupRepository,
    private val scope: CoroutineScope,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    private val trigger = Channel<Unit>(capacity = Channel.CONFLATED)

    init {
        scope.launch(Dispatchers.IO) {
            for (unused in trigger) {
                delay(AUTO_SYNC_DEBOUNCE_MILLIS)
                runCatching { writeIfConfigured() }
                    .onFailure { Log.w(TAG, "Auto-sync write failed -- will retry on the next triggering change.", it) }
            }
        }
    }

    fun notifyChanged() {
        trigger.trySend(Unit)
    }

    private suspend fun writeIfConfigured() {
        val folderUriString = settingsRepository.autoSyncFolderUri.first() ?: return
        val password = AutoSyncPassphraseStore.get(appContext) ?: return
        // AutoSyncPassphraseStore hands back a CharArray precisely so it can be
        // wiped, and its own doc comment says the caller should -- this is that
        // caller, and it wasn't. Wiping on every exit path (including the early
        // returns below) is the whole reason that class avoids Strings; leaving
        // the plaintext live for the lifetime of a debounced background write
        // undoes it.
        try {
            writeTo(folderUriString, password)
        } finally {
            Arrays.fill(password, ' ')
        }
    }

    private suspend fun writeTo(folderUriString: String, password: CharArray) {
        val treeDoc = DocumentFile.fromTreeUri(appContext, folderUriString.toUri()) ?: return
        // Permission can be revoked outside the app (storage settings, folder
        // deleted) -- nothing to do until the user re-grants it from the
        // Backup & Sync screen; not treated as an error to surface anywhere,
        // matching the "no user present" framing of this whole write path.
        if (!treeDoc.canWrite()) return

        val json = Json.encodeToString(BackupData.serializer(), backupRepository.exportSnapshot(nowEpochMillis()))
        val tempFile = File.createTempFile("emivita_autosync", ".zip", appContext.cacheDir)
        try {
            BackupZipWriter.write(tempFile, json, password)

            // Written under a staging name first and only swapped in once
            // the copy is confirmed complete -- writing straight into
            // AUTO_SYNC_FILE_NAME (or deleting it up front) would leave zero
            // backup files on disk if createFile/openOutputStream fails
            // partway through (SAF providers can fail mid-write: quota,
            // permission revoked, provider hiccup), which is worse than
            // leaving the previous, still-valid backup in place until the
            // next successful write.
            val stagingName = "$AUTO_SYNC_FILE_NAME.new"
            treeDoc.findFile(stagingName)?.delete()
            val staging = treeDoc.createFile("application/zip", stagingName)
                ?: error("SAF createFile returned null for $stagingName")
            val opened = appContext.contentResolver.openOutputStream(staging.uri)
            if (opened == null) {
                staging.delete()
                error("SAF openOutputStream returned null for $stagingName")
            }
            opened.use { out -> tempFile.inputStream().use { it.copyTo(out) } }

            treeDoc.findFile(AUTO_SYNC_FILE_NAME)?.delete()
            staging.renameTo(AUTO_SYNC_FILE_NAME)
        } finally {
            tempFile.delete()
        }
    }
}
