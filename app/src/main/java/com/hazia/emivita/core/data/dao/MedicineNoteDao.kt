package com.hazia.emivita.core.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.hazia.emivita.core.data.entity.MedicineNote
import kotlinx.coroutines.flow.Flow

@Dao
interface MedicineNoteDao {
    @Query("SELECT * FROM medicine_notes WHERE medicineId = :medicineId ORDER BY createdAtEpochMillis DESC")
    fun observeForMedicine(medicineId: Long): Flow<List<MedicineNote>>

    @Query("SELECT * FROM medicine_notes WHERE medicineId = :medicineId ORDER BY createdAtEpochMillis DESC")
    suspend fun getForMedicine(medicineId: Long): List<MedicineNote>

    @Insert
    suspend fun insert(note: MedicineNote): Long

    @Update
    suspend fun update(note: MedicineNote)

    @Query("DELETE FROM medicine_notes WHERE id = :id")
    suspend fun delete(id: Long)

    // ---- backup (spec §9 #9) ----
    @Query("SELECT * FROM medicine_notes")
    suspend fun getAllOnce(): List<MedicineNote>

    @Query("DELETE FROM medicine_notes")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreAll(notes: List<MedicineNote>)
}
