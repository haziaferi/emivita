package com.hazia.emivita.core.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.hazia.emivita.core.data.entity.DoseGroup
import kotlinx.coroutines.flow.Flow

@Dao
interface DoseGroupDao {
    @Query("SELECT * FROM dose_groups WHERE profileId = :profileId ORDER BY createdAtEpochMillis ASC")
    fun observeForProfile(profileId: Long): Flow<List<DoseGroup>>

    @Query("SELECT * FROM dose_groups WHERE id = :id")
    suspend fun getById(id: Long): DoseGroup?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(group: DoseGroup): Long

    @Update
    suspend fun update(group: DoseGroup)

    @Delete
    suspend fun delete(group: DoseGroup)

    // Backup/restore (spec §9 #9).
    @Query("SELECT * FROM dose_groups")
    suspend fun getAllOnce(): List<DoseGroup>

    @Query("DELETE FROM dose_groups")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreAll(groups: List<DoseGroup>)
}
