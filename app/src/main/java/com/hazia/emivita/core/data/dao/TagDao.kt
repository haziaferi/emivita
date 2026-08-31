package com.hazia.emivita.core.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.hazia.emivita.core.data.entity.MedicineTagCrossRef
import com.hazia.emivita.core.data.entity.Tag
import kotlinx.coroutines.flow.Flow

@Dao
interface TagDao {
    @Query("SELECT * FROM tags ORDER BY name ASC")
    fun observeAll(): Flow<List<Tag>>

    @Query("SELECT * FROM tags WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): Tag?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(tag: Tag): Long

    @Delete
    suspend fun delete(tag: Tag)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun attachToMedicine(crossRef: MedicineTagCrossRef)

    @Query("DELETE FROM medicine_tag_cross_ref WHERE medicineId = :medicineId AND tagId = :tagId")
    suspend fun detachFromMedicine(medicineId: Long, tagId: Long)

    @Query(
        """
        SELECT tags.* FROM tags
        INNER JOIN medicine_tag_cross_ref ON tags.id = medicine_tag_cross_ref.tagId
        WHERE medicine_tag_cross_ref.medicineId = :medicineId
        ORDER BY tags.name ASC
        """
    )
    fun observeTagsForMedicine(medicineId: Long): Flow<List<Tag>>

    // Backup/restore (spec §9 #9) -- both tags and the cross-ref join table,
    // since a backup snapshot needs the full tag<->medicine relationship, not
    // just the tag rows themselves.
    @Query("SELECT * FROM tags ORDER BY name ASC")
    suspend fun getAllOnce(): List<Tag>

    @Query("DELETE FROM tags")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreAll(tags: List<Tag>)

    @Query("SELECT * FROM medicine_tag_cross_ref")
    suspend fun getAllCrossRefsOnce(): List<MedicineTagCrossRef>

    @Query("DELETE FROM medicine_tag_cross_ref")
    suspend fun deleteAllCrossRefs()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreAllCrossRefs(crossRefs: List<MedicineTagCrossRef>)
}
