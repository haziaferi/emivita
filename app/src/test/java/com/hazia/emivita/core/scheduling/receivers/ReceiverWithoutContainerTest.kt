package com.hazia.emivita.core.scheduling.receivers

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.hazia.emivita.core.notification.ACTION_TAKE
import com.hazia.emivita.core.notification.EXTRA_DOSE_EVENT_ID
import com.hazia.emivita.core.scheduling.EXTRA_REMINDER_ID
import com.hazia.emivita.core.scheduling.ReminderActionReceiver
import com.hazia.emivita.core.scheduling.ReminderAlarmReceiver
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Under the stock `android.app.Application` (this file takes no `@Config`,
 * so `robolectric.properties` applies) there is no container. Both receivers
 * say so and drop the intent before `goAsync()` -- they must not throw, and
 * the safe cast in `onReceive` is what keeps that true (spec §9 #30).
 */
@RunWith(RobolectricTestRunner::class)
class ReceiverWithoutContainerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `an alarm delivered to a process without the container is dropped, not thrown`() {
        ReminderAlarmReceiver().onReceive(context, Intent(context, ReminderAlarmReceiver::class.java).putExtra(EXTRA_REMINDER_ID, 1L))
    }

    @Test
    fun `an action delivered to a process without the container is dropped, not thrown`() {
        ReminderActionReceiver().onReceive(context, Intent(context, ReminderActionReceiver::class.java).setAction(ACTION_TAKE).putExtra(EXTRA_DOSE_EVENT_ID, 1L))
    }
}
