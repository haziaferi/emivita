package com.hazia.emivita.core.data.backup

import androidx.room.withTransaction
import com.hazia.emivita.core.data.dao.DoseEventDao
import com.hazia.emivita.core.data.dao.DoseGroupDao
import com.hazia.emivita.core.data.dao.MedicineDao
import com.hazia.emivita.core.data.dao.ProfileDao
import com.hazia.emivita.core.data.dao.ReminderDao
import com.hazia.emivita.core.data.dao.TagDao
import com.hazia.emivita.core.data.db.EmivitaDatabase
import com.hazia.emivita.core.scheduling.OccurrenceProjectionCache
import com.hazia.emivita.core.scheduling.SchedulingCoordinator
import com.hazia.emivita.core.data.dao.MedicineNoteDao

/**
 * The single reader/writer for whole-database JSON snapshots (spec §9 #9) --
 * both the manual export/restore screen and the auto-sync debounced writer
 * (AutoSyncManager) go through this rather than touching the DAOs directly,
 * the same "one entry point" discipline DoseEventRepository/ReminderRepository
 * already follow.
 */
class BackupRepository(
    private val database: EmivitaDatabase,
    private val profileDao: ProfileDao,
    private val medicineDao: MedicineDao,
    private val tagDao: TagDao,
    private val doseGroupDao: DoseGroupDao,
    private val reminderDao: ReminderDao,
    private val doseEventDao: DoseEventDao,
    private val medicineNoteDao: MedicineNoteDao,
    private val schedulingCoordinator: SchedulingCoordinator,
    private val occurrenceProjectionCache: OccurrenceProjectionCache,
) {
    suspend fun exportSnapshot(nowEpochMillis: Long): BackupData = BackupData(
        exportedAtEpochMillis = nowEpochMillis,
        profiles = profileDao.getAllOnce(),
        medicines = medicineDao.getAllOnce(),
        tags = tagDao.getAllOnce(),
        medicineTagCrossRefs = tagDao.getAllCrossRefsOnce(),
        doseGroups = doseGroupDao.getAllOnce(),
        reminders = reminderDao.getAllOnce(),
        doseEvents = doseEventDao.getAllOnce(),
        medicineNotes = medicineNoteDao.getAllOnce(),
    )

    /** Every table, in dependency order -- the restore's first half, and the
     *  whole of "Delete everything" (§9 #29, MT-22). Inside a transaction. */
    private suspend fun deleteEverything() {
        medicineNoteDao.deleteAll()
        doseEventDao.deleteAll()
        reminderDao.deleteAll()
        tagDao.deleteAllCrossRefs()
        doseGroupDao.deleteAll()
        medicineDao.deleteAll()
        tagDao.deleteAll()
        profileDao.deleteAll()
    }

    /**
     * Back to the first-run state (§9 #29, Appendix F MT-22): every row gone,
     * every alarm cancelled (rescheduleAll only re-arms *enabled* reminders,
     * so the removed ones are cancelled one by one first), the projection
     * dropped. Settings, the app language and the lock stay, because they are
     * the phone's, not the data's, and EmivitaApp recreates the default profile.
     */
    suspend fun purgeAll() {
        val reminderIds = reminderDao.getAllOnce().map { it.id }
        database.withTransaction { deleteEverything() }
        reminderIds.forEach { runCatching { schedulingCoordinator.onReminderRemoved(it) } }
        occurrenceProjectionCache.invalidate()
    }

    /** Wholesale replace, not a merge -- restoring a disaster-recovery/migration
     *  snapshot means "the device now matches this file," not "layer this file
     *  on top of whatever's already here" (spec §9 #9(a)'s own framing: new
     *  phone, handing a snapshot to someone). Deletes in FK-dependency order,
     *  then inserts in the reverse order via REPLACE so every restored row
     *  keeps its original id -- the cross-references (medicineId, reminderId,
     *  parentReminderId, ...) only stay correct if ids round-trip exactly. */
    suspend fun restoreSnapshot(data: BackupData) {
        database.withTransaction {
            deleteEverything()

            profileDao.restoreAll(data.profiles)
            tagDao.restoreAll(data.tags)
            medicineDao.restoreAll(data.medicines)
            tagDao.restoreAllCrossRefs(data.medicineTagCrossRefs)
            doseGroupDao.restoreAll(data.doseGroups)
            reminderDao.restoreAll(data.reminders)
            doseEventDao.restoreAll(data.doseEvents)
            medicineNoteDao.restoreAll(data.medicineNotes)
        }

        // Outside the transaction, same reasoning DoseEventRepository.resolve()
        // documents: OS-level alarm scheduling isn't a DB write and shouldn't
        // roll back an already-committed restore if it fails. Every enabled
        // reminder needs its alarm re-armed against the restored data (the old
        // in-memory alarms, if any, pointed at rows that may no longer exist
        // with the same schedule).
        schedulingCoordinator.rescheduleAll()
        occurrenceProjectionCache.invalidate()
    }
}
