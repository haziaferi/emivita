package com.hazia.emivita.core.notification

import androidx.compose.ui.graphics.toArgb
import com.hazia.emivita.core.data.settings.SettingsRepository
import com.hazia.emivita.ui.theme.DEFAULT_PALETTE
import com.hazia.emivita.ui.theme.tokensFor
import kotlinx.coroutines.flow.first

/** The colour a notification's small icon and actions take: the saved
 *  palette's light-register primary (§7 R76). The shade shows it on its own
 *  ground, so the light register is the one that reads there in both modes. */
suspend fun notificationAccentArgb(settingsRepository: SettingsRepository): Int =
    tokensFor(settingsRepository.palette.first(), dark = false).primary.toArgb()

/** For the two group-summary builders, which are not suspending: the accent
 *  their last posted notification used, the default until one is posted. */
val DEFAULT_NOTIFICATION_ACCENT_ARGB: Int = tokensFor(DEFAULT_PALETTE, dark = false).primary.toArgb()
