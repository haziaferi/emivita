package com.hazia.emivita.core.data.backup

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.hazia.emivita.core.data.db.RecoveryNotice
import com.hazia.emivita.core.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.ZoneId
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
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val armWeekly: (Long) -> Unit = { WeeklyBackupAlarm.arm(appContext, it) },
) {
    // The after-every-change write and the weekly one never overlap in a folder.
    private val folderLock = Mutex()

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
        withFolder { treeDoc, password -> writeTo(treeDoc, password) }
        // A change is a moment the app is running with a working folder: the
        // weekly copy is checked here too, so it starts the week it is set up.
        runWeekly()
    }

    /**
     * The weekly copy (§7 R101), when one is due, then the alarm for the next.
     * Called on launch, after a reboot, from that alarm, and after each
     * after-every-change write. Unconfigured, it writes nothing and arms
     * nothing; the next launch or change after setup starts it.
     */
    suspend fun runWeekly() {
        var configured = false
        runCatching { withFolder { treeDoc, password -> configured = true; writeWeeklyIfDue(treeDoc, password) } }
            .onFailure { Log.w(TAG, "Weekly backup failed -- the alarm retries it.", it) }
        if (configured) armWeekly(WeeklyBackups.nextDue(settingsRepository.lastWeeklyBackupEpochMillis.first(), nowEpochMillis()))
    }

    private suspend fun withFolder(block: suspend (DocumentFile, CharArray) -> Unit) {
        // A recovery replaced the database with an empty one, so a snapshot now
        // would overwrite the only off-device copy with a backup of nothing --
        // destroying the data precisely when it is needed (SPEC §7 R82). The
        // write resumes once the launch dialog's notice is answered.
        if (RecoveryNotice.pendingSince(appContext) != null) return
        val folderUriString = settingsRepository.autoSyncFolderUri.first() ?: return
        val password = AutoSyncPassphraseStore.get(appContext) ?: return
        // AutoSyncPassphraseStore hands back a CharArray precisely so it can be
        // wiped, and its own doc comment says the caller should -- this is that
        // caller, and it wasn't. Wiping on every exit path (including the early
        // returns below) is the whole reason that class avoids Strings; leaving
        // the plaintext live for the lifetime of a debounced background write
        // undoes it.
        try {
            val treeDoc = DocumentFile.fromTreeUri(appContext, folderUriString.toUri()) ?: return
            // Permission can be revoked outside the app (storage settings, folder
            // deleted) -- nothing to do until the user re-grants it from the
            // Backup & Sync screen; not treated as an error to surface anywhere,
            // matching the "no user present" framing of this whole write path.
            if (!treeDoc.canWrite()) return
            folderLock.withLock { block(treeDoc, password) }
        } finally {
            Arrays.fill(password, '\u0000')
        }
    }

    /** A dated file of its own, so nothing is renamed over; staged like the sync file. */
    private suspend fun writeWeeklyIfDue(treeDoc: DocumentFile, password: CharArray) {
        val now = nowEpochMillis()
        if (!WeeklyBackups.isDue(settingsRepository.lastWeeklyBackupEpochMillis.first(), now)) return
        val name = WeeklyBackups.fileName(now, zone())
        val json = BackupJson.encodeToString(BackupData.serializer(), backupRepository.exportSnapshot(now))
        val tempFile = File.createTempFile("emivita_weekly", ".zip", appContext.cacheDir)
        try {
            BackupZipWriter.write(tempFile, json, password)
            val stagingName = "$name.new"
            treeDoc.findFile(stagingName)?.delete()
            val staging = treeDoc.createFile("application/zip", stagingName)
                ?: error("SAF createFile returned null for $stagingName")
            appContext.contentResolver.openOutputStream(staging.uri)
                ?.use { out -> tempFile.inputStream().use { it.copyTo(out) } }
                ?: run {
                    staging.delete()
                    error("SAF openOutputStream returned null for $stagingName")
                }
            // Only today's own name is replaced, and only once the new copy is whole.
            treeDoc.findFile(name)?.delete()
            if (!staging.renameTo(name)) error("SAF renameTo refused $stagingName")
            val names = treeDoc.listFiles().mapNotNull { it.name }
            WeeklyBackups.toPrune(names).forEach { treeDoc.findFile(it)?.delete() }
            settingsRepository.setLastWeeklyBackup(now)
            settingsRepository.setLastBackup(now)
        } finally {
            tempFile.delete()
        }
    }

    private suspend fun writeTo(treeDoc: DocumentFile, password: CharArray) {
        val json = BackupJson.encodeToString(BackupData.serializer(), backupRepository.exportSnapshot(nowEpochMillis()))
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
            // `?.use` rather than a null check then `use`: Kotlin 2.4's lint no
            // longer follows the smart cast and reports the stream as leaked.
            appContext.contentResolver.openOutputStream(staging.uri)
                ?.use { out -> tempFile.inputStream().use { it.copyTo(out) } }
                ?: run {
                    staging.delete()
                    error("SAF openOutputStream returned null for $stagingName")
                }

            // The previous generation is kept until the swap is confirmed.
            // `renameTo` returns false on providers with no renameDocument, and
            // deleting first left the folder holding only the staging name,
            // which nothing reads -- months of healthy-looking screens over
            // zero restorable backups (SPEC §7 R82).
            val keptName = "$AUTO_SYNC_FILE_NAME.prev"
            val previous = treeDoc.findFile(AUTO_SYNC_FILE_NAME)
            treeDoc.findFile(keptName)?.delete()
            if (previous != null && !previous.renameTo(keptName)) {
                error("SAF renameTo refused $AUTO_SYNC_FILE_NAME -- the previous backup is untouched")
            }
            if (!staging.renameTo(AUTO_SYNC_FILE_NAME)) {
                error("SAF renameTo refused $stagingName -- the previous backup is at $keptName")
            }
            treeDoc.findFile(keptName)?.delete()
            settingsRepository.setLastBackup(nowEpochMillis())
        } finally {
            tempFile.delete()
        }
    }
}
