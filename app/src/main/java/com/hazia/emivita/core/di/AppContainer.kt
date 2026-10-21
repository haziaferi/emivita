package com.hazia.emivita.core.di

import android.content.Context
import com.hazia.emivita.core.data.backup.AutoSyncManager
import com.hazia.emivita.core.data.backup.BackupRepository
import com.hazia.emivita.core.data.db.EmivitaDatabase
import com.hazia.emivita.core.data.repository.DoseEventRepository
import com.hazia.emivita.core.data.repository.MedicineRepository
import com.hazia.emivita.core.data.repository.ProfileRepository
import com.hazia.emivita.core.data.repository.ReminderRepository
import com.hazia.emivita.core.data.settings.SettingsRepository
import com.hazia.emivita.core.notification.AlertNotificationBuilder
import com.hazia.emivita.core.notification.ReminderNotificationBuilder
import com.hazia.emivita.core.scheduling.AlarmScheduler
import com.hazia.emivita.core.scheduling.DeliveryLedger
import com.hazia.emivita.core.scheduling.LowStockExpirationAlertEvaluator
import com.hazia.emivita.core.scheduling.OccurrenceProjectionCache
import com.hazia.emivita.core.scheduling.RealSchedulingCoordinator
import com.hazia.emivita.core.scheduling.SchedulingCoordinator
import com.hazia.emivita.core.scheduling.SchedulingEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import com.hazia.emivita.feature.widget.StockLevelsWidget
import com.hazia.emivita.feature.widget.NextRemindersWidget
import androidx.glance.appwidget.updateAll
import com.hazia.emivita.core.notification.UnansweredDoseReposter
import com.hazia.emivita.core.scheduling.MissedDoseBackfiller
import com.hazia.emivita.core.format.LocaleFormats
import com.hazia.emivita.core.data.settings.TimeFormat
import com.hazia.emivita.core.scheduling.AlarmWatchdog

/** Single-maintainer personal app -- a plain composition root reads more clearly
 *  here than Hilt's codegen for the size this project is at. Revisit if it
 *  grows enough that manual wiring becomes the annoying part. */
