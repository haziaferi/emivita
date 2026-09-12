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
 * The encrypted database on real hardware (spec §9 #20).
 *
 * Robolectric runs the migration and repository tests against a JVM SQLite,
 * so nothing in the JVM suite ever loads `libsqlcipher.so`, wraps a key
 * through AndroidKeyStore, or writes ciphertext to disk. §7 R39 proved all
 * three by hand once -- "proven by the database opening at all" -- and this
 * is that proof made repeatable.
 *
 * Two assertions, and the second is the one worth having: a database that
 * opens and round-trips could still be plaintext if the SQLCipher factory
 * were silently dropped from the builder. Reading the file's first bytes is
 * the check no repository call can stand in for.
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

        // A plaintext SQLite file begins with a 16-byte magic: the fifteen
        // ASCII characters of "SQLite format 3" followed by a NUL. SQLCipher's
        // first page is ciphertext (the salt), so a correctly encrypted file
        // must NOT start with it.
        //
        // The NUL matters. A first draft of this compared against the text
        // with a trailing space instead, which no file ever starts with, so
        // the assertion below could not have failed on a plaintext database.
        // That is §7.1 rule 5 in one line, and why the sanity check on the
        // magic's own length is here.
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
