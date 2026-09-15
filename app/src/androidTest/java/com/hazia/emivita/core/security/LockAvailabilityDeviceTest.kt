package com.hazia.emivita.core.security

import android.app.KeyguardManager
import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What this phone answers to the lock's questions (§7 R78). The row under
 * Settings said "not enrolled" on a phone with a PIN; this prints every code
 * the rule reads and asserts that a secure keyguard never leaves the lock
 * unavailable.
 */
@RunWith(AndroidJUnit4::class)
class LockAvailabilityDeviceTest {
    @Test
    fun aSecureKeyguardMakesTheLockAvailable() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = BiometricManager.from(context)
        val strong = manager.canAuthenticate(BIOMETRIC_STRONG)
        val weak = manager.canAuthenticate(BIOMETRIC_WEAK)
        val credential = manager.canAuthenticate(DEVICE_CREDENTIAL)
        val both = manager.canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
        val secure = (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure
        val availability = BiometricAuthenticator.availability(context)
        println("LockAvailabilityDeviceTest: strong=$strong weak=$weak credential=$credential strong|credential=$both isDeviceSecure=$secure -> $availability")
        if (secure) {
            assertEquals(
                "a secure keyguard must make the lock available (strong=$strong credential=$credential both=$both)",
                true, availability == BiometricAvailability.AVAILABLE || availability == BiometricAvailability.CREDENTIAL_ONLY,
            )
        }
    }
}
