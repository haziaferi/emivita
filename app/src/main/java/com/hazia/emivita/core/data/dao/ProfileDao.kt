package com.hazia.emivita.core.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.hazia.emivita.core.data.entity.Profile
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles ORDER BY createdAtEpochMillis ASC")
    fun observeAll(): Flow<List<Profile>>

    @Query("SELECT * FROM profiles WHERE id = :id")
    suspend fun getById(id: Long): Profile?

    @Query("SELECT * FROM profiles WHERE id = :id")
    fun observeById(id: Long): Flow<Profile?>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(profile: Profile): Long

    @Update
    suspend fun update(profile: Profile)

    @Delete
    suspend fun delete(profile: Profile)

    // Backup/restore (spec §9 #9) -- getAllOnce for the export snapshot,
    // deleteAll+restoreAll for import. restoreAll uses REPLACE, not the
    // ABORT strategy insert() above uses, so restored rows keep their
    // original ids (foreign keys in medicines/reminders/etc. reference them).
    @Query("SELECT * FROM profiles ORDER BY createdAtEpochMillis ASC")
    suspend fun getAllOnce(): List<Profile>

    @Query("DELETE FROM profiles")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreAll(profiles: List<Profile>)
}
