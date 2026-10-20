package com.hazia.emivita.core.data.backup

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** §7 R101: the weekly copy's alarm (AlarmManager.set, inexact) is replaced not stacked and reaches a declared receiver. */
@RunWith(RobolectricTestRunner::class)
class WeeklyBackupAlarmTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val alarms = shadowOf(context.getSystemService(AlarmManager::class.java))

    @Test
    fun `arming twice leaves one alarm at the later time, for the weekly receiver`() {
        WeeklyBackupAlarm.arm(context, 1_000_000L)
        WeeklyBackupAlarm.arm(context, 2_000_000L)
        assertEquals(1, alarms.scheduledAlarms.size)
        val alarm = alarms.scheduledAlarms.single()
        assertEquals(2_000_000L, alarm.triggerAtMs)
        assertEquals(AlarmManager.RTC_WAKEUP, alarm.type)
        assertEquals(
            WeeklyBackupReceiver::class.java.name,
            shadowOf(alarm.operation as PendingIntent).savedIntent.component?.className,
        )
    }

    @Test
    fun `the receiver is declared in the manifest and not exported`() {
        val info = context.packageManager.getReceiverInfo(ComponentName(context, WeeklyBackupReceiver::class.java), 0)
        assertFalse(info.exported)
    }
}
