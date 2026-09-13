package com.hazia.emivita.core.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** The OEM auto-start table (spec §9 #29; HM-36): well-formed, no duplicates,
 *  every package declared in the manifest, and null where nothing resolves. */
@RunWith(RobolectricTestRunner::class)
class AutoStartSettingsTest {
    @Test
    fun `every component is well formed and unique`() {
        val all = AutoStartSettings.COMPONENTS
        assertEquals(all.size, all.toSet().size)
        assertTrue(all.all { it.packageName.contains('.') && it.className.contains('.') })
    }

    @Test
    fun `every package is declared in the manifest queries block`() {
        val manifest = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "app/src/main/AndroidManifest.xml") }.first { it.isFile }.readText()
        for (pkg in AutoStartSettings.COMPONENTS.map { it.packageName }.toSet()) {
            assertTrue(pkg, manifest.contains("<package android:name=\"$pkg\" />"))
        }
    }

    @Test
    fun `no OEM page on a plain device`() {
        assertNull(AutoStartSettings.intentFor(ApplicationProvider.getApplicationContext<Context>()))
    }
}
