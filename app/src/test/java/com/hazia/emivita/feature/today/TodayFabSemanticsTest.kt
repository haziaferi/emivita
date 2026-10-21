package com.hazia.emivita.feature.today

import com.hazia.emivita.core.scheduling.receivers.closedAfterCompose
import org.junit.rules.TestRule
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import com.hazia.emivita.core.scheduling.receivers.ReceiverHarness
import com.hazia.emivita.core.scheduling.receivers.TestEmivitaApplication
import com.hazia.emivita.ui.theme.EmivitaTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/**
 * Today's "Log dose" FAB names itself (§7 R81, §9 #34's TalkBack check). On the
 * LE2123 the extended FAB's text never reached the accessibility tree -- the
 * button had no text leaf, while every plain Button did -- so the description
 * on the FAB's own node is what a screen reader gets. Robolectric's semantics
 * tree shows the text either way, so this pins the description, not the defect.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestEmivitaApplication::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class TodayFabSemanticsTest {
    val compose = createComposeRule()

    private val h = ReceiverHarness()

    @get:Rule
    val rules: TestRule = closedAfterCompose(h, compose)


    @Test
    fun `the Log dose FAB carries its label as a description on the clickable node`() {
        val c = h.container
        compose.setContent {
            EmivitaTheme {
                TodayScreen(
                    profileId = h.profileId,
                    medicineRepository = c.medicineRepository,
                    doseEventRepository = c.doseEventRepository,
                    occurrenceProjectionCache = c.occurrenceProjectionCache,
                    settingsRepository = c.settingsRepository,
                    reminderRepository = c.reminderRepository,
                    notificationBuilder = c.notificationBuilder,
                )
            }
        }
        compose.onNode(hasContentDescription("Log dose")).assertHasClickAction()
    }
}
