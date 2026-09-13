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
import com.hazia.emivita.core.scheduling.LowStockExpirationAlertEvaluator
import com.hazia.emivita.core.scheduling.OccurrenceProjectionCache
import com.hazia.emivita.core.scheduling.RealSchedulingCoordinator
import com.hazia.emivita.core.scheduling.SchedulingCoordinator
import com.hazia.emivita.core.scheduling.SchedulingEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.hazia.emivita.core.notification.UnansweredDoseReposter

/** Single-maintainer personal app -- a plain composition root reads more clearly
 *  here than Hilt's codegen for the size this project is at. Revisit if it
 *  grows enough that manual wiring becomes the annoying part. */
class AppContainer(context: Context) {
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
    private val database: EmivitaDatabase by lazy { EmivitaDatabase.getInstance(context) }

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
        SchedulingEngine(database.doseEventDao(), database.medicineDao(), settingsRepository)
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
        OccurrenceProjectionCache(database.medicineDao(), schedulingEngine, alertEvaluator, applicationScope)
    }

    /** Re-arm every enabled reminder from scratch, off the calling thread.
     *  Called from MainActivity.onCreate (§7 R60 B F-3): a user force-stop
     *  drops every alarm the app holds, and until now nothing re-armed them
     *  short of a reboot, a backup restore, or editing a reminder -- the app
     *  opened onto a populated Today screen with no alarm behind any row.
     *  medTimer restarts its scheduler service on every resume. Idempotent:
     *  the same request codes with FLAG_UPDATE_CURRENT replace, not stack. */
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

    fun rescheduleOnLaunch() {
        applicationScope.launch {
            runCatching { schedulingCoordinator.rescheduleAll() }
                .onFailure { android.util.Log.e("AppContainer", "rescheduleOnLaunch failed", it) }
            // And the past: a force-stop cleared the shade as well as the alarms.
            runCatching { unansweredDoseReposter.repost() }
                .onFailure { android.util.Log.e("AppContainer", "repost on launch failed", it) }
        }
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
        )
    }
}
