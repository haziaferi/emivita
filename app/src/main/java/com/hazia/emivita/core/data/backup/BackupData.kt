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
import com.hazia.emivita.core.data.config.ReminderType
import com.hazia.emivita.core.data.entity.DoseStatus
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import com.hazia.emivita.core.data.entity.MedicineNote

/** Current backup schema version -- bump when a field is added/removed in a
 *  way [BackupRepository.restoreSnapshot] can't already tolerate (kotlinx
 *  serialization defaults handle additive changes for free; this is for the
 *  rare breaking case). Version 2 (§7 R105): DoseStatus gained the two
 *  seen-alert values, which an older app cannot decode, so it refuses a v2
 *  file as newer than itself instead of failing on it. Version 3 (§7 R111):
 *  a one-off dose's `medicineId` is null, which an older app's non-null field
 *  cannot decode, so the same refusal applies. */
const val BACKUP_SCHEMA_VERSION = 3

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
    return BackupDecodeResult.Ok(deriveMissingAsNeeded(json, BackupJson.decodeFromString(BackupData.serializer(), json)))
}

/** A medicine written before `asNeeded` existed (§7 R116) has no such key and
 *  decodes as false, which would move an as-needed one to *Senza promemoria*.
 *  It gets the flag as MIGRATION_11_12 gives it: no enabled dosing reminder and
 *  a dose logged. A file that carries the key keeps what it says. */
private fun deriveMissingAsNeeded(json: String, data: BackupData): BackupData {
    val missing = BackupJson.parseToJsonElement(json).jsonObject["medicines"]?.jsonArray.orEmpty()
        .map { it.jsonObject }
        .filter { "asNeeded" !in it }
        .map { it["id"]?.jsonPrimitive?.longOrNull ?: 0L }
        .toSet()
    if (missing.isEmpty()) return data
    val scheduled = data.reminders
        .filter { it.enabled && it.type != ReminderType.LOW_STOCK_ALERT && it.type != ReminderType.EXPIRATION_ALERT }
        .map { it.medicineId }.toSet()
    val logged = data.doseEvents.filter { it.status == DoseStatus.TAKEN }.mapNotNull { it.medicineId }.toSet()
    return data.copy(
        medicines = data.medicines.map { m -> if (m.id in missing) m.copy(asNeeded = m.id !in scheduled && m.id in logged) else m },
    )
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
    /** `planned` and `missedAfterEpochMillis` (§7 R118, R119) are additive, as
     *  `asNeeded` was (§7 R116), so the version stays: an older file decodes
     *  `planned` from its own reminderId through the field's default. The
     *  second is inert since §7 R121 and still decodes from a schema-13 file. */
    val doseEvents: List<DoseEvent>,
    /** Since §9 #26. Defaulted so a pre-#26 file still decodes; the schema
     *  version stays 1 because an older build can read a file that carries
     *  this (`ignoreUnknownKeys`), which is what a version bump would deny. */
    val medicineNotes: List<MedicineNote> = emptyList(),
    /** Since §7 R133: the travelling settings (`SettingsRepository.exportForBackup`). Additive and defaulted, as
     *  medicineNotes was, so the version stays and an older build reads the file; null in a file from before it. */
    val settings: Map<String, kotlinx.serialization.json.JsonElement>? = null,
)
