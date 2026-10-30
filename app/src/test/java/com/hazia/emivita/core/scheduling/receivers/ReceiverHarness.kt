package com.hazia.emivita.core.scheduling.receivers

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import androidx.test.core.app.ApplicationProvider
import com.hazia.emivita.EmivitaApplication
import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.data.config.ReminderType
import com.hazia.emivita.core.data.entity.DoseEvent
import com.hazia.emivita.core.data.entity.Medicine
import com.hazia.emivita.core.data.entity.RepeatConfig
import com.hazia.emivita.core.data.entity.StockInfo
import com.hazia.emivita.core.di.AppContainer
import com.hazia.emivita.core.notification.EXTRA_DOSE_EVENT_ID
import com.hazia.emivita.core.scheduling.EXTRA_IS_NAG
import com.hazia.emivita.core.scheduling.EXTRA_IS_PRE_ALARM
import com.hazia.emivita.core.scheduling.EXTRA_REMINDER_ID
import com.hazia.emivita.core.scheduling.EXTRA_TRIGGER_AT_EPOCH_MILLIS
import com.hazia.emivita.core.scheduling.ReminderActionReceiver
import kotlinx.coroutines.runBlocking
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowLooper
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.util.concurrent.TimeUnit

/**
 * Drives the receivers the way AlarmManager and the notification shade do:
 * an armed alarm is fired by broadcasting its own saved intent, an action by
 * broadcasting what the notification's button would. The database is the
 * container's in-memory Room; the alarms are what `ShadowAlarmManager`
 * recorded; the notifications are what `ShadowNotificationManager` holds.
 */
