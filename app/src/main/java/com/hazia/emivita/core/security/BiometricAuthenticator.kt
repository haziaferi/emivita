package com.hazia.emivita.core.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

enum class BiometricAvailability { AVAILABLE, NO_HARDWARE, NOT_ENROLLED }

/** Small, near-verbatim port of medTimer's `Biometrics.kt` (spec §5/§9 #11) --
 *  a thin wrapper around [androidx.biometric.BiometricPrompt], adapted to a
 *  suspend function so [com.hazia.emivita.feature.lock.AppLockGate] can await
 *  a result instead of juggling a callback. Strong biometrics only (no device
 *  credential/PIN fallback) -- matches spec §9 #11's scope of "biometric
 *  app-lock," not "app lock with a PIN fallback." */
object BiometricAuthenticator {
    // Explicit per-code mapping, not a catch-all `else` -- BiometricManager's
    // other codes (SECURITY_UPDATE_REQUIRED, UNSUPPORTED, STATUS_UNKNOWN) are
    // not "no hardware," they're "can't tell/can't use it right now," and a
    // silent else previously folded them into NO_HARDWARE alongside genuine
    // no-hardware cases without that being a conscious choice. They still
    // resolve to NO_HARDWARE below -- same fail-open outcome as before -- but
    // for a reasoned reason: spec §9 #11 scopes this feature to biometric-only
    // with no PIN/pattern fallback, so treating any of these as "stay locked"
    // would strand the user with literally no way to reach Settings and turn
    // the lock back off. Revisit if a fallback unlock path is ever added.
    fun availability(context: Context): BiometricAvailability = when (BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG)) {
        BiometricManager.BIOMETRIC_SUCCESS -> BiometricAvailability.AVAILABLE
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricAvailability.NOT_ENROLLED
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
        BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE,
        BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED,
        BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED,
        BiometricManager.BIOMETRIC_STATUS_UNKNOWN,
        -> BiometricAvailability.NO_HARDWARE
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
            .setTitle("Unlock Emivita")
            .setAllowedAuthenticators(BIOMETRIC_STRONG)
            .setNegativeButtonText("Cancel")
            .build()
        continuation.invokeOnCancellation { prompt.cancelAuthentication() }
        prompt.authenticate(promptInfo)
    }
}
