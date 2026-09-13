package com.hazia.emivita

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import com.hazia.emivita.navigation.EmivitaApp
import android.content.Context
import com.hazia.emivita.core.locale.AppLocale

// FragmentActivity, not ComponentActivity -- androidx.biometric.BiometricPrompt
// (spec §9 #11) needs a FragmentActivity/Fragment host to attach its dialog
// fragment to; FragmentActivity is itself a ComponentActivity subtype, so
// enableEdgeToEdge()/installSplashScreen()/setContent still all work unchanged.
class MainActivity : FragmentActivity() {
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLocale.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as EmivitaApplication).container
        container.rescheduleOnLaunch()

        setContent {
            EmivitaApp(container)
        }
    }
}
