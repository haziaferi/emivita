package com.hazia.emivita.core.security

import androidx.biometric.BiometricManager
import org.junit.Assert.assertEquals
import org.junit.Test

/** The lock's availability rule (spec §9 #29, MK-16): biometric, else the
 *  phone's credential, else not enrolled, else fail open. */
class BiometricAvailabilityTest {
    private val ok = BiometricManager.BIOMETRIC_SUCCESS
    private val none = BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED
    private val noHw = BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE

    @Test
    fun `a strong biometric wins whatever the credential says`() {
        assertEquals(BiometricAvailability.AVAILABLE, BiometricAuthenticator.availabilityOf(ok, ok))
        assertEquals(BiometricAvailability.AVAILABLE, BiometricAuthenticator.availabilityOf(ok, noHw))
    }

    @Test
    fun `no usable biometric but a phone PIN locks with the PIN`() {
        assertEquals(BiometricAvailability.CREDENTIAL_ONLY, BiometricAuthenticator.availabilityOf(none, ok))
        assertEquals(BiometricAvailability.CREDENTIAL_ONLY, BiometricAuthenticator.availabilityOf(noHw, ok))
    }

    @Test
    fun `nothing to fall back to keeps the old outcomes`() {
        assertEquals(BiometricAvailability.NOT_ENROLLED, BiometricAuthenticator.availabilityOf(none, none))
        assertEquals(BiometricAvailability.NO_HARDWARE, BiometricAuthenticator.availabilityOf(noHw, noHw))
        assertEquals(BiometricAvailability.NO_HARDWARE, BiometricAuthenticator.availabilityOf(BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE, none))
    }
}
