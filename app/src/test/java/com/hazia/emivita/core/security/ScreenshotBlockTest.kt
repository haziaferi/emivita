package com.hazia.emivita.core.security

import android.view.WindowManager
import androidx.activity.ComponentActivity
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import android.os.Looper

/** The screenshot switch reaches the window both ways (§7 R133): on sets FLAG_SECURE, off clears it. */
@RunWith(RobolectricTestRunner::class)
class ScreenshotBlockTest {

    private fun secure(a: ComponentActivity) = a.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

    @Test fun the_window_follows_the_switch() {
        val block = MutableStateFlow(false)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().get()
        activity.followScreenshotBlock(block)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("off by default: screenshots allowed", false, secure(activity))
        block.value = true
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("on: the window is secure", true, secure(activity))
        block.value = false
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("off again: cleared", false, secure(activity))
    }
}
