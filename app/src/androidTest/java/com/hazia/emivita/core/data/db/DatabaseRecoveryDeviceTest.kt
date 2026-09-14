package com.hazia.emivita.core.data.db

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hazia.emivita.DeviceTestSupport
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The recovery path on the real SQLCipher factory (spec §9 #32). The JVM
 * suite proves the ordering on the framework's factory, where a keep-file
 * wrapper is needed because the framework deletes a corrupt file before any
 * probe (Lunar's finding). SQLCipher's helper never consults Room's callback
 * and its own error handler keeps the file when a codec is loaded -- read
 * from sqlcipher-android 4.16.0's bytecode, and this is where that reading
 * is checked rather than trusted. Runs on a throwaway database name with the
 * real Keystore passphrase; the owner's file is never touched.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseRecoveryDeviceTest {

    private val name = "androidTest-recovery.db"
    private val app get() = DeviceTestSupport.app()
    private val file: File get() = app.getDatabasePath(name)

    @After
    fun cleanUp() {
        for (suffix in listOf("", "-journal", "-wal", "-shm")) File(file.parentFile, name + suffix).delete()
        DatabaseFiles.deleteSetAsideFiles(app, name)
        RecoveryNotice.dismiss(app)
    }

    @Test
    fun corruptEncryptedFileIsSetAsideIntactAndTheAppOpensEmpty() {
        val garbage = "not a database, and not close\n".repeat(40).toByteArray()
        file.parentFile!!.mkdirs()
        file.writeBytes(garbage)

        val factory = SupportOpenHelperFactory(PassphraseProvider.getOrCreatePassphrase(app))
        val result = EmivitaDatabase.open(app, name, factory)
        try {
            assertTrue("recovery was reported", result.recovered)
            assertEquals(0, runBlocking { result.database.profileDao().count() })
            val incident = DatabaseFiles.setAsideIncidents(app, name).single()
            assertArrayEquals("SQLCipher left the file for the set-aside, byte for byte", garbage, incident.readBytes())
            assertTrue("a fresh encrypted database took its place", file.exists() && !file.readBytes().contentEquals(garbage))
            assertFalse("the fresh file is ciphertext, not a plain SQLite header", String(file.readBytes().copyOf(15), Charsets.US_ASCII).startsWith("SQLite format 3"))
        } finally {
            result.database.close()
        }
    }
}
