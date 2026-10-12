package com.hazia.emivita.navigation

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
import com.hazia.emivita.core.data.entity.Profile
import com.hazia.emivita.core.data.settings.SettingsRepository
import com.hazia.emivita.core.di.AppContainer
import com.hazia.emivita.core.di.LocalAppContainer
import com.hazia.emivita.core.di.LocalCurrentProfileId
import com.hazia.emivita.feature.lock.AppLockGate
import com.hazia.emivita.feature.permissions.PermissionSetupSheet
import com.hazia.emivita.feature.permissions.rememberPermissionFixer
import com.hazia.emivita.feature.permissions.rememberPermissionState
import com.hazia.emivita.ui.theme.SavedEmivitaTheme
import kotlinx.coroutines.launch
import com.hazia.emivita.feature.disclaimer.DisclaimerDialog
import com.hazia.emivita.feature.recovery.DatabaseRecoveredDialog
import com.hazia.emivita.core.data.db.RecoveryNotice
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.hazia.emivita.core.data.settings.StartPage
import kotlinx.coroutines.flow.first

/** Name given to the silently auto-created first [Profile] -- never shown
 *  anywhere in the UI (confirmed by search: only the profile's `id`, via
 *  [LocalCurrentProfileId], is ever read outside creation), so any fixed
 *  string is safe here. Matches the single-profile, personal-use framing
 *  (spec §1) -- there's no name to actually ask for.
 *
 *  That premise stopped being universally true with Medical ID (§7 R48),
 *  which prints the profile's name at the top of an emergency card handed to
 *  a clinician. It is still true of every *other* surface, so this stays a
 *  silent default rather than becoming onboarding again -- the deleted
 *  onboarding screen is not coming back to ask for a name that only one
 *  screen needs. MedicalIdScreen holds the other half of the deal: it treats
 *  this exact string as an empty field rather than a chosen name, and refuses
 *  to export a card until a real one is entered. Change this constant and
 *  change PLACEHOLDER_PROFILE_NAME there with it.
 *
 *  Not a string resource, deliberately (§9 #19): this is a sentinel that is
 *  WRITTEN to the database and later RECOGNISED by comparison. Translating it
 *  would make a profile created under one language unrecognisable under
 *  another, and the card exporter would print the placeholder as a name. */
private const val DEFAULT_PROFILE_NAME = "Me"

/**
 * App-level root: resolves the persisted theme, gates on whether a Profile
 * exists yet, and provides [LocalAppContainer] + [LocalCurrentProfileId] for
 * every screen below. Single-profile assumption (spec §9 item 1 follow-up)
 * -- the first Profile row is simply "the" profile; there's no switcher UI.
 * No onboarding UI (removed, spec §9 item 1 follow-up reversal) -- the first
 * Profile row is created silently the moment the list is observed empty,
 * with [DEFAULT_PROFILE_NAME] and whatever [SettingsRepository.palette]'s
 * own default already is (`DAYLIGHT` -- see that Flow's definition), so
 * there's nothing left for onboarding to have asked the user for.
 */
@Composable
fun EmivitaApp(container: AppContainer) {
    // null = still loading the first Flow emission, distinct from "loaded, empty list".
    // produceState (not collectAsState) specifically because the initial value's type
    // (null) differs from the flow's element type (List<Profile>, non-null) --
    // collectAsState's `initial` parameter must match the flow's own type exactly.
    val profilesState = produceState<List<Profile>?>(initialValue = null, container) {
        container.profileRepository.observeAll().collect { value = it }
    }
    val profiles = profilesState.value

    SavedEmivitaTheme(container.settingsRepository) {
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
                    // The disclaimer (§9 #29, MT-21) comes before the lock: it
                    // is the one thing a person must read before the app is
                    // theirs at all. null = not loaded yet, no flash either way.
                    val disclaimerAccepted by produceState<Boolean?>(initialValue = null, container) {
                        container.settingsRepository.disclaimerAccepted.collect { value = it }
                    }
                    // The start page is read once (§9 #29, HM-33): a NavHost must not
                    // be re-rooted while it is showing.
                    val startPage by produceState<StartPage?>(initialValue = null, container) {
                        value = container.settingsRepository.startPage.first()
                    }
                    val scope = rememberCoroutineScope()
                    val page = startPage
                    // The recovery notice (§9 #32): read once the profile list is
                    // here, because that read is what opened the database and wrote
                    // the marker. Before the lock, like the disclaimer -- an app that
                    // silently starts empty is the failure this prevents. Answered
                    // once; "Restore" opens the graph on Data & Backup.
                    val recoveryContext = LocalContext.current
                    var recoveryPending by remember { mutableStateOf(RecoveryNotice.pendingSince(recoveryContext) != null) }
                    var openDataAndBackup by remember { mutableStateOf(false) }
                    when {
                        disclaimerAccepted == null || page == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        disclaimerAccepted == false -> DisclaimerDialog(onAccept = { scope.launch { container.settingsRepository.setDisclaimerAccepted() } })
                        recoveryPending -> DatabaseRecoveredDialog(
                            keyLost = RecoveryNotice.reason(recoveryContext) == RecoveryNotice.Reason.KEY_LOST,
                            onRestore = { RecoveryNotice.dismiss(recoveryContext); openDataAndBackup = true; recoveryPending = false },
                            onContinueEmpty = { RecoveryNotice.dismiss(recoveryContext); recoveryPending = false },
                        )
                        else -> AppLockGate(settingsRepository = container.settingsRepository) {
                            EmivitaNavHost(startPage = page, openDataAndBackupOnStart = openDataAndBackup)
                            PermissionSetupEffect(container.settingsRepository)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The first-launch sheet asking for every grant reminders depend on (§7 R83,
 * the owner's decision; it replaces the single notification request of §9 #12).
 * Shown once, after the disclaimer and the lock, and only when something is
 * missing; later revocations are Today's card.
 */
@Composable
private fun PermissionSetupEffect(settingsRepository: SettingsRepository) {
    val scope = rememberCoroutineScope()
    val shown by settingsRepository.permissionSetupShown.collectAsState(initial = true)
    if (shown) return
    val state = rememberPermissionState()
    val onFix = rememberPermissionFixer(settingsRepository)
    var open by remember { mutableStateOf(state.missing.isNotEmpty()) }
    LaunchedEffect(Unit) { if (!open) settingsRepository.setPermissionSetupShown() }
    if (open) {
        PermissionSetupSheet(state, onFix, onClose = { open = false; scope.launch { settingsRepository.setPermissionSetupShown() } })
    }
}
