package com.hazia.emivita.core.data.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.SQLiteMode
import java.io.File

/**
 * [EmivitaDatabase.open] on real files (spec §9 #32). SQLCipher's native
 * library cannot load on the JVM, so these run the production open path on
 * the framework's open-helper factory -- which is also how Lunar's finding is
 * pinned here as a fact about *that* factory: Room's default callback inherits
 * the framework's `DefaultDatabaseErrorHandler`, which deletes a corrupt file
 * and reopens empty before any probe runs. With it, recovery has nothing to
 * set aside and the data is simply gone; with [KeepFileOnCorruptionFactory]
 * (Lunar's class, test-side here) the file survives to be set aside.
 *
 * Production's SQLCipher factory needs no such wrapper -- it never consults
 * the callback and its own handler keeps the file -- and that is proven on the
 * phone by `DatabaseRecoveryDeviceTest`, not assumed from this file.
 */
@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DatabaseFileTest {

    private val name = "recovery-test.db"
    private val app: Context get() = ApplicationProvider.getApplicationContext()
    private val file: File get() = app.getDatabasePath(name)
    private val opened = mutableListOf<EmivitaDatabase>()

    @After
    fun tearDown() {
        opened.forEach { runCatching { it.close() } }
        RecoveryNotice.dismiss(app)
    }

    private fun open(factory: SupportSQLiteOpenHelper.Factory = KeepFileOnCorruptionFactory()): DatabaseOpen<EmivitaDatabase> =
        EmivitaDatabase.open(app, name, factory).also { opened += it.database }

    private val garbage = "not a database, and not close\n".repeat(40).toByteArray()

    private fun writeGarbage() {
        file.parentFile!!.mkdirs()
        file.writeBytes(garbage)
    }

    @Test
    fun `Lunar's finding, pinned -- the framework's default callback deletes a corrupt file before any probe`() {
        writeGarbage()
        val result = open(FrameworkSQLiteOpenHelperFactory())
        assertFalse("nothing was reported: the framework replaced the file silently", result.recovered)
        assertEquals(emptyList<File>(), DatabaseFiles.setAsideFiles(app, name))
        assertFalse("the bytes are gone", file.readBytes().contentEquals(garbage))
        assertNull(RecoveryNotice.pendingSince(app))
    }

    @Test
    fun `a file that is not SQLite is set aside intact, the app opens empty, and the notice is recorded`() {
        writeGarbage()
        val result = open()
        assertTrue("recovery was reported", result.recovered)
        assertEquals(0, runBlocking { result.database.profileDao().count() })

        val incident = DatabaseFiles.setAsideIncidents(app, name).single()
        assertArrayEquals("renamed, not rewritten or deleted", garbage, incident.readBytes())
        assertTrue("a fresh database took its place", file.exists() && !file.readBytes().contentEquals(garbage))
        assertNotNull(RecoveryNotice.pendingSince(app))

        RecoveryNotice.dismiss(app)
        assertNull(RecoveryNotice.pendingSince(app))
    }

    @Test
    fun `a healthy file reopens without recovery and without touching the set-aside list`() {
        open().database.close()
        val again = open()
        assertFalse(again.recovered)
        assertEquals(emptyList<File>(), DatabaseFiles.setAsideFiles(app, name))
        assertNull(RecoveryNotice.pendingSince(app))
    }

    @Test
    fun `the journal and WAL companions move under the same stamp as the file`() {
        // SQLite discards an invalid hot journal when it opens the main file, so
        // this cannot be shown through an open; the rename is a file operation
        // and is tested as one.
        file.parentFile!!.mkdirs()
        for (suffix in listOf("", "-journal", "-wal", "-shm")) File(file.parentFile, name + suffix).writeText("companion$suffix")
        DatabaseFiles.setAside(app, name, nowEpochMillis = 1_700_000_000_000L)

        val stem = name + UNOPENABLE_SUFFIX + "1700000000000"
        assertEquals(listOf(stem, "$stem-journal", "$stem-shm", "$stem-wal"), DatabaseFiles.setAsideFiles(app, name).map { it.name })
        assertEquals(listOf(stem), DatabaseFiles.setAsideIncidents(app, name).map { it.name })
        assertEquals("companion-wal", File(file.parentFile, "$stem-wal").readText())
        assertFalse(file.exists())
    }

    @Test
    fun `every incident is kept and one delete removes them all`() {
        writeGarbage(); open().database.close()
        Thread.sleep(2) // distinct stamps
        writeGarbage(); open().database.close()
        assertEquals(2, DatabaseFiles.setAsideIncidents(app, name).size)

        DatabaseFiles.deleteSetAsideFiles(app, name)
        assertEquals(emptyList<File>(), DatabaseFiles.setAsideFiles(app, name))
    }
}

/**
 * Lunar's `KeepFileOnCorruptionFactory`: Room's own callback for everything
 * except `onCorruption`, which leaves the file alone so the framework's retry
 * throws and the probe fails. Test-side only in Emivita -- production runs
 * SQLCipher, whose helper never calls this method.
 */
internal class KeepFileOnCorruptionFactory : SupportSQLiteOpenHelper.Factory {
    private val framework = FrameworkSQLiteOpenHelperFactory()

    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper =
        framework.create(
            SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
                .name(configuration.name)
                .callback(KeepFileOnCorruption(configuration.callback))
                .noBackupDirectory(configuration.useNoBackupDirectory)
                .allowDataLossOnRecovery(configuration.allowDataLossOnRecovery)
                .build(),
        )
}

private class KeepFileOnCorruption(private val room: SupportSQLiteOpenHelper.Callback) : SupportSQLiteOpenHelper.Callback(room.version) {
    override fun onConfigure(db: SupportSQLiteDatabase) = room.onConfigure(db)
    override fun onCreate(db: SupportSQLiteDatabase) = room.onCreate(db)
    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = room.onUpgrade(db, oldVersion, newVersion)
    override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = room.onDowngrade(db, oldVersion, newVersion)
    override fun onOpen(db: SupportSQLiteDatabase) = room.onOpen(db)
    override fun onCorruption(db: SupportSQLiteDatabase) = Unit
}
