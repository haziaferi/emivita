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
import com.hazia.emivita.core.data.entity.StockInfo
import com.hazia.emivita.core.data.config.decodeReminderConfig
import com.hazia.emivita.core.data.dao.MedicineWithReminders
import com.hazia.emivita.core.data.entity.MedicineNote
import com.hazia.emivita.core.data.dao.MedicineNoteDao

class MedicineRepository(
    private val database: EmivitaDatabase,
    private val medicineDao: MedicineDao,
    private val tagDao: TagDao,
    private val doseEventDao: DoseEventDao,
    private val reminderDao: ReminderDao,
    private val medicineNoteDao: MedicineNoteDao,
    private val schedulingCoordinator: SchedulingCoordinator,
    private val occurrenceProjectionCache: OccurrenceProjectionCache,
    private val autoSyncManager: AutoSyncManager,
) {
    fun observeForProfile(profileId: Long): Flow<List<MedicineWithTags>> =
        medicineDao.observeForProfile(profileId)

    fun observeById(id: Long): Flow<Medicine?> = medicineDao.observeById(id)
    fun observeWithRemindersForProfile(profileId: Long): Flow<List<MedicineWithReminders>> =
        medicineDao.observeWithRemindersForProfile(profileId)

    /** Persist a drag (§9 #24): one row's sortOrder, computed by the caller
     *  from its new neighbours (`sortOrderForMove`). Nothing else is
     *  renumbered -- medTimer's `move()` idea. */
    suspend fun setSortOrder(medicineId: Long, sortOrder: Double) {
        medicineDao.updateSortOrder(medicineId, sortOrder)
        autoSyncManager.notifyChanged()
    }
    suspend fun getById(id: Long): Medicine? = medicineDao.getById(id)
    suspend fun getByBarcodeValue(barcodeValue: String): Medicine? = medicineDao.getByBarcodeValue(barcodeValue)
    suspend fun getAllTracked(): List<Medicine> = medicineDao.getAllTracked()

    suspend fun create(medicine: Medicine): Long =
        medicineDao.insert(medicine).also { autoSyncManager.notifyChanged() }

    /** Editing a medicine can change its stock quantity or expiry date, both
     *  of which are inputs to alert-reminder scheduling (spec §7 R37), so this
     *  re-arms that medicine's alert alarms. The reminder rows are untouched,
     *  so nothing else would. */
    suspend fun update(medicine: Medicine) {
        medicineDao.update(medicine)
        schedulingCoordinator.onMedicineChanged(medicine.id)
        autoSyncManager.notifyChanged()
    }

    /** Duplicate a medicine (§9 #24; §6 planned "Duplicate, Duplicate-with-
     *  reminders" in the overflow, and Appendix E C-28 / F HM-41 confirmed
     *  every neighbour has it). The copy keeps every field but the id,
     *  barcode (a code identifies one pack) and stock (a new entry starts
     *  untracked), takes the name with a "(copy)" suffix, the same tags, and
     *  -- when asked -- the reminders, re-created through [ReminderRepository]
     *  so each gets its own alarm. LinkedFollowing parents are remapped to
     *  the copies; a child whose parent was not copied is dropped rather
     *  than left pointing at the original's parent. Returns the new id. */
    suspend fun duplicate(
        medicineId: Long,
        copySuffix: String,
        withReminders: Boolean,
        reminderRepository: ReminderRepository,
    ): Long? {
        val source = medicineDao.getById(medicineId) ?: return null
        val tags = tagDao.observeTagsForMedicine(medicineId).first()
        val newId = database.withTransaction {
            val id = medicineDao.insert(
                source.copy(
                    id = 0,
                    name = "${source.name} $copySuffix",
                    barcodeValue = null,
                    stockInfo = StockInfo(),
                    sortOrder = System.currentTimeMillis().toDouble(),
                    createdAtEpochMillis = System.currentTimeMillis(),
                ),
            )
            for (tag in tags) tagDao.attachToMedicine(MedicineTagCrossRef(medicineId = id, tagId = tag.id))
            id
        }
        if (withReminders) {
            val originals = reminderDao.observeForMedicine(medicineId).first()
            val idMap = mutableMapOf<Long, Long>()
            // Parents before children so a linked child's parent id is known.
            val ordered = originals.filter { it.parentReminderId == null } + originals.filter { it.parentReminderId != null }
            for (r in ordered) {
                val parent = r.parentReminderId?.let { idMap[it] }
                if (r.parentReminderId != null && parent == null) continue
                val copiedId = reminderRepository.create(
                    medicineId = newId,
                    groupId = null,
                    type = r.type,
                    config = decodeReminderConfig(r.configJson),
                    parentReminderId = parent,
                    autoResolve = r.autoResolve,
                    activeFromEpochDay = r.activeFromEpochDay,
                    activeUntilEpochDay = r.activeUntilEpochDay,
                    repeatOnUnanswered = r.repeatOnUnanswered,
                    weekendModeOptIn = r.weekendModeOptIn,
                    preAlarmMinutes = r.preAlarmMinutes,
                    instructions = r.instructions,
                    dosesRemaining = r.dosesRemaining,
                )
                idMap[r.id] = copiedId
            }
        }
        occurrenceProjectionCache.invalidate()
        autoSyncManager.notifyChanged()
        return newId
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
    /** "Delete" since §9 #26 (the owner's reversal of the cascade below,
     *  which is now [deletePermanently]): the medicine is archived -- out of
     *  Medicines, Today, the projection and the widgets; its reminders are
     *  removed and their alarms cancelled -- and its dose history stays for
     *  History and the exports, which is what a clinician-facing record is
     *  for. medTimer keeps history as snapshots for the same reason. */
    suspend fun archive(medicine: Medicine) {
        val reminders = reminderDao.observeForMedicine(medicine.id).first()
        database.withTransaction {
            medicineDao.setArchived(medicine.id, true)
            // Disabled, not deleted: `dose_events.reminderId` cascades on a
            // reminder delete, which would take the very history archiving
            // exists to keep -- the test for this method found it. A restored
            // medicine gets its reminders back paused, to re-enable by hand.
            for (r in reminders) if (r.enabled) reminderDao.update(r.copy(enabled = false))
        }
        for (r in reminders) schedulingCoordinator.onReminderRemoved(r.id)
        occurrenceProjectionCache.invalidate()
        autoSyncManager.notifyChanged()
    }

    suspend fun unarchive(medicineId: Long) {
        medicineDao.setArchived(medicineId, false)
        occurrenceProjectionCache.invalidate()
        autoSyncManager.notifyChanged()
    }

    fun observeArchivedForProfile(profileId: Long): Flow<List<Medicine>> = medicineDao.observeArchivedForProfile(profileId)

    // ---- notes journal (§9 #26; Appendix F MT-04) ----
    fun observeNotes(medicineId: Long): Flow<List<MedicineNote>> = medicineNoteDao.observeForMedicine(medicineId)
    suspend fun addNote(medicineId: Long, text: String): Long =
        medicineNoteDao.insert(MedicineNote(medicineId = medicineId, text = text.trim(), createdAtEpochMillis = System.currentTimeMillis()))
            .also { autoSyncManager.notifyChanged() }
    suspend fun editNote(note: MedicineNote, text: String) {
        medicineNoteDao.update(note.copy(text = text.trim(), editedAtEpochMillis = System.currentTimeMillis()))
        autoSyncManager.notifyChanged()
    }
    suspend fun deleteNote(noteId: Long) {
        medicineNoteDao.delete(noteId)
        autoSyncManager.notifyChanged()
    }

    /** The old cascade, kept for the archive's "Delete permanently". */
    suspend fun deletePermanently(medicine: Medicine) = delete(medicine)

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
