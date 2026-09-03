package com.hazia.emivita.core.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Junction
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update
import com.hazia.emivita.core.data.entity.Medicine
import com.hazia.emivita.core.data.entity.MedicineTagCrossRef
import com.hazia.emivita.core.data.entity.Reminder
import com.hazia.emivita.core.data.entity.Tag
import kotlinx.coroutines.flow.Flow

data class MedicineWithTags(
    @Embedded val medicine: Medicine,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = MedicineTagCrossRef::class,
            parentColumn = "medicineId",
            entityColumn = "tagId",
        ),
    )
    val tags: List<Tag>,
)

/** Built specifically for [OccurrenceProjectionCache]'s reactive watcher (spec
 *  §9 #4) -- one @Relation-joined query so Room's own change-tracking re-fires
 *  a single Flow on a write to either `medicines` or `reminders`, mirroring
 *  [MedicineWithTags]'s shape but for the direct medicineId FK instead of a
 *  junction table. */
data class MedicineWithReminders(
    @Embedded val medicine: Medicine,
    @Relation(parentColumn = "id", entityColumn = "medicineId")
    val reminders: List<Reminder>,
)

@Dao
interface MedicineDao {
    // sortOrder, not name -- manual drag-to-reorder (spec §3), not alphabetical.
    @Transaction
    @Query("SELECT * FROM medicines WHERE profileId = :profileId ORDER BY sortOrder ASC")
    fun observeForProfile(profileId: Long): Flow<List<MedicineWithTags>>

    @Query("SELECT * FROM medicines WHERE id = :id")
    fun observeById(id: Long): Flow<Medicine?>

    @Query("SELECT * FROM medicines WHERE id = :id")
    suspend fun getById(id: Long): Medicine?

    // "Scan to find" (spec §3/§9 item 1) -- purely local, no different from
    // any other WHERE clause. Doesn't need the internet permission this app
    // deliberately doesn't declare; the scan itself is delegated to an
    // installed scanner app the same way "Scan barcode" on Add already is.
    @Query("SELECT * FROM medicines WHERE barcodeValue = :barcodeValue LIMIT 1")
    suspend fun getByBarcodeValue(barcodeValue: String): Medicine?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(medicine: Medicine): Long

    @Update
    suspend fun update(medicine: Medicine)

    @Delete
    suspend fun delete(medicine: Medicine)

    /** Used by the resolve-a-dose flow to decrement stock -- kept as its own
     *  narrow query rather than a full entity update so DoseEventRepository
     *  doesn't need to read-modify-write the whole Medicine row. */
    @Query("UPDATE medicines SET quantity = MAX(0.0, quantity - :amount) WHERE id = :medicineId")
    suspend fun decrementStock(medicineId: Long, amount: Double)

    /** Used by DoseEventRepository.logRefill() -- same narrow-update shape as
     *  decrementStock, opposite direction. No upper clamp: an over-generous
     *  refill amount is a user data-entry concern, not one this query should
     *  silently correct. */
    @Query("UPDATE medicines SET quantity = quantity + :amount WHERE id = :medicineId")
    suspend fun incrementStock(medicineId: Long, amount: Double)

    /** The change-notification side of [OccurrenceProjectionCache]: Room
     *  re-fires this on any write to `medicines` or `reminders`. */
    @Transaction
    @Query("SELECT * FROM medicines")
    fun observeAllWithReminders(): Flow<List<MedicineWithReminders>>

    /** The read side of the same data. A one-shot suspend query rather than
     *  `observeAllWithReminders().first()`, which registers and immediately
     *  tears down a Room InvalidationTracker observer just to take one
     *  snapshot -- pure overhead on a recompute pass that is already
     *  subscribed to the Flow above for exactly that signal. */
    @Transaction
    @Query("SELECT * FROM medicines")
    suspend fun getAllWithReminders(): List<MedicineWithReminders>

    /** Widgets (spec §9 #7) -- StockLevelsWidget's one-shot snapshot of every
     *  medicine with stock tracking active, across all profiles (matches the
     *  cache's own profile-agnostic observeAllWithReminders() above). */
    @Query("SELECT * FROM medicines WHERE unit IS NOT NULL")
    suspend fun getAllTracked(): List<Medicine>

    /** Direct set, not a relative +/- like decrementStock/incrementStock --
     *  used by DoseEventRepository.resolve(), which computes the target
     *  quantity from a stock read taken inside the same transaction, so the
     *  absolute write has no staleness window and is a decrement in all but
     *  name.
     *
     *  switchStatus() used to be the other caller, and must not become one
     *  again: its sentinel pair was recorded at some earlier resolution, so
     *  writing it back absolutely discarded every stock change made since.
     *  It now applies a relative delta instead -- see docs/SPEC.md §7 R27,
     *  which also retires the "relative math could compound" claim this
     *  comment used to make. */
    @Query("UPDATE medicines SET quantity = :quantity WHERE id = :medicineId")
    suspend fun setStock(medicineId: Long, quantity: Double)

    // Backup/restore (spec §9 #9) -- see ProfileDao's matching trio for why
    // restoreAll is REPLACE, not insert()'s ABORT.
    @Query("SELECT * FROM medicines")
    suspend fun getAllOnce(): List<Medicine>

    @Query("DELETE FROM medicines")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreAll(medicines: List<Medicine>)
}
