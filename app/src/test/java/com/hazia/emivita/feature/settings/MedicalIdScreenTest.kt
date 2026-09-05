package com.hazia.emivita.feature.settings

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import com.hazia.emivita.core.data.entity.Profile
import com.hazia.emivita.core.data.repository.MedicineRepository
import com.hazia.emivita.core.data.repository.ProfileRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Medical ID's two judgement calls (spec section 7 R48), both about the name.
 *
 * EmivitaApp creates the single profile silently as "Me" and justified that on
 * the grounds that a profile's name is never displayed. This screen is where
 * that stopped being true, so it must neither present "Me" as a name the user
 * chose nor let a card go out headed with it.
 */
@RunWith(RobolectricTestRunner::class)
class MedicalIdScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(profile: Profile) {
        val profiles = mockk<ProfileRepository>(relaxed = true)
        coEvery { profiles.getById(1L) } returns profile
        val medicines = mockk<MedicineRepository>(relaxed = true)
        coEvery { medicines.observeForProfile(1L) } returns flowOf(emptyList())
        compose.setContent {
            MedicalIdScreen(
                profileId = 1L,
                profileRepository = profiles,
                medicineRepository = medicines,
                onBack = {},
            )
        }
        compose.waitForIdle()
    }

    private fun profile(name: String) =
        Profile(id = 1L, name = name, createdAtEpochMillis = 0L)

    /** The placeholder is an absence, not a name. Showing it would invite the
     *  user to leave it and hand a doctor a card headed "Me". */
    @Test
    fun thePlaceholderNameIsShownAsAnEmptyField() {
        show(profile("Me"))
        compose.onNodeWithText("Me").assertDoesNotExist()
        compose.onNodeWithText("Add a name before exporting", substring = true).assertExists()
    }

    @Test
    fun exportIsRefusedUntilARealNameIsEntered() {
        show(profile("Me"))
        compose.onNodeWithText("Export card (PDF)").assertIsNotEnabled()
        compose.onNodeWithText("Name").performTextInput("Ada Lovelace")
        compose.waitForIdle()
        compose.onNodeWithText("Export card (PDF)").assertIsEnabled()
    }

    @Test
    fun aRealNameLoadsIntoTheFieldAndAllowsExport() {
        show(profile("Ada Lovelace"))
        compose.onNodeWithText("Ada Lovelace").assertExists()
        compose.onNodeWithText("Export card (PDF)").assertIsEnabled()
        compose.onNodeWithText("Add a name before exporting", substring = true).assertDoesNotExist()
    }

    /** The boundary section 9's parking-lot entry fixes: this screen records,
     *  it never interprets. If that ever changes, this text is the first thing
     *  that has to change with it. */
    @Test
    fun theScreenSaysItNeverChecksWhatYouTyped() {
        show(profile("Ada Lovelace"))
        compose.onNodeWithText("never checks any of it", substring = true).assertExists()
    }
}