class AppContainer(
    context: Context,
    // Tests hand in an unencrypted in-memory database (§9 #30): the receivers
    // reach the real repositories, coordinator and scheduler on the JVM, where
    // SQLCipher's native library cannot load. Production passes nothing.
    injectedDatabase: EmivitaDatabase? = null,
    // What a widget refresh does: re-render every placed widget. A test, with
    // no launcher and so no placed widget, counts the calls instead.
    private val refreshWidgets: suspend (Context) -> Unit = {
        NextRemindersWidget().updateAll(it)
        StockLevelsWidget().updateAll(it)
    },
) {
    private val appContext = context.applicationContext

    // Process-lifetime scope for singletons that need to run their own
    // background work (currently just OccurrenceProjectionCache's reactive
    // watcher + debounced recompute loop) -- never cancelled, matching the
    // rest of this container's "lives as long as the process" singletons.
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // `by lazy` on purpose: EmivitaDatabase.getInstance() does Keystore access
    // and SQLCipher keying -- neither fast nor guaranteed synchronous-safe --
    // and AppContainer is constructed in EmivitaApplication.onCreate(), on the
    // main thread. Deferring means that cost lands on first actual access
    // rather than on every app launch (still worth wrapping that first access
    // in `withContext(Dispatchers.IO)` at the call site once one exists).
    private val databaseLazy = lazy { injectedDatabase ?: EmivitaDatabase.getInstance(context) }
    private val database: EmivitaDatabase by databaseLazy

    /** Tests only (§9 #31 found the need): stop the process-lifetime loops
     *  and close the database. A container that outlives its Robolectric
     *  test keeps recomputing the projection on a connection the runner has
     *  torn down, which in native SQLite mode is a JVM crash, not an
     *  exception. Production never calls this -- the process ends instead. */
    @androidx.annotation.VisibleForTesting
    internal fun shutdown() {
        applicationScope.cancel()
        if (databaseLazy.isInitialized()) runCatching { database.close() }
    }

    val profileRepository: ProfileRepository by lazy {
        ProfileRepository(database.profileDao(), autoSyncManager)
    }

    val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(appContext)
    }

    val medicineRepository: MedicineRepository by lazy {
        MedicineRepository(
            database = database,
            medicineDao = database.medicineDao(),
            tagDao = database.tagDao(),
            doseEventDao = database.doseEventDao(),
            reminderDao = database.reminderDao(),
            medicineNoteDao = database.medicineNoteDao(),
            schedulingCoordinator = schedulingCoordinator,
            occurrenceProjectionCache = occurrenceProjectionCache,
            autoSyncManager = autoSyncManager,
        )
    }

    // ---- Scheduling engine (AlarmManager + notifications) -------------------
    // See core/scheduling/SchedulingEngine.kt and RealSchedulingCoordinator.kt
    // for why these depend on ReminderDao/DoseEventDao directly rather than on
    // ReminderRepository -- ReminderRepository depends on schedulingCoordinator
    // below, so routing the engine through the repository too would be circular.

    val alarmScheduler: AlarmScheduler by lazy {
        AlarmScheduler(appContext)
    }

    // Private again -- Today migrated onto occurrenceProjectionCache (§9 item 5),
    // so RealSchedulingCoordinator and the cache itself (both below, in this file)
    // are the only remaining consumers.
    private val schedulingEngine: SchedulingEngine by lazy {
        SchedulingEngine(database.doseEventDao(), database.medicineDao(), settingsRepository).also {
            it.deliveredAt = { reminderId, doseEventId -> DeliveryLedger.deliveredAt(appContext, reminderId, doseEventId) }
        }
    }

    // Public since spec §7 R37: ReminderAlarmReceiver posts alert
    // notifications directly now that alert reminders own real alarms,
    // rather than every alert going through the evaluator.
    val alertNotificationBuilder: AlertNotificationBuilder by lazy {
        AlertNotificationBuilder(appContext, settingsRepository)
    }

    private val alertEvaluator: LowStockExpirationAlertEvaluator by lazy {
        LowStockExpirationAlertEvaluator(database.reminderDao(), alertNotificationBuilder)
    }

    // Shared occurrence-projection cache (spec §2/§9 item 4) -- foundation for
    // #5-7 (Today timeline, History, Widgets); also runs LowStockAlert/
    // ExpirationAlert triggering (§9 item 8, via alertEvaluator) as the last step
    // of its own recompute pass. See OccurrenceProjectionCache's own doc comment.
    val occurrenceProjectionCache: OccurrenceProjectionCache by lazy {
        OccurrenceProjectionCache(
            database.medicineDao(), schedulingEngine, alertEvaluator, applicationScope,
            onPublished = { widgetRefresh.trySend(Unit) },
            answeredSlots = { from, to ->
                database.doseEventDao().getAnsweredSlots(from, to).mapTo(HashSet()) { it.reminderId to it.scheduledEpochMillis }
            },
        )
    }

    // The widgets follow the data, as medTimer's do (§7 R104, census E-37),
    // not only a 30-minute timer: each finished projection pass re-renders
    // both, conflated so a burst of passes costs one refresh.
    private val widgetRefresh: Channel<Unit> by lazy {
        Channel<Unit>(capacity = Channel.CONFLATED).also { channel ->
            applicationScope.launch {
                for (unused in channel) {
                    runCatching { refreshWidgets(appContext) }.onFailure { android.util.Log.w("AppContainer", "widget refresh failed", it) }
                }
            }
        }
    }

    /** A hide-names flip re-renders the placed widgets (§7 R108, census E-36),
     *  through the same conflated channel as a projection pass. */
    fun observeWidgetSettings() {
        applicationScope.launch {
            settingsRepository.hideMedicineNames.distinctUntilChanged().drop(1).collect { widgetRefresh.trySend(Unit) }
        }
    }

    /** §9 #28: the time-format choice and the phone's 24-hour switch reach
     *  every formatter through LocaleFormats' two hooks. */
    fun observeTimeFormatSetting() {
        LocaleFormats.systemHour24 = { android.text.format.DateFormat.is24HourFormat(appContext) }
        applicationScope.launch {
            settingsRepository.timeFormat.collect { format ->
                LocaleFormats.hour24Override = when (format) { TimeFormat.SYSTEM -> null; TimeFormat.H12 -> false; TimeFormat.H24 -> true }
            }
        }
    }

    /** Mirror the setAlarmClock setting onto the scheduler and re-arm when it
     *  flips (§9 #25). Started once from EmivitaApplication; the first value
     *  arrives before any alarm is armed on a warm start, and rescheduleOnLaunch
     *  covers the cold one. */
    fun observeAlarmClockSetting() {
        applicationScope.launch {
            var first = true
            settingsRepository.useAlarmClock.collect { on ->
                alarmScheduler.useAlarmClock = on
                if (!first) runCatching { schedulingCoordinator.rescheduleAll() }
                first = false
            }
        }
    }

    /** The moment a dose reached the person; a follow-up counts from it (§7 R83 (c)). */
    fun recordDelivered(reminderId: Long, doseEventId: Long, atEpochMillis: Long) =
        DeliveryLedger.recordFirst(appContext, reminderId, doseEventId, atEpochMillis)

    /** Re-arm every enabled reminder from scratch, off the calling thread.
     *  Called from MainActivity.onCreate (§7 R60 B F-3) because a user
     *  force-stop drops every alarm the app holds and nothing else re-arms
     *  them short of a reboot, a restore or an edit. Idempotent: the same
     *  request codes with FLAG_UPDATE_CURRENT replace, not stack. */
    fun rescheduleOnLaunch() {
        applicationScope.launch {
            // Before the repair (§9 #29, DP-04): did an armed alarm fail to fire?
            runCatching { AlarmWatchdog.checkOnLaunch(appContext) }
                .onFailure { android.util.Log.e("AppContainer", "alarm watchdog failed", it) }
            runCatching { schedulingCoordinator.rescheduleAll() }
                .onFailure { android.util.Log.e("AppContainer", "rescheduleOnLaunch failed", it) }
            // The past, in two steps (§9 #25, #26): slots that passed while
            // the app was not running become Missed rows, then the last day's
            // unanswered doses get their notification back.
            runCatching { missedDoseBackfiller.backfill() }
                .onFailure { android.util.Log.e("AppContainer", "backfill on launch failed", it) }
            runCatching { alarmScheduler.rearmSnoozes() }
                .onFailure { android.util.Log.e("AppContainer", "snooze re-arm on launch failed", it) }
            // A re-posted parent has just reached the person, so its follow-ups
            // re-anchor on now (§7 R83 (c)).
            runCatching { if (unansweredDoseReposter.repost() > 0) schedulingCoordinator.rescheduleAll() }
                .onFailure { android.util.Log.e("AppContainer", "repost on launch failed", it) }
            // The weekly backup copy, if one is due, and its next alarm (§7 R101).
            runCatching { autoSyncManager.runWeekly() }
                .onFailure { android.util.Log.e("AppContainer", "weekly backup on launch failed", it) }
        }
    }

    val missedDoseBackfiller: MissedDoseBackfiller by lazy {
        MissedDoseBackfiller(database.reminderDao(), schedulingEngine, doseEventRepository, settingsRepository)
    }

    val unansweredDoseReposter: UnansweredDoseReposter by lazy {
        UnansweredDoseReposter(appContext, database.doseEventDao(), database.reminderDao(), database.medicineDao(), notificationBuilder)
    }

    val schedulingCoordinator: SchedulingCoordinator by lazy {
        RealSchedulingCoordinator(database.reminderDao(), schedulingEngine, alarmScheduler, occurrenceProjectionCache)
    }

    val notificationBuilder: ReminderNotificationBuilder by lazy {
        ReminderNotificationBuilder(appContext, database.doseGroupDao(), settingsRepository)
    }

    // ---- Backup/restore + auto-sync (spec §9 #9) -----------------------------

    val backupRepository: BackupRepository by lazy {
        BackupRepository(
            database = database,
            profileDao = database.profileDao(),
            medicineDao = database.medicineDao(),
            tagDao = database.tagDao(),
            doseGroupDao = database.doseGroupDao(),
            reminderDao = database.reminderDao(),
            doseEventDao = database.doseEventDao(),
            medicineNoteDao = database.medicineNoteDao(),
            schedulingCoordinator = schedulingCoordinator,
            occurrenceProjectionCache = occurrenceProjectionCache,
        )
    }

    // Debounced auto-sync writer -- notifyChanged() is called from the same
    // repository methods below that already call occurrenceProjectionCache's
    // invalidate(), matching spec §9 #9(b)'s trigger list exactly.
    val autoSyncManager: AutoSyncManager by lazy {
        AutoSyncManager(appContext, settingsRepository, backupRepository, applicationScope)
    }

    // ---- Repositories ---------------------------------------------------------

    val reminderRepository: ReminderRepository by lazy {
        ReminderRepository(database, database.reminderDao(), schedulingCoordinator, autoSyncManager)
    }

    val doseEventRepository: DoseEventRepository by lazy {
        DoseEventRepository(
            database = database,
            doseEventDao = database.doseEventDao(),
            medicineDao = database.medicineDao(),
            schedulingCoordinator = schedulingCoordinator,
            occurrenceProjectionCache = occurrenceProjectionCache,
            autoSyncManager = autoSyncManager,
            reminderDao = database.reminderDao(),
        )
    }
}
