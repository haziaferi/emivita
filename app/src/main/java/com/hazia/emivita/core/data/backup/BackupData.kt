package com.hazia.emivita.core.data.backup

import com.hazia.emivita.core.data.entity.DoseEvent
import com.hazia.emivita.core.data.entity.DoseGroup
import com.hazia.emivita.core.data.entity.Medicine
import com.hazia.emivita.core.data.entity.MedicineTagCrossRef
import com.hazia.emivita.core.data.entity.Profile
import com.hazia.emivita.core.data.entity.Reminder
import com.hazia.emivita.core.data.entity.Tag
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.hazia.emivita.core.data.entity.MedicineNote

/** Current backup schema version -- bump when a field is added/removed in a
 *  way [BackupRepository.restoreSnapshot] can't already tolerate (kotlinx
 *  serialization defaults handle additive changes for free; this is for the
 *  rare breaking case). Not yet consulted by restore -- one version exists
 *  so far, nothing to migrate between. */
const val BACKUP_SCHEMA_VERSION = 1

/** The one Json every backup path uses (§7 R60 E F1). `ignoreUnknownKeys`
 *  lets an older app restore a newer file's known half, where the default
 *  `Json` fails at the first added field. `encodeDefaults` writes
 *  [BackupData.schemaVersion] and every defaulted field to disk, which the
 *  default `Json` silently omits. */
val BackupJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

sealed interface BackupDecodeResult {
    data class Ok(val data: BackupData) : BackupDecodeResult
    /** The file was written by a build with a higher schema version. Its
     *  known fields could be read, but a snapshot restored half-understood
     *  is worse than one refused with the version named. */
    data class NewerThanApp(val fileVersion: Int) : BackupDecodeResult
}

/** Decode a snapshot, refusing one from a newer schema. A file with no
 *  version field (written before R60) is version 1 by definition. */
fun decodeBackup(json: String): BackupDecodeResult {
    val version = runCatching {
        BackupJson.parseToJsonElement(json).jsonObject["schemaVersion"]?.jsonPrimitive?.int
    }.getOrNull() ?: BACKUP_SCHEMA_VERSION
    if (version > BACKUP_SCHEMA_VERSION) return BackupDecodeResult.NewerThanApp(version)
    return BackupDecodeResult.Ok(BackupJson.decodeFromString(BackupData.serializer(), json))
}

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
    /** Since §9 #26. Defaulted so a pre-#26 file still decodes; the schema
     *  version stays 1 because an older build can read a file that carries
     *  this (`ignoreUnknownKeys`), which is what a version bump would deny. */
    val medicineNotes: List<MedicineNote> = emptyList(),
)
