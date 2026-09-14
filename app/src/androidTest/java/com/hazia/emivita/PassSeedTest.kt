package com.hazia.emivita

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hazia.emivita.core.data.config.IntervalStartBehavior
import com.hazia.emivita.core.data.config.LowStockTriggerMode
import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.data.config.ReminderType
import com.hazia.emivita.core.data.entity.DoseStatus
import com.hazia.emivita.core.data.entity.Medicine
import com.hazia.emivita.core.data.entity.NotificationPriority
import com.hazia.emivita.core.data.entity.StockInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The data the §9 #34 device pass looks at, written through the real
 * repositories into the owner's own profile under names that start with
 * `androidTest ` -- the one profile the app shows -- and removed by
 * [PassCleanupTest] by that prefix. Both are gated on the instrumentation
 * argument `seedPass=true`, so the ordinary device suite skips them:
 *
 *   ./gradlew connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.hazia.emivita.PassSeedTest \
 *     -Pandroid.testInstrumentationRunnerArguments.seedPass=true
 */
@RunWith(AndroidJUnit4::class)
class PassSeedTest {

    @Test
    fun seedThePass() = runBlocking {
        assumeTrue("run only with -e seedPass true", InstrumentationRegistry.getArguments().getString("seedPass") == "true")
        val c = DeviceTestSupport.container()
        val profileId = DeviceTestSupport.ensureProfileId(c)
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        fun medicine(name: String, stock: Double, unit: String? = "tablet", priority: NotificationPriority = NotificationPriority.STANDARD, strength: String? = null, rx: String? = null, icon: String = "tablet") = Medicine(
            profileId = profileId, name = "androidTest $name", iconKey = icon, stockInfo = StockInfo(quantity = stock, unit = unit),
            sortOrder = 0.0, createdAtEpochMillis = now, notificationPriority = priority, strength = strength, rxNumber = rx,
        )

        // Ibuprofen: the estimated follow-up, the low-stock badge, a tag, notes, history.
        val ibu = c.medicineRepository.create(medicine("Ibuprofen", stock = 12.0, strength = "400 mg", rx = "RX-2026-0914", icon = "pill"))
        val ibuMorning = c.reminderRepository.create(ibu, null, ReminderType.FIXED_TIME, ReminderConfig.FixedTime(8, 0, 1.0), instructions = "With food")
        c.reminderRepository.create(ibu, null, ReminderType.LINKED_FOLLOWING, ReminderConfig.LinkedFollowing(60, 2.0, IntervalStartBehavior.ON_REMINDED), parentReminderId = ibuMorning)
        c.reminderRepository.create(ibu, null, ReminderType.LOW_STOCK_ALERT, ReminderConfig.LowStockAlert(threshold = 20.0, triggerMode = LowStockTriggerMode.DAILY_WHEN_BELOW))
        c.medicineRepository.addTag(ibu, "Morning")
        c.medicineRepository.addNote(ibu, "Pharmacist said take it with a meal")
        c.medicineRepository.addNote(ibu, "Slight stomach ache on day 2")
        // Six past doses for History and the chart: taken, skipped, taken, taken, skipped, taken.
        listOf(DoseStatus.TAKEN, DoseStatus.SKIPPED, DoseStatus.TAKEN, DoseStatus.TAKEN, DoseStatus.SKIPPED, DoseStatus.TAKEN).forEachIndexed { i, status ->
            val at = LocalDate.now(zone).minusDays((6 - i).toLong()).atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
            val id = c.doseEventRepository.materializeIfAbsent(ibuMorning, ibu, at, 1.0) ?: return@forEachIndexed
            c.doseEventRepository.resolve(id, status, resolvedAtEpochMillis = at + 5 * 60_000L)
        }

        // Melatonin: the overnight window across midnight.
        val mel = c.medicineRepository.create(medicine("Melatonin", stock = 30.0, icon = "capsule"))
        c.reminderRepository.create(mel, null, ReminderType.DAILY_WINDOW, ReminderConfig.DailyWindow(240, 1.0, 22, 0, 6, 0, IntervalStartBehavior.ON_REMINDED))

        // Levothyroxine: near run-out, another tag for the group-by-tag headers.
        val levo = c.medicineRepository.create(medicine("Levothyroxine", stock = 2.0, strength = "100 mcg"))
        c.reminderRepository.create(levo, null, ReminderType.FIXED_TIME, ReminderConfig.FixedTime(7, 30, 1.0))
        c.medicineRepository.addTag(levo, "Thyroid")

        // The Alarm tier: fires two minutes from now.
        val soon = LocalDateTime.now(zone).plusMinutes(2)
        val alarm = c.medicineRepository.create(medicine("Alarm test", stock = 5.0, priority = NotificationPriority.ALARM, icon = "drop"))
        c.reminderRepository.create(alarm, null, ReminderType.FIXED_TIME, ReminderConfig.FixedTime(soon.hour, soon.minute, 1.0))

        println("PassSeedTest: seeded; alarm at ${soon.hour}:${"%02d".format(soon.minute)}")
    }
}
