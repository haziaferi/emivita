package com.hazia.emivita.core.di

import androidx.compose.runtime.staticCompositionLocalOf

/** Threads the single composition root into Compose without a DI framework --
 *  consistent with AppContainer's own "plain composition root" choice. Provided
 *  once at the EmivitaApp root (see EmivitaApp.kt); every screen below reads
 *  repositories from here via `LocalAppContainer.current` rather than each
 *  screen needing its own constructor parameters threaded through the NavHost. */
val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("No AppContainer provided -- LocalAppContainer.current read outside EmivitaApp's content")
}

/** The active profile's id. Single-profile assumption (spec §9 item 1
 *  follow-up: "no current/active profile concept anywhere") -- resolved once
 *  in EmivitaApp from the one Profile it auto-creates on first launch, not a
 *  real multi-profile switcher. Revisit if multi-profile support is built. */
val LocalCurrentProfileId = staticCompositionLocalOf<Long> {
    error("No current profile id provided -- LocalCurrentProfileId.current read outside EmivitaApp's content")
}
