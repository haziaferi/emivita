package com.hazia.emivita.core.platform

import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
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

    /** The OnePlus LE2123 on Android 14 (§7 R81): none of the ColorOS or OnePlus
     *  packages exist, the startup list is `com.oplus.battery`'s. */
    @Test
    fun `the oplus battery startup page is found when it is the only one present`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val oplus = ComponentName("com.oplus.battery", "com.oplus.startupapp.view.StartupAppListActivity")
        shadowOf(context.packageManager).addActivityIfNotPresent(oplus)
        assertEquals(oplus, AutoStartSettings.intentFor(context)?.component)
    }

    /** The same page guarded by an OEM signature permission (the LE2123, §7 R81):
     *  it resolves, `startActivity` would throw, so it must not be offered. */
    @Test
    fun `a page that requires a permission this app lacks is skipped`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val oplus = ComponentName("com.oplus.battery", "com.oplus.startupapp.view.StartupAppListActivity")
        val info = ActivityInfo().apply {
            packageName = oplus.packageName
            name = oplus.className
            permission = "oplus.permission.OPLUS_COMPONENT_SAFE"
            applicationInfo = context.applicationInfo
        }
        shadowOf(context.packageManager).addOrUpdateActivity(info)
        assertNotNull("the page resolves", context.packageManager.resolveActivity(android.content.Intent().setComponent(oplus), 0))
        assertNull(AutoStartSettings.intentFor(context))
    }
}
