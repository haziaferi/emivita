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
 * v12 -> v13 (§7 R118, R119): `dose_events` is rebuilt so deleting a reminder
 * keeps its doses (SET NULL instead of CASCADE), with `planned` backfilled from
 * reminderId and a pending dose's `missedAfterEpochMillis` from its reminder's
 * repeats. The v12 file is built from the exported `12.json` and opened by Room
 * through the app's own migration list, so Room's validator checks the rebuilt
 * table against the v13 entity before any assertion here runs.
 */
@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class MigrationFrom12To13Test {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "migration-12-13-test"
    private lateinit var database: EmivitaDatabase

    private val schemaDir = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "app/schemas/com.hazia.emivita.core.data.db.EmivitaDatabase") }.first { it.isDirectory }

    private fun createVersion12() {
        context.deleteDatabase(name)
        val root = Json.parseToJsonElement(File(schemaDir, "12.json").readText()).jsonObject["database"]!!.jsonObject
        val callback = object : SupportSQLiteOpenHelper.Callback(12) {
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
            "INSERT INTO medicines (id, profileId, name, iconKey, notificationPriority, quantity, refillQuantity, sortOrder, archived, asNeeded, createdAtEpochMillis) " +
                "VALUES (1, 1, 'Metformin', 'pill', 'STANDARD', 10.0, 0.0, 0.0, 0, 0, 0)",
        )
        // Reminder 1 repeats every 10 minutes up to 3 times; reminder 2 does not repeat.
        db.execSQL(
            "INSERT INTO reminders (id, medicineId, type, configJson, enabled, autoResolve, weekendModeOptIn, stickyUntilAnswered, variableAmount, repeat_delayMinutes, repeat_maxRepeats, createdAtEpochMillis) VALUES " +
                "(1, 1, 'FIXED_TIME', '{}', 1, 0, 0, 0, 0, 10, 3, 0), " +
                "(2, 1, 'FIXED_TIME', '{}', 1, 0, 0, 0, 0, NULL, NULL, 0)",
        )
        // Taken and pending of the repeating reminder, pending of the plain one,
        // an as-needed dose, a refill and a one-off.
        db.execSQL(
            "INSERT INTO dose_events (id, reminderId, medicineId, scheduledEpochMillis, doseAmount, status, resolvedAtEpochMillis, stockBefore, stockAfter, repeatCount, notes, createdAtEpochMillis, customName, customProfileId) VALUES " +
                "(1, 1, 1, 1000, 1.0, 'TAKEN', 1100, 10.0, 9.0, 2, 'late', 900, NULL, NULL), " +
                "(2, 1, 1, 2000, 1.5, 'PENDING', NULL, -1.0, -1.0, 1, NULL, 1900, NULL, NULL), " +
                "(3, 2, 1, 2500, 1.0, 'PENDING', NULL, -1.0, -1.0, 0, NULL, 2400, NULL, NULL), " +
                "(5, NULL, 1, 3000, 2.0, 'TAKEN', 3000, 9.0, 7.0, 0, NULL, 3000, NULL, NULL), " +
                "(9, NULL, 1, 4000, 30.0, 'REFILLED', 4000, 7.0, 37.0, 0, NULL, 4000, NULL, NULL), " +
                "(11, NULL, NULL, 5000, 1.0, 'TAKEN', 5000, -1.0, -1.0, 0, NULL, 5000, 'Paracetamol', 1)",
        )
        db.close()
    }

    @Before
    fun setUp() {
        createVersion12()
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
    fun `every row survives with every field`() = runBlocking {
        val dao = database.doseEventDao()
        assertEquals(listOf(1L, 2L, 3L, 5L, 9L, 11L), dao.getAllOnce().map { it.id }.sorted())
        assertEquals(
            DoseEvent(1, 1, 1, 1000, 1.0, DoseStatus.TAKEN, 1100, 10.0, 9.0, 2, "late", 900, null, null, planned = true, missedAfterEpochMillis = null),
            dao.getById(1),
        )
        assertEquals("Paracetamol", dao.getById(11)!!.customName)
        assertEquals(1L, dao.getById(11)!!.customProfileId)
        assertEquals("the next id follows the old ones", 12L, dao.insert(DoseEvent(reminderId = null, medicineId = 1, scheduledEpochMillis = 6000, doseAmount = 1.0, createdAtEpochMillis = 0)))
    }

    @Test
    fun `planned is backfilled from reminderId`() = runBlocking {
        val planned = database.doseEventDao().getAllOnce().associate { it.id to it.planned }
        assertEquals(mapOf(1L to true, 2L to true, 3L to true, 5L to false, 9L to false, 11L to false), planned)
    }

    @Test
    fun `a pending dose of a repeating reminder is due until its last repeat, every other row has no deadline`() = runBlocking {
        val dao = database.doseEventDao()
        assertEquals(2000L + 3 * 10 * 60_000L, dao.getById(2)!!.missedAfterEpochMillis)
        assertNull("no repeats: missed from its slot", dao.getById(3)!!.missedAfterEpochMillis)
        assertNull("answered", dao.getById(1)!!.missedAfterEpochMillis)
        assertNull("as needed", dao.getById(5)!!.missedAfterEpochMillis)
    }

    @Test
    fun `the reminders FK is SET NULL and the indices are those of the v13 entity`() {
        // table, from, to, on_delete
        assertEquals(
            listOf(listOf("profiles", "customProfileId", "id", "CASCADE"), listOf("reminders", "reminderId", "id", "SET NULL")),
            query("SELECT \"table\", \"from\", \"to\", on_delete FROM pragma_foreign_key_list('dose_events') ORDER BY \"table\""),
        )
        assertEquals(
            listOf(
                "index_dose_events_customProfileId", "index_dose_events_medicineId",
                "index_dose_events_reminderId_scheduledEpochMillis", "index_dose_events_scheduledEpochMillis",
            ),
            query("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'dose_events' AND name LIKE 'index_%' ORDER BY name").map { it[0] },
        )
    }

    @Test
    fun `deleting a reminder keeps its doses, detached and still planned`() = runBlocking {
        database.openHelper.writableDatabase.execSQL("DELETE FROM reminders WHERE id = 1")
        val dao = database.doseEventDao()
        assertEquals(listOf(1L, 2L, 3L, 5L, 9L, 11L), dao.getAllOnce().map { it.id }.sorted())
        val taken = dao.getById(1)!!
        assertNull(taken.reminderId)
        assertEquals(true, taken.planned)
        assertEquals(DoseStatus.TAKEN, taken.status)
    }
}
