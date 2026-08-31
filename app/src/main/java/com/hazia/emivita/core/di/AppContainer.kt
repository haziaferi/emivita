package com.hazia.emivita.core.di

import android.content.Context
import com.hazia.emivita.core.data.backup.AutoSyncManager
import com.hazia.emivita.core.data.backup.BackupRepository
import com.hazia.emivita.core.data.db.EmivitaDatabase
import com.hazia.emivita.core.data.repository.DoseEventRepository
import com.hazia.emivita.core.data.repository.DoseGroupRepository
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

    // `by lazy` on purpose: EmivitaDatabase.getInstance() does Keystore access,
    // EncryptedSharedPreferences setup, and SQLCipher native-library loading +
    // keying -- none of it fast or guaranteed synchronous-safe. Previously this
    // ran unconditionally the instant AppContainer was constructed, which
    // happens in EmivitaApplication.onCreate() on the main thread, risking an
    // ANR at app launch. Deferring it here means it only runs on first actual
    // access (still worth wrapping that first access in
    // `withContext(Dispatchers.IO)` at the call site once one exists).
    private val database: EmivitaDatabase by lazy { EmivitaDatabase.getInstance(context) }

    val profileRepository: ProfileRepository by lazy {
        ProfileRepository(database.profileDao())
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
        SchedulingEngine(database.doseEventDao(), settingsRepository)
    }

    private val alertNotificationBuilder: AlertNotificationBuilder by lazy {
        AlertNotificationBuilder(appContext)
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
        ReminderRepository(database.reminderDao(), schedulingCoordinator, autoSyncManager)
    }

    val doseGroupRepository: DoseGroupRepository by lazy {
        DoseGroupRepository(database, database.doseGroupDao(), database.reminderDao())
    }

    val doseEventRepository: DoseEventRepository by lazy {
        DoseEventRepository(
            database = database,
            doseEventDao = database.doseEventDao(),
            medicineDao = database.medicineDao(),
            reminderDao = database.reminderDao(),
            schedulingCoordinator = schedulingCoordinator,
            occurrenceProjectionCache = occurrenceProjectionCache,
            autoSyncManager = autoSyncManager,
        )
    }
}
