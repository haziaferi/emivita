package com.hazia.emivita

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import com.hazia.emivita.navigation.EmivitaApp
import android.content.Context
import com.hazia.emivita.core.locale.AppLocale
import com.hazia.emivita.core.security.followScreenshotBlock

// FragmentActivity, not ComponentActivity -- androidx.biometric.BiometricPrompt
// (spec §9 #11) needs a FragmentActivity/Fragment host to attach its dialog
// fragment to; FragmentActivity is itself a ComponentActivity subtype, so
// enableEdgeToEdge()/installSplashScreen()/setContent still all work unchanged.
class MainActivity : FragmentActivity() {
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLocale.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        // The splash fades out over the app's first frame (§7 R98): it is drawn
        // from a fixed resource before the user's palette and theme are known,
        // so it can match none of them exactly, and a cut would show the step.
        installSplashScreen().setOnExitAnimationListener { splash ->
            splash.view.animate().alpha(0f).setDuration(SPLASH_FADE_MS).withEndAction { splash.remove() }.start()
        }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as EmivitaApplication).container
        container.rescheduleOnLaunch()
        followScreenshotBlock(container.settingsRepository.blockScreenshots)

        setContent {
            EmivitaApp(container)
        }
    }
}

private const val SPLASH_FADE_MS = 200L
