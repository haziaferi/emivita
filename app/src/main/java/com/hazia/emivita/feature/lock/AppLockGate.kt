package com.hazia.emivita.feature.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hazia.emivita.core.data.settings.SettingsRepository
import com.hazia.emivita.core.security.BiometricAuthenticator
import com.hazia.emivita.core.security.BiometricAvailability
import kotlinx.coroutines.launch

/**
 * Gates [content] behind a BiometricPrompt challenge (spec §9 #11) whenever
 * the setting is on -- wraps [com.hazia.emivita.navigation.EmivitaNavHost]
 * in [com.hazia.emivita.navigation.EmivitaApp], below onboarding (nothing to
 * protect before a Profile exists). Re-locks on every ON_STOP (backgrounding),
 * not just cold start, so switching away and back always re-challenges.
 *
 * Fails open, deliberately: if the setting is on but the device has no
 * enrolled biometric (removed after being turned on, or never had one),
 * this doesn't lock the user out with no recourse -- spec §9 #11 scopes this
 * to biometric-only, no PIN/pattern fallback, so "can't challenge" has to
 * mean "don't block," not "permanently inaccessible."
 */
@Composable
fun AppLockGate(settingsRepository: SettingsRepository, content: @Composable () -> Unit) {
    // Nullable, not a Boolean defaulted to false -- "haven't loaded the
    // setting yet" and "loaded, and it's off" must not collapse into the same
    // state. Defaulting to false rendered content() for one or more
    // composition frames on every cold start even when the lock was genuinely
    // on, since DataStore's first real emission hasn't landed yet at that
    // point -- a real fail-open window on a screen that exists specifically
    // to prevent exactly that.
    val lockEnabled by settingsRepository.biometricLockEnabled.collectAsState(initial = null)
    val context = LocalContext.current
    val activity = context as? FragmentActivity

    if (lockEnabled == null) {
        // Loading: render neither the lock screen (would flash-then-vanish
        // for the common "lock is off" case) nor content() (the fail-open
        // bug above) -- a blank frame the same background color as the rest
        // of the app, gone within one recomposition once DataStore resolves.
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {}
        return
    }

    if (lockEnabled == false || activity == null) {
        content()
        return
    }

    var authenticated by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Re-checked on every lock cycle, not cached once via remember(activity)
    // for the Activity's whole lifetime -- canAuthenticate() can legitimately
    // change between cycles (biometrics enrolled/removed, sensor freed up
    // after being transiently busy), and a stale first-launch snapshot would
    // otherwise wrongly keep failing open (or keep gating) for the rest of
    // the session regardless of the device's real current state.
    val availability = remember(activity, authenticated) { BiometricAuthenticator.availability(activity) }
    if (availability != BiometricAvailability.AVAILABLE) {
        content()
        return
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) authenticated = false
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (authenticated) {
        content()
        return
    }

    LaunchedEffect(Unit) {
        authenticated = BiometricAuthenticator.authenticate(activity)
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Filled.Fingerprint, contentDescription = null, modifier = Modifier.height(64.dp))
            Text("Emivita is locked", style = MaterialTheme.typography.titleLarge)
            Box(Modifier.height(16.dp))
            Button(onClick = { scope.launch { authenticated = BiometricAuthenticator.authenticate(activity) } }) {
                Text("Unlock")
            }
        }
    }
}
