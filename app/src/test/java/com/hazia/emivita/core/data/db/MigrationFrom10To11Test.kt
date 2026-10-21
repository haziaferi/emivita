package com.hazia.emivita.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.hazia.emivita.core.data.entity.DoseEvent
import com.hazia.emivita.core.data.entity.DoseStatus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.SQLiteMode
import java.io.File

/**
 * v10 -> v11 (§7 R111, Appendix E C-15): `dose_events` is rebuilt so
 * `medicineId` can be null and a one-off dose can carry a name and a profile.
 * The v10 database is built from the exported `10.json` (every table, its
 * indices and Room's identity hash), filled, and then opened by Room through
 * the app's own migration list, so Room's validator checks the rebuilt table
 * against the v11 entity before any assertion here runs.
 */
@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class MigrationFrom10To11Test {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "migration-10-11-test"
    private lateinit var database: EmivitaDatabase

    private val schemaDir = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "app/schemas/com.hazia.emivita.core.data.db.EmivitaDatabase") }.first { it.isDirectory }

    /** The v10 file: every CREATE in `10.json`, then its setup queries (the identity hash). */
    private fun createVersion10() {
        context.deleteDatabase(name)
        val root = Json.parseToJsonElement(File(schemaDir, "10.json").readText()).jsonObject["database"]!!.jsonObject
        val callback = object : SupportSQLiteOpenHelper.Callback(10) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                for (entity in root["entities"]!!.jsonArray) {
                    val table = entity.jsonObject["tableName"]!!.jsonPrimitive.content
                    db.execSQL(entity.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                    for (index in entity.jsonObject["indices"]?.jsonArray.orEmpty()) {
                        db.execSQL(index.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                    }
                }
                for (query in root["setupQueries"]!!.jsonArray) db.execSQL(query.jsonPrimitive.content)
            }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val db = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(callback).build(),
        ).writableDatabase
        db.execSQL("INSERT INTO profiles (id, name, createdAtEpochMillis) VALUES (1, 'Me', 0)")
        db.execSQL(
            "INSERT INTO medicines (id, profileId, name, iconKey, notificationPriority, quantity, refillQuantity, sortOrder, archived, createdAtEpochMillis) " +
                "VALUES (1, 1, 'Metformin', 'pill', 'STANDARD', 10.0, 0.0, 0.0, 0, 0)",
        )
        db.execSQL(
            "INSERT INTO reminders (id, medicineId, type, configJson, enabled, autoResolve, weekendModeOptIn, stickyUntilAnswered, variableAmount, createdAtEpochMillis) " +
                "VALUES (1, 1, 'FIXED_TIME', '{}', 1, 0, 0, 0, 0, 0)",
        )
        // A scheduled dose taken, a pending one, an as-needed dose and a refill (no reminder), one with a note.
        db.execSQL(
            "INSERT INTO dose_events (id, reminderId, medicineId, scheduledEpochMillis, doseAmount, status, resolvedAtEpochMillis, stockBefore, stockAfter, repeatCount, notes, createdAtEpochMillis) VALUES " +
                "(1, 1, 1, 1000, 1.0, 'TAKEN', 1100, 10.0, 9.0, 2, 'late', 900), " +
                "(2, 1, 1, 2000, 1.5, 'PENDING', NULL, -1.0, -1.0, 0, NULL, 1900), " +
                "(5, NULL, 1, 3000, 2.0, 'TAKEN', 3000, 9.0, 7.0, 0, NULL, 3000), " +
                "(9, NULL, 1, 4000, 30.0, 'REFILLED', 4000, 7.0, 37.0, 0, NULL, 4000)",
        )
        db.close()
    }

    @Before
    fun setUp() {
        createVersion10()
        database = Room.databaseBuilder(context, EmivitaDatabase::class.java, name)
            .addMigrations(*Migrations.ALL)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(name)
    }

    private fun query(sql: String): List<List<String?>> = database.openHelper.writableDatabase.query(sql).use { c ->
        buildList { while (c.moveToNext()) add((0 until c.columnCount).map { if (c.isNull(it)) null else c.getString(it) }) }
    }

    @Test
    fun `every row survives with every field, and the new columns are null`() = runBlocking {
        val dao = database.doseEventDao()
        assertEquals(listOf(1L, 2L, 5L, 9L), dao.getAllOnce().map { it.id }.sorted())
        assertEquals(
            DoseEvent(1, 1, 1, 1000, 1.0, DoseStatus.TAKEN, 1100, 10.0, 9.0, 2, "late", 900, null, null),
            dao.getById(1),
        )
        assertEquals(DoseEvent(2, 1, 1, 2000, 1.5, DoseStatus.PENDING, null, -1.0, -1.0, 0, null, 1900, null, null), dao.getById(2))
        assertEquals(DoseEvent(5, null, 1, 3000, 2.0, DoseStatus.TAKEN, 3000, 9.0, 7.0, 0, null, 3000, null, null), dao.getById(5))
        assertEquals(DoseStatus.REFILLED, dao.getById(9)!!.status)
        assertEquals("the next id follows the old ones", 10L, dao.insert(DoseEvent(reminderId = null, medicineId = 1, scheduledEpochMillis = 5000, doseAmount = 1.0, createdAtEpochMillis = 0)))
    }

    @Test
    fun `the indices and foreign keys are those of the v11 entity`() {
        assertEquals(
            listOf(
                "index_dose_events_customProfileId", "index_dose_events_medicineId",
                "index_dose_events_reminderId_scheduledEpochMillis", "index_dose_events_scheduledEpochMillis",
            ),
            query("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'dose_events' AND name LIKE 'index_%' ORDER BY name").map { it[0] },
        )
        // table, from, to, on_delete
        assertEquals(
            listOf(listOf("profiles", "customProfileId", "id", "CASCADE"), listOf("reminders", "reminderId", "id", "CASCADE")),
            query("SELECT \"table\", \"from\", \"to\", on_delete FROM pragma_foreign_key_list('dose_events') ORDER BY \"table\""),
        )
        assertEquals("medicineId now takes null", listOf(listOf("medicineId", "0")), query("SELECT name, \"notnull\" FROM pragma_table_info('dose_events') WHERE name = 'medicineId'"))
    }

    @Test
    fun `the unique slot index still refuses a second dose in one slot`() = runBlocking {
        assertEquals(-1L, database.doseEventDao().insert(DoseEvent(reminderId = 1, medicineId = 1, scheduledEpochMillis = 1000, doseAmount = 1.0, createdAtEpochMillis = 0)))
    }

    @Test
    fun `deleting the reminder still cascades to its doses and leaves the as-needed ones`() = runBlocking {
        database.openHelper.writableDatabase.execSQL("DELETE FROM reminders WHERE id = 1")
        assertEquals(listOf(5L, 9L), database.doseEventDao().getAllOnce().map { it.id }.sorted())
    }

    @Test
    fun `a one-off dose is stored after the migration and goes with its profile`() = runBlocking {
        val dao = database.doseEventDao()
        val id = dao.insert(DoseEvent(medicineId = null, customName = "Paracetamol", customProfileId = 1, scheduledEpochMillis = 6000, doseAmount = 1.0, status = DoseStatus.TAKEN, createdAtEpochMillis = 0))
        assertEquals("Paracetamol", dao.getById(id)!!.customName)
        database.openHelper.writableDatabase.execSQL("DELETE FROM profiles WHERE id = 1")
        assertNull(dao.getById(id))
    }
}