class ReceiverHarness {
    val context: Context = ApplicationProvider.getApplicationContext()
    val container: AppContainer = (context.applicationContext as EmivitaApplication).container
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        ShadowLog.stream = System.out // the receivers explain themselves through Log; a failure should show why
        ShadowAlarmManager.setCanScheduleExactAlarms(true) // the phone's granted state (§9 #29 permissions row)
        shadowOf(context.applicationContext as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    val profileId: Long = runBlocking { container.profileRepository.create("Me") }

    /** Every test's @After: the container's loops must not outlive the test
     *  (native SQLite on a torn-down connection is a JVM crash), and the clock
     *  switch AppContainer installs goes back (Robolectric answers 12-hour). */
    fun close() {
        container.shutdown()
        com.hazia.emivita.core.format.LocaleFormats.systemHour24 = { null }
    }

    fun medicine(name: String = "Ibuprofen", stock: Double = 10.0, unit: String? = "tablet", locked: Boolean? = null, color: String? = null): Long = runBlocking {
        container.medicineRepository.create(
            Medicine(
                profileId = profileId, name = name, iconKey = "pill", sortOrder = 0.0, color = color,
                stockInfo = StockInfo(quantity = stock, unit = unit),
                remindersLockedUntilTaken = locked,
                createdAtEpochMillis = 0L,
            ),
        )
    }

    fun reminder(
        medicineId: Long,
        config: ReminderConfig,
        repeat: RepeatConfig? = null,
        preAlarmMinutes: Int? = null,
        sticky: Boolean = false,
        variableAmount: Boolean = false,
    ): Long = runBlocking {
        container.reminderRepository.create(
            medicineId = medicineId, groupId = null, type = typeOf(config), config = config,
            repeatOnUnanswered = repeat, preAlarmMinutes = preAlarmMinutes,
            stickyUntilAnswered = sticky, variableAmount = variableAmount,
        )
    }

    private fun typeOf(config: ReminderConfig) = when (config) {
        is ReminderConfig.FixedTime -> ReminderType.FIXED_TIME
        is ReminderConfig.RepeatingInterval -> ReminderType.REPEATING_INTERVAL
        is ReminderConfig.DailyWindow -> ReminderType.DAILY_WINDOW
        is ReminderConfig.LinkedFollowing -> ReminderType.LINKED_FOLLOWING
        is ReminderConfig.LowStockAlert -> ReminderType.LOW_STOCK_ALERT
        is ReminderConfig.ExpirationAlert -> ReminderType.EXPIRATION_ALERT
    }

    fun doseEvents(reminderId: Long): List<DoseEvent> = runBlocking {
        container.doseEventRepository.getInRangeOnce(profileId, Long.MIN_VALUE, Long.MAX_VALUE, null)
            .map { it.doseEvent }.filter { it.reminderId == reminderId }
    }

    fun doseEvent(id: Long): DoseEvent = runBlocking { container.doseEventRepository.getById(id)!! }
    fun medicineRow(id: Long): Medicine = runBlocking { container.medicineRepository.getById(id)!! }

    // ---- alarms -------------------------------------------------------------

    val alarms: List<ShadowAlarmManager.ScheduledAlarm> get() = shadowOf(alarmManager).scheduledAlarms

    private fun intentOf(alarm: ShadowAlarmManager.ScheduledAlarm): Intent = shadowOf(alarm.operation as PendingIntent).savedIntent

    fun reminderIdOf(alarm: ShadowAlarmManager.ScheduledAlarm): Long = intentOf(alarm).getLongExtra(EXTRA_REMINDER_ID, -1L)
    fun isNag(alarm: ShadowAlarmManager.ScheduledAlarm): Boolean = intentOf(alarm).getBooleanExtra(EXTRA_IS_NAG, false)
    fun isPreAlarm(alarm: ShadowAlarmManager.ScheduledAlarm): Boolean = intentOf(alarm).getBooleanExtra(EXTRA_IS_PRE_ALARM, false)
    fun scheduledAtOf(alarm: ShadowAlarmManager.ScheduledAlarm): Long = intentOf(alarm).getLongExtra(EXTRA_TRIGGER_AT_EPOCH_MILLIS, -1L)

    /** The regular slot: its trigger *is* the slot it carries. A snooze
     *  re-fires an existing dose's slot at a later instant, so its trigger and
     *  its carried slot differ -- that is how the two are told apart here,
     *  without reaching into AlarmScheduler's request-code arithmetic. */
    fun regularAlarms(reminderId: Long) = alarms.filter { reminderIdOf(it) == reminderId && !isNag(it) && !isPreAlarm(it) && it.triggerAtMs == scheduledAtOf(it) }
    fun snoozeAlarms(reminderId: Long) = alarms.filter { reminderIdOf(it) == reminderId && !isNag(it) && !isPreAlarm(it) && it.triggerAtMs != scheduledAtOf(it) }
    fun nagAlarms(reminderId: Long) = alarms.filter { reminderIdOf(it) == reminderId && isNag(it) }
    fun preAlarms(reminderId: Long) = alarms.filter { reminderIdOf(it) == reminderId && isPreAlarm(it) }

    /** Deliver an armed alarm exactly as AlarmManager would: its saved intent,
     *  to its component. Returns the slot the intent carried, read *before*
     *  delivery: the PendingIntent is one shared object, and when the receiver
     *  re-arms the slot with FLAG_UPDATE_CURRENT its saved intent is rewritten
     *  in place -- Robolectric models that as the platform does. The shadow
     *  keeps the alarm in its list where the real AlarmManager would have
     *  consumed it (and `scheduledAlarms` is a copy, so it cannot be edited),
     *  so the alarm is cancelled through the manager before the receiver runs. */
    fun fire(alarm: ShadowAlarmManager.ScheduledAlarm): Long {
        val intent = Intent(intentOf(alarm))
        alarmManager.cancel(alarm.operation as PendingIntent)
        broadcast(intent)
        return intent.getLongExtra(EXTRA_TRIGGER_AT_EPOCH_MILLIS, -1L)
    }

    /** An alarm's intent captured now, to be delivered later by [deliver] --
     *  for the case where something between arming and firing (a refill, an
     *  edit) has since cancelled the alarm. AlarmManager can still deliver
     *  such an alarm; a cancel that races a delivery is exactly the real case. */
    fun capture(alarm: ShadowAlarmManager.ScheduledAlarm): Intent = Intent(intentOf(alarm))
    fun deliver(intent: Intent) = broadcast(intent)

    /** The same alarm delivered again: a duplicate delivery. */
    fun fireAgain(alarm: ShadowAlarmManager.ScheduledAlarm): Long {
        val intent = Intent(intentOf(alarm))
        broadcast(intent)
        return intent.getLongExtra(EXTRA_TRIGGER_AT_EPOCH_MILLIS, -1L)
    }

    // ---- notification actions ----------------------------------------------

    fun action(action: String, doseEventId: Long) = broadcast(
        Intent(context, ReminderActionReceiver::class.java).setAction(action).putExtra(EXTRA_DOSE_EVENT_ID, doseEventId),
    )

    /** Robolectric routes `sendBroadcast` by intent filter and these receivers
     *  are explicit-component only, so the receiver is instantiated the way
     *  the framework would and given a pending result for its `goAsync()`.
     *  The work runs on the receiver's own Dispatchers.IO, exactly as on the
     *  phone; this returns when the receiver calls `finish()`, which is the
     *  one signal that means "done" (its `finally` block), not a guess about
     *  which table to poll. A receiver that drops the intent before
     *  `goAsync()` never finishes; [awaitFinish] is false for those. */
    private fun broadcast(intent: Intent, awaitFinish: Boolean = true) {
        val receiver = Class.forName(intent.component!!.className).getDeclaredConstructor().newInstance() as BroadcastReceiver
        // Both the PendingResult constructor and setPendingResult are hidden
        // API, hence reflection; Robolectric shadows finish() and records it.
        val pendingResult = ReflectionHelpers.callConstructor(
            BroadcastReceiver.PendingResult::class.java,
            ClassParameter.from(Int::class.javaPrimitiveType, 0),
            ClassParameter.from(String::class.java, null),
            ClassParameter.from(Bundle::class.java, null),
            ClassParameter.from(Int::class.javaPrimitiveType, 0),
            ClassParameter.from(Boolean::class.javaPrimitiveType, false),
            ClassParameter.from(Boolean::class.javaPrimitiveType, false),
            ClassParameter.from(IBinder::class.java, null),
            ClassParameter.from(Int::class.javaPrimitiveType, 0),
            ClassParameter.from(Int::class.javaPrimitiveType, 0),
        )
        ReflectionHelpers.callInstanceMethod<Unit>(receiver, "setPendingResult", ClassParameter.from(BroadcastReceiver.PendingResult::class.java, pendingResult))
        receiver.onReceive(context, intent)
        if (awaitFinish) shadowOf(pendingResult).future.get(10, TimeUnit.SECONDS)
        ShadowLooper.idleMainLooper()
    }

    /** The plain-Application case: deliver and return without waiting. */
    fun deliverWithoutWaiting(intent: Intent) = broadcast(intent, awaitFinish = false)

    // ---- notifications ------------------------------------------------------

    /** Ids of the notifications currently showing (dose ids are positive;
     *  the group summary and pre-alarms are negative), the answered cards left out. */
    val notificationIds: List<Int> get() = shadowOf(notificationManager).activeNotifications
        .filter { it.notification.category != android.app.Notification.CATEGORY_STATUS }.map { it.id }

    /** The answered cards a Take or Skip from the shade leaves for a few seconds (§7 R134), by id. */
    val answeredCards: Map<Int, android.app.Notification> get() = shadowOf(notificationManager).activeNotifications
        .filter { it.notification.category == android.app.Notification.CATEGORY_STATUS }.associate { it.id to it.notification }

    /** What a swipe on the shade does to a notification that has no delete intent. */
    fun dismissFromShade(id: Int) = notificationManager.cancel(id)
}
