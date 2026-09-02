package com.hazia.emivita.core.data.db

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The database is encrypted (SQLCipher), but the *key* protecting it is
 *  never something the user types -- it's a random 256-bit value, generated
 *  once, then Keystore-wrapped at rest. This matches "encrypted local
 *  database" from the agreed feature checklist: at-rest protection against
 *  e.g. a stolen/rooted device reading the raw .db file, without adding a
 *  login screen (that's the separate, optional biometric App Lock feature).
 *
 *  Two deliberate departures from the obvious implementation (see
 *  docs/SPEC.md) -- both keep the
 *  exact same threat model (256-bit SecureRandom key, never user-typed,
 *  Keystore-protected at rest) while removing cost that model doesn't buy
 *  anything for:
 *
 *  1. **Raw Key syntax instead of a passphrase string.** SQLCipher's default
 *     KDF (PBKDF2, 256,000 iterations) exists to stretch a *weak,
 *     human-typed* passphrase so it resists brute force / dictionary
 *     attack. Our key is already 32 bytes of `SecureRandom` output --
 *     stretching it buys no additional security and costs real CPU on
 *     every single cold DB open. Per SQLCipher's own documented guidance
 *     ("Technical Guidance: Using Random Values as SQLCipher Keys",
 *     zetetic.net/blog and discuss.zetetic.net/t/3715), formatting the key
 *     as the Raw Key BLOB literal `x'<64 hex chars>'` makes SQLCipher use
 *     the 256-bit value directly as the cipher key and skip the KDF pass
 *     entirely -- "a substantial performance enhancement when opening
 *     databases," in their words. `SupportOpenHelperFactory` hands whatever
 *     byte[] it's given straight through as the key argument, so this
 *     formatting has to happen on our side -- see [toRawKeySyntax].
 *  2. **Direct AndroidKeyStore wrap instead of `EncryptedSharedPreferences`/
 *     `MasterKey`.** That API pulls in the (already-deprecated,
 *     security-crypto:1.1.0-alpha07+, no official replacement) Tink-based
 *     abstraction just to protect one 32-byte value -- extra APK weight and
 *     an extra crypto-provider init cost at first touch for no capability
 *     we don't get more directly from a Keystore-backed AES/GCM `Cipher`
 *     ourselves. Same guarantee (the wrapping AES key never leaves the
 *     Keystore; only ciphertext touches disk), fewer moving parts, and it
 *     retires the deprecated-dependency liability the original comment
 *     here flagged as a "revisit later" item instead of deferring it.
 */
object PassphraseProvider {
    private const val PREFS_NAME = "emivita_secure_prefs"
    private const val KEY_CIPHERTEXT = "db_key_ciphertext"
    private const val KEY_IV = "db_key_iv"
    private const val KEYSTORE_ALIAS = "emivita_db_wrap_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val RAW_KEY_BYTES = 32

    /** Returns the value to hand to `SupportOpenHelperFactory`: the UTF-8
     *  bytes of a SQLCipher Raw Key BLOB literal (`x'...'`), wrapping a
     *  32-byte SecureRandom value that's generated once and Keystore-wrapped
     *  thereafter. */
    fun getOrCreatePassphrase(context: Context): ByteArray =
        toRawKeySyntax(getOrCreateRawKey(context))

    private fun getOrCreateRawKey(context: Context): ByteArray {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existingCiphertext = prefs.getString(KEY_CIPHERTEXT, null)
        val existingIv = prefs.getString(KEY_IV, null)
        if (existingCiphertext != null && existingIv != null) {
            return decrypt(
                Base64.decode(existingCiphertext, Base64.NO_WRAP),
                Base64.decode(existingIv, Base64.NO_WRAP),
            )
        }

        val newRawKey = ByteArray(RAW_KEY_BYTES).also { SecureRandom().nextBytes(it) }
        val (ciphertext, iv) = encrypt(newRawKey)
        // commit = true, not the default apply(): this key is returned below
        // and immediately used to create emivita.db. If the process died
        // before an async flush landed, the next launch would generate a
        // different key and find a database it could never open -- an
        // unrecoverable brick, since EmivitaDatabase sets no destructive
        // migration fallback. One synchronous write, once, on first launch
        // only. AutoSyncPassphraseStore deliberately stays on apply(), where
        // a lost write costs nothing worse than re-entering a password.
        // See docs/SPEC.md §7 R23.
        prefs.edit(commit = true) {
            putString(KEY_CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            putString(KEY_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
        }
        return newRawKey
    }

    /** `x'2dd29c...'` -- SQLCipher's Raw Key BLOB literal syntax. Must stay
     *  US-ASCII (it's a hex string), and must include the `x'...'` wrapper
     *  or SQLCipher treats the bytes as a passphrase and runs the KDF
     *  anyway, silently defeating the whole point of this class. */
    private fun toRawKeySyntax(rawKey: ByteArray): ByteArray {
        val hex = rawKey.joinToString(separator = "") { "%02x".format(it) }
        return "x'$hex'".toByteArray(Charsets.US_ASCII)
    }

    private fun getOrCreateWrapKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return keyGenerator.generateKey()
    }

    private fun encrypt(plaintext: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateWrapKey())
        return cipher.doFinal(plaintext) to cipher.iv
    }

    private fun decrypt(ciphertext: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateWrapKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }
}
