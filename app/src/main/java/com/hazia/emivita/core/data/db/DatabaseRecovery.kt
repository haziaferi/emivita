package com.hazia.emivita.core.data.db

import android.content.Context
import androidx.core.content.edit
import java.io.File

/**
 * What [EmivitaDatabase.open] returns: the database, and whether an
 * unopenable file had to be set aside to get it. The app shows the second
 * case once, before the lock, because it is then running on an empty
 * database and the person deserves to know why (spec §9 #32).
 */
class DatabaseOpen<T>(
    val database: T,
    /** True when [openOrRecover] set an unopenable file aside to get here. */
    val recovered: Boolean,
)

/**
 * Build the database, prove it actually opens, and recover once if it does not.
 *
 * Before this, `EmivitaDatabase.getInstance` handed back a lazily-opened Room
 * instance and nothing caught a failure to open it: a corrupt file, or a
 * migration that throws, surfaced at the first DAO call and took the app
 * down -- on every launch, with no route back. SQLCipher does not delete the
 * file on corruption (its error handler refuses to when a codec is loaded,
 * since a wrong passphrase looks the same), which is better than the
 * framework's default and still leaves the person with an app that cannot
 * start.
 *
 * Recovery here means the unopenable file is *renamed aside*, never deleted,
 * and a fresh empty database is created in its place. The set-aside file is
 * still encrypted with the same Keystore passphrase, so it stays recoverable;
 * Data & Backup lists what exists and is the one place it can be deleted.
 *
 * Generic over the database type so the ordering -- probe, close, set aside,
 * rebuild, re-probe -- is testable without Room or a SQLite file; that
 * ordering is the whole content. [probe] must issue a real query: Room opens
 * lazily, so building proves nothing. A second failure is rethrown rather than
 * looped on: if a freshly created database cannot be opened, the old file was
 * not the problem, and setting another one aside would destroy a working copy
 * for nothing. Lifted from Lunar's DatabaseRecovery (the owner's own), where
 * it was written for the same hole.
 */
fun <T> openOrRecover(
    build: () -> T,
    probe: (T) -> Unit,
    close: (T) -> Unit,
    setAside: () -> Unit,
): DatabaseOpen<T> {
    val first = build()
    val failure = runCatching { probe(first) }.exceptionOrNull()
    if (failure == null) return DatabaseOpen(first, recovered = false)

    // Close before moving the file: an open handle on a database being renamed
    // is how a recovery turns one unreadable file into two.
    runCatching { close(first) }
    setAside()

    val second = build()
    probe(second)
    return DatabaseOpen(second, recovered = true)
}

/**
 * The suffix an unopenable database is renamed with, followed by a timestamp.
 * Not `.bak`: the name should say which failure produced the file, so it is
 * not mistaken for a backup the app makes routinely.
 */
const val UNOPENABLE_SUFFIX = ".unopenable-"

/** The database file's companions, renamed with it under one stamp: a journal
 *  or WAL left beside a fresh file belongs to a different database, which is a
 *  worse failure than the one being recovered from. SQLCipher's default is a
 *  rollback journal; WAL is covered in case the mode ever changes. */
private val COMPANIONS = listOf("", "-journal", "-wal", "-shm")

object DatabaseFiles {
    /** Renames [name] and its companions out of the way, with one stamp for all. */
    fun setAside(context: Context, name: String, nowEpochMillis: Long = System.currentTimeMillis()) {
        val stem = name + UNOPENABLE_SUFFIX + nowEpochMillis
        for (suffix in COMPANIONS) {
            val f = context.getDatabasePath(name + suffix)
            if (f.exists()) f.renameTo(File(f.parentFile, stem + suffix))
        }
    }

    /** Every file a past recovery set aside for [name], main files and companions. */
    fun setAsideFiles(context: Context, name: String): List<File> =
        context.getDatabasePath(name).parentFile?.listFiles().orEmpty()
            .filter { it.name.startsWith(name + UNOPENABLE_SUFFIX) }
            .sortedBy { it.name }

    /** Only the main database files, one per incident, oldest first. */
    fun setAsideIncidents(context: Context, name: String): List<File> =
        setAsideFiles(context, name).filter { f -> COMPANIONS.drop(1).none { f.name.endsWith(it) } }

    fun deleteSetAsideFiles(context: Context, name: String) {
        setAsideFiles(context, name).forEach { it.delete() }
    }
}

/**
 * The "a file was set aside" marker the launch dialog reads (spec §9 #32).
 * A marker on disk, not a process flag: the person may kill the app before
 * answering, and the next launch would open the empty database cleanly and
 * forget to say anything. SharedPreferences, not DataStore, because it is
 * written from inside the database open path, before any repository exists.
 */
object RecoveryNotice {
    private const val PREFS = "emivita_db_recovery"
    private const val KEY_PENDING_SINCE = "pending_since"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun record(context: Context, atEpochMillis: Long) = prefs(context).edit { putLong(KEY_PENDING_SINCE, atEpochMillis) }

    /** When the set-aside happened, or null when there is nothing to say. */
    fun pendingSince(context: Context): Long? = prefs(context).getLong(KEY_PENDING_SINCE, -1L).takeIf { it >= 0L }

    fun dismiss(context: Context) = prefs(context).edit { remove(KEY_PENDING_SINCE) }
}
