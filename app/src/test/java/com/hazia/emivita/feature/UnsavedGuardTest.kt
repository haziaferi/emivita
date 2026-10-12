package com.hazia.emivita.feature

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.hazia.emivita.core.data.entity.Medicine
import com.hazia.emivita.core.data.entity.Profile
import com.hazia.emivita.core.data.entity.StockInfo
import com.hazia.emivita.core.data.repository.MedicineRepository
import com.hazia.emivita.core.data.repository.ProfileRepository
import com.hazia.emivita.core.data.repository.ReminderRepository
import com.hazia.emivita.core.scheduling.OccurrenceProjectionCache
import com.hazia.emivita.core.scheduling.ProjectionState
import com.hazia.emivita.feature.medicines.StockExpirationScreen
import com.hazia.emivita.feature.settings.MedicalIdScreen
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * §7 R84, found on the phone: Stock & Expiration and Medical ID dropped an
 * unsaved edit on Back without asking. Untouched, Back leaves at once; edited,
 * it asks first.
 */
@RunWith(RobolectricTestRunner::class)
class UnsavedGuardTest {

    @get:Rule
    val compose = createComposeRule()

    private var backs = 0

    private fun medicalId() {
        val profiles = mockk<ProfileRepository>(relaxed = true)
        coEvery { profiles.getById(1L) } returns Profile(id = 1L, name = "Ada", createdAtEpochMillis = 0L)
        val medicines = mockk<MedicineRepository>(relaxed = true)
        every { medicines.observeForProfile(1L) } returns flowOf(emptyList())
        compose.setContent { MedicalIdScreen(profileId = 1L, profileRepository = profiles, medicineRepository = medicines, onBack = { backs++ }) }
        compose.waitForIdle()
    }

    private fun stock() {
        val medicines = mockk<MedicineRepository>(relaxed = true)
        coEvery { medicines.getById(1L) } returns
            Medicine(id = 1L, profileId = 1L, name = "M", iconKey = "pill", stockInfo = StockInfo(quantity = 10.0, unit = "tablet"), sortOrder = 0.0, createdAtEpochMillis = 0L)
        val reminders = mockk<ReminderRepository>(relaxed = true)
        every { reminders.observeForMedicine(1L) } returns flowOf(emptyList())
        val cache = mockk<OccurrenceProjectionCache>(relaxed = true)
        every { cache.state } returns MutableStateFlow(ProjectionState())
        compose.setContent {
            StockExpirationScreen(
                medicineId = 1L, medicineRepository = medicines, reminderRepository = reminders,
                doseEventRepository = mockk(relaxed = true), occurrenceProjectionCache = cache, onBack = { backs++ },
            )
        }
        compose.waitForIdle()
    }

    @Test
    fun `Medical ID leaves at once when untouched`() {
        medicalId()
        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, backs)
    }

    @Test
    fun `Medical ID asks before dropping an edit`() {
        medicalId()
        compose.onNodeWithText("Blood type").performTextInput("O+")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Discard changes?").assertExists()
        assertEquals(0, backs)
    }

    @Test
    fun `Stock leaves at once when untouched, the loaded values being the baseline`() {
        stock()
        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, backs)
    }

    @Test
    fun `Stock asks before dropping an edit`() {
        stock()
        compose.onNodeWithText("tablet").performTextInput("s")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Discard changes?").assertExists()
        assertEquals(0, backs)
    }
}
