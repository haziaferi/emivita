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

enum class BiometricAvailability { AVAILABLE, NO_HARDWARE, NOT_ENROLLED }

/** Small, near-verbatim port of medTimer's `Biometrics.kt` (spec §5/§9 #11) --
 *  a thin wrapper around [androidx.biometric.BiometricPrompt], adapted to a
 *  suspend function so [com.hazia.emivita.feature.lock.AppLockGate] can await
 *  a result instead of juggling a callback. Strong biometrics only (no device
 *  credential/PIN fallback) -- matches spec §9 #11's scope of "biometric
 *  app-lock," not "app lock with a PIN fallback." */
object BiometricAuthenticator {
    fun availability(context: Context): BiometricAvailability = when (BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG)) {
        BiometricManager.BIOMETRIC_SUCCESS -> BiometricAvailability.AVAILABLE
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricAvailability.NOT_ENROLLED
        // Everything else -- no hardware, hardware busy, security update
        // required, unsupported, status unknown -- resolves to NO_HARDWARE,
        // which AppLockGate fails open on. Deliberate: spec §9 #11 scopes this
        // to biometric-only with no PIN/pattern fallback, so treating a
        // "can't tell right now" code as "stay locked" would strand the user
        // with no way to reach Settings and turn the lock back off. Revisit if
        // a fallback unlock path is ever added.
        else -> BiometricAvailability.NO_HARDWARE
    }

    /** True on success, false on any failure/cancellation -- the caller
     *  (AppLockGate) treats both the same way: stay locked. */
    suspend fun authenticate(activity: FragmentActivity): Boolean = suspendCancellableCoroutine { continuation ->
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (continuation.isActive) continuation.resume(true)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (continuation.isActive) continuation.resume(false)
                }

                override fun onAuthenticationFailed() {
                    // A single failed attempt (wrong finger, etc.) -- the prompt
                    // itself stays open for another try, so this isn't resolved
                    // yet; only onAuthenticationError/Succeeded end the prompt.
                }
            },
        )
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(R.string.biometric_unlock_title))
            .setAllowedAuthenticators(BIOMETRIC_STRONG)
            .setNegativeButtonText(activity.getString(R.string.common_cancel))
            .build()
        continuation.invokeOnCancellation { prompt.cancelAuthentication() }
        prompt.authenticate(promptInfo)
    }
}
