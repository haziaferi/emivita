package com.hazia.emivita.core.locale

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale

/** The app's own language (spec §9 #28): the store, the 11-12 wrap, and the
 *  shipped list pinned to the resource folders on disk. */
@RunWith(RobolectricTestRunner::class)
class AppLocaleTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `the shipped list is exactly the values folders with a language qualifier`() {
        val res = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "app/src/main/res") }.first { it.isDirectory }
        val onDisk = res.listFiles()!!.map { it.name }.filter { it.startsWith("values-") && !it.startsWith("values-night") }
            .map { it.removePrefix("values-").replace("-r", "-") }.sorted()
        assertEquals((listOf("en") + onDisk).sorted(), AppLocale.SHIPPED.sorted())
    }

    @Test
    @Config(sdk = [31])
    fun `on Android 12 the store is the truth and wrap puts the language in the context`() {
        assertNull(AppLocale.current(context))
        assertSame("nothing stored: identity", context, AppLocale.wrap(context))
        AppLocale.set(context, "it")
        assertEquals("it", AppLocale.current(context))
        assertTrue(AppLocale.needsRecreate)
        val wrapped = AppLocale.wrap(context)
        assertEquals("it", wrapped.resources.configuration.locales[0].language)
        assertEquals("it", Locale.getDefault().language)
        AppLocale.set(context, null)
        assertNull(AppLocale.current(context))
        Locale.setDefault(Locale.US)
    }

    @Test
    @Config(sdk = [34])
    fun `on Android 14 the framework holds the value and wrap is identity`() {
        assertNull(AppLocale.current(context))
        AppLocale.set(context, "it")
        assertEquals("it", AppLocale.current(context))
        assertEquals("the store is written too", "it", AppLocale.stored(context))
        assertSame(context, AppLocale.wrap(context))
        assertTrue(!AppLocale.needsRecreate)
        AppLocale.set(context, null)
        assertNull(AppLocale.current(context))
    }

    @Test
    fun `the pseudolocales are offered on a debuggable build, after the shipped languages`() {
        // testDebugUnitTest runs the debug variant, which is debuggable
        val list = AppLocale.supported(context)
        assertEquals(AppLocale.SHIPPED + listOf("en-XA", "ar-XB"), list)
    }
}
