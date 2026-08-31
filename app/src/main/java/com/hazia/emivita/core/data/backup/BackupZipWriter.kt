package com.hazia.emivita.core.data.backup

import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.EncryptionMethod
import java.io.ByteArrayInputStream
import java.io.File

private const val ENTRY_NAME = "backup.json"

/** Outcome of [BackupZipWriter.read] -- a plain zip file's header can always
 *  be parsed without a password, but its content entry can't, so "is this
 *  encrypted" and "was the password right" are two separate questions the
 *  restore UI needs to ask in sequence. */
sealed interface BackupZipReadResult {
    data class Success(val json: String) : BackupZipReadResult
    data object PasswordRequired : BackupZipReadResult
    data object WrongPassword : BackupZipReadResult
    data object Corrupt : BackupZipReadResult
}

/** AES-256 password-protected zip read/write (spec §9 #9's shared writer) --
 *  a plain file-in/file-out API on purpose. SAF Uri <-> temp cache File
 *  copying happens at the call site (the export/restore UI, AutoSyncManager)
 *  since zip4j itself needs a real File, not a content:// stream, to do
 *  random-access zip writes. */
object BackupZipWriter {
    /** [password] null = an unencrypted zip (spec §9 #9(a)'s explicit
     *  "Export anyway" path only -- (b)'s auto-sync writer always passes one). */
    fun write(outputFile: File, json: String, password: CharArray?) {
        if (outputFile.exists()) outputFile.delete()
        val zipFile = if (password != null) ZipFile(outputFile, password) else ZipFile(outputFile)
        val params = ZipParameters().apply {
            fileNameInZip = ENTRY_NAME
            if (password != null) {
                isEncryptFiles = true
                encryptionMethod = EncryptionMethod.AES
                aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
            }
        }
        ByteArrayInputStream(json.toByteArray(Charsets.UTF_8)).use { input ->
            zipFile.addStream(input, params)
        }
    }

    /** [password] null on the first call -- if the result is [BackupZipReadResult.PasswordRequired],
     *  the caller prompts and calls again with a non-null password. */
    fun read(inputFile: File, password: CharArray?): BackupZipReadResult {
        return try {
            val zipFile = if (password != null) ZipFile(inputFile, password) else ZipFile(inputFile)
            val header = zipFile.getFileHeader(ENTRY_NAME) ?: return BackupZipReadResult.Corrupt
            if (header.isEncrypted && password == null) return BackupZipReadResult.PasswordRequired
            val bytes = zipFile.getInputStream(header).use { it.readBytes() }
            BackupZipReadResult.Success(String(bytes, Charsets.UTF_8))
        } catch (e: Exception) {
            // zip4j surfaces a wrong AES password as a MAC-check failure during
            // the actual stream read (the header itself parses fine even with
            // no/wrong password) -- there's no separate typed exception for it
            // in this version, so the message is what distinguishes "wrong
            // password" from "not a zip at all" / "truncated file." Caught as
            // the broad Exception, not just zip4j's own ZipException: a
            // genuinely non-zip or truncated input file can surface
            // java.util.zip.ZipException or a plain IOException from deeper in
            // zip4j's internals instead, and either should still resolve to
            // Corrupt rather than crash restore.
            if (password != null && e is ZipException && e.message?.contains("Wrong Password", ignoreCase = true) == true) {
                BackupZipReadResult.WrongPassword
            } else {
                BackupZipReadResult.Corrupt
            }
        }
    }
}
