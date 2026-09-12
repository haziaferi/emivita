package com.hazia.emivita.core.scheduling

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.hazia.emivita.DeviceTestSupport
import com.hazia.emivita.core.data.config.IntervalStartBehavior
import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.data.config.ReminderType
import com.hazia.emivita.core.data.entity.DoseEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The whole alarm chain, end to end, on the device it ships to (spec §9 #20).
 *
 * reminder row -> SchedulingEngine -> AlarmScheduler -> AlarmManager ->
 * ReminderAlarmReceiver -> DoseEvent materialised -> notification posted.
 *
 * §7 R39 walked this by hand on a OnePlus 9 Pro and it is the thing the JVM
 * suite structurally cannot touch: `setExactAndAllowWhileIdle` under
 * `USE_EXACT_ALARM`, a real PendingIntent, a real broadcast, a real
 * NotificationManager. Every one of those is a stub or a mock on the JVM.
 *
 * Timing is the honest part. With `USE_EXACT_ALARM` held the platform
 * promises an exact firing, and R39 still measured a five-minute delay under
 * battery saver on OxygenOS. So the wait here is generous, and a timeout is
 * reported as what it most likely is -- the OS deferring the alarm -- rather
 * than as a bug in the chain. That is a real result too: it is precisely the
 * condition `BatteryOptimizationCard` exists to warn about.
 */
@RunWith(AndroidJUnit4::class)
class AlarmChainTest {

    // POST_NOTIFICATIONS is a runtime permission only from API 33; on 30-32 a
    // grant request for it would fail, so the rule is built empty there.
    @get:Rule
    val grantNotifications: GrantPermissionRule =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            GrantPermissionRule.grant()
        }

    private val fireDelayMillis = 8_000L
    private val waitForFireMillis = 120_000L

    @Test
    fun aScheduledReminderFiresMaterialisesADoseEventAndPostsANotification() = runBlocking {
        val app = DeviceTestSupport.app()
        val container = DeviceTestSupport.container()
        assumeTrue(
            "notifications are disabled for the app on this device; the chain cannot be " +
                "observed past the receiver. (This is the state R58's card exists to surface.)",
            NotificationManagerCompat.from(app).areNotificationsEnabled(),
        )

        val profileId = DeviceTestSupport.ensureProfileId(container)
        val medicineId = container.medicineRepository.create(
            DeviceTestSupport.testMedicine(profileId, "alarm-chain"),
        )
        var reminderId = -1L
        var event: DoseEvent? = null
        try {
            // A RepeatingInterval anchored a few seconds out: the engine's
            // first occurrence for a future anchor IS the anchor (steps = 0),
            // so this fires at exactly `fireAt`, and a 60-minute interval keeps
            // the second occurrence well outside the test.
            val fireAt = System.currentTimeMillis() + fireDelayMillis
            reminderId = container.reminderRepository.create(
                medicineId = medicineId,
                groupId = null,
                type = ReminderType.REPEATING_INTERVAL,
                config = ReminderConfig.RepeatingInterval(
                    intervalMinutes = 60,
                    dose = 1.0,
                    startBehavior = IntervalStartBehavior.ON_REMINDED,
                    anchorEpochMillis = fireAt,
                ),
            )
            // ReminderRepository.create arms the alarm through the coordinator
            // itself; nothing here schedules by hand, so what is under test is
            // the same path a user's tap takes.

            val deadline = fireAt + waitForFireMillis
            while (System.currentTimeMillis() < deadline && event == null) {
                delay(500)
                event = container.doseEventRepository.findByReminderAndTime(reminderId, fireAt)
            }
            val lateBy = System.currentTimeMillis() - fireAt
            assertNotNull(
                "no DoseEvent materialised within ${waitForFireMillis / 1000}s of the trigger. " +
                    "Either the chain is broken, or the OS deferred the exact alarm -- check " +
                    "battery optimisation for Emivita (§7 R39 measured ~5 min under battery saver).",
                event,
            )
            assertEquals(1.0, event!!.doseAmount, 0.0)
            assertEquals(fireAt, event!!.scheduledEpochMillis)

            val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val posted = nm.activeNotifications.any { it.id == event!!.id.toInt() }
            assertTrue(
                "DoseEvent ${event!!.id} exists but no notification with that id is active -- " +
                    "the receiver materialised the dose and then postIfPermitted dropped it",
                posted,
            )
            // Recorded, not asserted: exactness is the platform's promise, not
            // this app's, and R39 showed an OEM can break it under battery saver.
            println("AlarmChainTest: alarm fired ${lateBy}ms after its trigger time")
        } finally {
            if (reminderId > 0) container.alarmScheduler.cancel(reminderId)
            event?.let { NotificationManagerCompat.from(app).cancel(it.id.toInt()) }
            // Cascade: deleting the medicine removes its reminders and dose events.
            container.medicineRepository.getById(medicineId)?.let { container.medicineRepository.delete(it) }
        }
    }
}
