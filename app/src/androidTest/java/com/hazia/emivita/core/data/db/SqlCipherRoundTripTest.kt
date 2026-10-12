package com.hazia.emivita.core.data.db

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hazia.emivita.DeviceTestSupport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The encrypted database on real hardware (spec §9 #20): the JVM suite never
 * loads `libsqlcipher.so`, wraps a key through AndroidKeyStore or writes
 * ciphertext, so this makes §7 R39's one-off manual proof repeatable. The
 * second test is the one worth having: a database that round-trips could still
 * be plaintext if the SQLCipher factory were dropped from the builder, and only
 * the file's first bytes can tell.
 */
@RunWith(AndroidJUnit4::class)
class SqlCipherRoundTripTest {

    @Test
    fun encryptedDatabaseOpensWritesAndReadsBack() = runBlocking {
        val container = DeviceTestSupport.container()
        val profileId = DeviceTestSupport.ensureProfileId(container)
        val medicine = DeviceTestSupport.testMedicine(profileId, "sqlcipher")

        val id = container.medicineRepository.create(medicine)
        try {
            val back = container.medicineRepository.getById(id)
            assertNotNull("row written through the real SQLCipher path should read back", back)
            assertEquals(medicine.name, back!!.name)
            assertEquals(10.0, back.stockInfo.quantity, 0.0)
        } finally {
            container.medicineRepository.getById(id)?.let { container.medicineRepository.delete(it) }
        }
    }

    @Test
    fun databaseFileOnDiskIsNotPlaintextSqlite() = runBlocking {
        // Touch the database first. AppContainer opens it lazily, and on a
        // fresh install this test can run before anything else has, in which
        // case there is no file to read yet -- which is exactly how the first
        // device run failed, on the `isFile` line, without ever reaching the
        // assertion this test exists for.
        DeviceTestSupport.ensureProfileId(DeviceTestSupport.container())

        // A plaintext SQLite file begins with the 16-byte magic "SQLite format 3"
        // plus a NUL, while SQLCipher's first page is ciphertext (the salt), so
        // an encrypted file must NOT start with it. The NUL matters: a trailing
        // space instead matches no file, so the assertion could never fail
        // (§7.1 rule 5), which is why the magic's own length is checked too.
        val file: File = DeviceTestSupport.app().getDatabasePath("emivita.db")
        assertTrue("database file should exist after the app has opened it: $file", file.isFile)

        val head = ByteArray(16)
        val read = file.inputStream().use { it.read(head) }
        assertEquals(16, read)

        val plaintextMagic = "SQLite format 3".toByteArray(Charsets.US_ASCII) + byteArrayOf(0)
        assertEquals("sanity: the magic itself is 16 bytes", 16, plaintextMagic.size)
        assertFalse(
            "emivita.db begins with the plaintext SQLite header -- SQLCipher is not " +
                "encrypting it, which is the at-rest guarantee §1 and §2 are built on",
            head.contentEquals(plaintextMagic),
        )
    }
}
