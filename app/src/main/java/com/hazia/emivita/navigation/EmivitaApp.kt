package com.hazia.emivita.navigation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.core.content.ContextCompat
import com.hazia.emivita.core.data.entity.Profile
import com.hazia.emivita.core.data.settings.SettingsRepository
import com.hazia.emivita.core.di.AppContainer
import com.hazia.emivita.core.di.LocalAppContainer
import com.hazia.emivita.core.di.LocalCurrentProfileId
import com.hazia.emivita.feature.lock.AppLockGate
import com.hazia.emivita.ui.theme.EmivitaTheme
import com.hazia.emivita.ui.theme.MedPalette
import com.hazia.emivita.ui.theme.ThemeMode
import kotlinx.coroutines.launch

/** Name given to the silently auto-created first [Profile] -- never shown
 *  anywhere in the UI (confirmed by search: only the profile's `id`, via
 *  [LocalCurrentProfileId], is ever read outside creation), so any fixed
 *  string is safe here. Matches the single-profile, personal-use framing
 *  (spec §1) -- there's no name to actually ask for. */
private const val DEFAULT_PROFILE_NAME = "Me"

/**
 * App-level root: resolves the persisted theme, gates on whether a Profile
 * exists yet, and provides [LocalAppContainer] + [LocalCurrentProfileId] for
 * every screen below. Single-profile assumption (spec §9 item 1 follow-up)
 * -- the first Profile row is simply "the" profile; there's no switcher UI.
 * No onboarding UI (removed, spec §9 item 1 follow-up reversal) -- the first
 * Profile row is created silently the moment the list is observed empty,
 * with [DEFAULT_PROFILE_NAME] and whatever [SettingsRepository.palette]'s
 * own default already is (`GOLDEN_HOUR` -- see that Flow's definition), so
 * there's nothing left for onboarding to have asked the user for.
 */
@Composable
fun EmivitaApp(container: AppContainer) {
    val palette by container.settingsRepository.palette.collectAsState(initial = MedPalette.GOLDEN_HOUR)
    val themeMode by container.settingsRepository.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
    // null = still loading the first Flow emission, distinct from "loaded, empty list".
    // produceState (not collectAsState) specifically because the initial value's type
    // (null) differs from the flow's element type (List<Profile>, non-null) --
    // collectAsState's `initial` parameter must match the flow's own type exactly.
    val profilesState = produceState<List<Profile>?>(initialValue = null, container) {
        container.profileRepository.observeAll().collect { value = it }
    }
    val profiles = profilesState.value

    EmivitaTheme(palette = palette, themeMode = themeMode) {
        CompositionLocalProvider(LocalAppContainer provides container) {
            when {
                profiles == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

                profiles!!.isEmpty() -> {
                    // No onboarding screen -- create the one profile this
                    // single-profile app needs and move on. LaunchedEffect
                    // (not a bare suspend call) so a recomposition triggered
                    // by something else entirely can't re-fire create();
                    // once observeAll() emits the new row this branch stops
                    // matching on its own, same as onboarding's old onDone.
                    LaunchedEffect(Unit) { container.profileRepository.create(name = DEFAULT_PROFILE_NAME) }
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                else -> CompositionLocalProvider(LocalCurrentProfileId provides profiles!!.first().id) {
                    NotificationPermissionEffect(container.settingsRepository)
                    AppLockGate(settingsRepository = container.settingsRepository) {
                        EmivitaNavHost()
                    }
                }
            }
        }
    }
}

/**
 * Requests POST_NOTIFICATIONS once, the first time the main shell renders
 * post-onboarding (API 33+ only -- the permission doesn't exist below that).
 * Without this, [ReminderNotificationBuilder]/[AlertNotificationBuilder]'s
 * existing `checkSelfPermission` guard correctly stops a crash, but nothing
 * anywhere ever triggers the system grant dialog -- on a fresh Android 13+
 * install the permission defaults to denied and every reminder notification
 * silently never posts, for an app whose entire purpose is posting them.
 *
 * Asks exactly once (tracked via [SettingsRepository.notificationPermissionRequested]),
 * whichever way the user answers -- no re-prompt on later app opens and no
 * Settings-page redirect on denial (PERM-023/024: respect the decision,
 * don't pressure it). If the user later wants notifications after denying,
 * they grant it the normal OS way (long-press the app icon > App info).
 */
@Composable
private fun NotificationPermissionEffect(settingsRepository: SettingsRepository) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val alreadyRequested by settingsRepository.notificationPermissionRequested.collectAsState(initial = true)

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        scope.launch { settingsRepository.setNotificationPermissionRequested() }
    }

    LaunchedEffect(alreadyRequested) {
        if (alreadyRequested) return@LaunchedEffect
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            settingsRepository.setNotificationPermissionRequested()
        } else {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
