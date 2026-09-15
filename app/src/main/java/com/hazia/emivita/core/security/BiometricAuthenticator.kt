package com.hazia.emivita.core.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import com.hazia.emivita.R
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL

/** [CREDENTIAL_ONLY]: no usable biometric, but the phone has a PIN, pattern
 *  or password the prompt can fall back to (§9 #29, MK-16). */
enum class BiometricAvailability { AVAILABLE, CREDENTIAL_ONLY, NO_HARDWARE, NOT_ENROLLED }

/** Small, near-verbatim port of medTimer's `Biometrics.kt` (spec §5/§9 #11) --
 *  a thin wrapper around [androidx.biometric.BiometricPrompt], adapted to a
 *  suspend function so [com.hazia.emivita.feature.lock.AppLockGate] can await
 *  a result instead of juggling a callback. Strong biometrics only (no device
 *  credential/PIN fallback) -- matches spec §9 #11's scope of "biometric
 *  app-lock," not "app lock with a PIN fallback." */
object BiometricAuthenticator {
    /** Strong biometrics, or the device credential (§9 #29, MK-16): a phone
     *  with a PIN and no enrolled finger locks with the PIN. */
    const val ALLOWED = BIOMETRIC_STRONG or DEVICE_CREDENTIAL

    fun availability(context: Context): BiometricAvailability {
        val manager = BiometricManager.from(context)
        return availabilityOf(manager.canAuthenticate(BIOMETRIC_STRONG), manager.canAuthenticate(DEVICE_CREDENTIAL))
    }

    /** The rule, pure: a strong biometric wins; otherwise the phone's own
     *  credential; otherwise "not enrolled" when the biometric hardware is
     *  there but empty, and NO_HARDWARE for everything else (busy, security
     *  update required, unknown) -- the gate fails open on those two so a
     *  transient "can't tell" never strands the user outside Settings. */
    fun availabilityOf(strongCode: Int, credentialCode: Int): BiometricAvailability = when {
        strongCode == BiometricManager.BIOMETRIC_SUCCESS -> BiometricAvailability.AVAILABLE
        credentialCode == BiometricManager.BIOMETRIC_SUCCESS -> BiometricAvailability.CREDENTIAL_ONLY
        strongCode == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricAvailability.NOT_ENROLLED
        else -> BiometricAvailability.NO_HARDWARE
    }

    /** True on success, false on any failure/cancellation -- the caller
     *  (AppLockGate) treats both the same way: stay locked. The three
     *  `AppLock` log lines carry no data and are what made §7 R79's
     *  diagnosis possible from adb: a prompt cancelled by the screen going
     *  off (error 10) looks, from the phone in hand, like "no keyboard". */
    suspend fun authenticate(activity: FragmentActivity): Boolean = suspendCancellableCoroutine { continuation ->
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    android.util.Log.i("AppLock", "succeeded type=${result.authenticationType} active=${continuation.isActive}")
                    if (continuation.isActive) continuation.resume(true)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    android.util.Log.i("AppLock", "error $errorCode $errString active=${continuation.isActive}")
                    if (continuation.isActive) continuation.resume(false)
                }

                override fun onAuthenticationFailed() {
                    // A single failed attempt (wrong finger, etc.) -- the prompt
                    // itself stays open for another try, so this isn't resolved
                    // yet; only onAuthenticationError/Succeeded end the prompt.
                }
            },
        )
        // No negative button: the platform forbids one when DEVICE_CREDENTIAL
        // is allowed (the credential path takes its place).
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(R.string.biometric_unlock_title))
            .setAllowedAuthenticators(ALLOWED)
            .build()
        continuation.invokeOnCancellation { android.util.Log.i("AppLock", "cancelled"); prompt.cancelAuthentication() }
        android.util.Log.i("AppLock", "authenticate()")
        prompt.authenticate(promptInfo)
    }
}
