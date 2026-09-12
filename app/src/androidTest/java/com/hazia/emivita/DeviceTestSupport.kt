package com.hazia.emivita

import androidx.test.core.app.ApplicationProvider
import com.hazia.emivita.core.data.entity.Medicine
import com.hazia.emivita.core.data.entity.StockInfo
import com.hazia.emivita.core.di.AppContainer
import kotlinx.coroutines.flow.first

/**
 * Shared plumbing for the on-device suite (spec §9 #20).
 *
 * Everything here goes through the *real* [AppContainer] of the *real*
 * [EmivitaApplication]: the same SQLCipher database, the same AlarmManager,
 * the same NotificationManager the user's own install uses. That is the whole
 * reason this source set exists. The JVM suite proves the logic; this proves
 * the platform underneath it still does what the logic assumes, on the device
 * generation it ships to.
 *
 * Every test cleans up what it created, because these run against the
 * developer's own installed database, not a scratch one.
 */
object DeviceTestSupport {

    fun app(): EmivitaApplication = ApplicationProvider.getApplicationContext()

    fun container(): AppContainer = app().container

    /** The first profile, or a fresh one if the install is empty. Mirrors
     *  EmivitaApp's own silent-creation path rather than inventing a second. */
    suspend fun ensureProfileId(container: AppContainer): Long {
        val existing = container.profileRepository.observeAll().first()
        return existing.firstOrNull()?.id ?: container.profileRepository.create("Me")
    }

    /** A medicine whose name makes it unmistakable in a real database if a
     *  test ever dies before its cleanup runs. */
    fun testMedicine(profileId: Long, tag: String): Medicine = Medicine(
        profileId = profileId,
        name = "androidTest $tag ${System.nanoTime()}",
        iconKey = "tablet",
        stockInfo = StockInfo(quantity = 10.0, unit = "tablet"),
        sortOrder = 0.0,
        createdAtEpochMillis = System.currentTimeMillis(),
    )
}
