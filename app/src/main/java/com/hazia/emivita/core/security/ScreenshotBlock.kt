package com.hazia.emivita.core.security

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Keeps `FLAG_SECURE` on this activity's window while [block] says so (§7 R133, medTimer E-34): screenshots, screen
 * sharing and the Recents preview show nothing of the app. Every activity that can show a medicine name calls it, so
 * the switch covers the alarm screens too. Off by default, the owner's choice: walks and support captures need it off.
 */
fun ComponentActivity.followScreenshotBlock(block: Flow<Boolean>) {
    lifecycleScope.launch {
        // DataStore re-emits on every write (§7 R70); only a change touches the window.
        block.distinctUntilChanged().collect { on ->
            if (on) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
