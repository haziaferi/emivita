package com.hazia.emivita.core.data.repository

import androidx.room.withTransaction
import com.hazia.emivita.core.data.backup.AutoSyncManager
import com.hazia.emivita.core.data.dao.DoseEventDao
import com.hazia.emivita.core.data.dao.MedicineDao
import com.hazia.emivita.core.data.dao.MedicineWithTags
import com.hazia.emivita.core.data.dao.ReminderDao
import com.hazia.emivita.core.data.dao.TagDao
import com.hazia.emivita.core.data.db.EmivitaDatabase
import com.hazia.emivita.core.data.entity.Medicine
import com.hazia.emivita.core.data.entity.MedicineTagCrossRef
import com.hazia.emivita.core.data.entity.Tag
import com.hazia.emivita.core.scheduling.OccurrenceProjectionCache
import com.hazia.emivita.core.scheduling.SchedulingCoordinator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

class MedicineRepository(
    private val database: EmivitaDatabase,
    private val medicineDao: MedicineDao,
    private val tagDao: TagDao,
    private val doseEventDao: DoseEventDao,
    private val reminderDao: ReminderDao,
    private val schedulingCoordinator: SchedulingCoordinator,
    private val occurrenceProjectionCache: OccurrenceProjectionCache,
    private val autoSyncManager: AutoSyncManager,
) {
    fun observeForProfile(profileId: Long): Flow<List<MedicineWithTags>> =
        medicineDao.observeForProfile(profileId)

    fun observeById(id: Long): Flow<Medicine?> = medicineDao.observeById(id)
    suspend fun getById(id: Long): Medicine? = medicineDao.getById(id)
    suspend fun getByBarcodeValue(barcodeValue: String): Medicine? = medicineDao.getByBarcodeValue(barcodeValue)
    suspend fun getAllTracked(): List<Medicine> = medicineDao.getAllTracked()

    suspend fun create(medicine: Medicine): Long =
        medicineDao.insert(medicine).also { autoSyncManager.notifyChanged() }

    suspend fun update(medicine: Medicine) {
        medicineDao.update(medicine)
        autoSyncManager.notifyChanged()
    }

    /** Cascade delete (spec §9 #1's deferred data-integrity question,
     *  resolved: cascade) -- removes the medicine's Reminders and every
     *  DoseEvent tied to it, scheduled or PRN. `reminders.medicineId` is
     *  already a CASCADE FK, so deleting the Medicine row alone would
     *  transitively take reminder-linked DoseEvents with it (their own FK
     *  is CASCADE too) -- but PRN/refill DoseEvents only carry a
     *  denormalized `medicineId`, no FK, so [DoseEventDao.deleteAllForMedicine]
     *  is what actually catches those. Alarms are OS state, not DB rows --
     *  Room's cascade can't touch AlarmManager, so every reminder's pending
     *  alarm (regular + nag) is explicitly cancelled first via
     *  [SchedulingCoordinator.onReminderRemoved]. */
    suspend fun delete(medicine: Medicine) {
        val reminderIds = reminderDao.observeForMedicine(medicine.id).first().map { it.id }

        database.withTransaction {
            doseEventDao.deleteAllForMedicine(medicine.id)
            medicineDao.delete(medicine)
        }

        // Outside the transaction -- OS-level alarm cancellation, not a DB
        // write, same reasoning DoseEventRepository.resolve() documents.
        for (reminderId in reminderIds) {
            schedulingCoordinator.onReminderRemoved(reminderId)
        }
        occurrenceProjectionCache.invalidate()
        autoSyncManager.notifyChanged()
    }

    fun observeAllTags(): Flow<List<Tag>> = tagDao.observeAll()
    fun observeTagsForMedicine(medicineId: Long): Flow<List<Tag>> = tagDao.observeTagsForMedicine(medicineId)

    // Was two un-wrapped suspend calls (flagged in review, R1) -- a process death
    // or thrown exception between insert() and attachToMedicine() could leave a
    // new Tag row created with nothing pointing to it. Now atomic.
    suspend fun addTag(medicineId: Long, tagName: String) {
        database.withTransaction {
            val insertedId = tagDao.insert(Tag(name = tagName))
            // OnConflictStrategy.IGNORE returns -1 on a duplicate name instead of the
            // existing row's id -- resolve it explicitly so the attach below never
            // silently no-ops for a tag that already exists.
            val resolvedId = if (insertedId != -1L) insertedId else {
                tagDao.getByName(tagName)?.id
                    ?: error("Tag insert conflicted but no existing tag named '$tagName' was found")
            }
            tagDao.attachToMedicine(MedicineTagCrossRef(medicineId, resolvedId))
        }
        autoSyncManager.notifyChanged()
    }

    suspend fun removeTag(medicineId: Long, tagId: Long) {
        tagDao.detachFromMedicine(medicineId, tagId)
        autoSyncManager.notifyChanged()
    }
}
