package com.hazia.emivita.core.data.backup

import com.hazia.emivita.core.data.entity.DoseEvent
import com.hazia.emivita.core.data.entity.DoseGroup
import com.hazia.emivita.core.data.entity.Medicine
import com.hazia.emivita.core.data.entity.MedicineTagCrossRef
import com.hazia.emivita.core.data.entity.Profile
import com.hazia.emivita.core.data.entity.Reminder
import com.hazia.emivita.core.data.entity.Tag
import kotlinx.serialization.Serializable

/** Current backup schema version -- bump when a field is added/removed in a
 *  way [BackupRepository.restoreSnapshot] can't already tolerate (kotlinx
 *  serialization defaults handle additive changes for free; this is for the
 *  rare breaking case). Not yet consulted by restore -- one version exists
 *  so far, nothing to migrate between. */
const val BACKUP_SCHEMA_VERSION = 1

/** The whole-database JSON snapshot for spec §9 #9's shared writer -- both
 *  (a) manual export/restore and (b) the auto-sync folder zip the exact same
 *  shape, just under different triggers/password policy (see
 *  BackupRepository/AutoSyncManager). Entities are serialized directly
 *  (each carries its own @Serializable, see core/data/entity) rather than
 *  through a parallel DTO layer -- one shape to keep in sync with the schema,
 *  not two. */
@Serializable
data class BackupData(
    val schemaVersion: Int = BACKUP_SCHEMA_VERSION,
    val exportedAtEpochMillis: Long,
    val profiles: List<Profile>,
    val medicines: List<Medicine>,
    val tags: List<Tag>,
    val medicineTagCrossRefs: List<MedicineTagCrossRef>,
    val doseGroups: List<DoseGroup>,
    val reminders: List<Reminder>,
    val doseEvents: List<DoseEvent>,
)
