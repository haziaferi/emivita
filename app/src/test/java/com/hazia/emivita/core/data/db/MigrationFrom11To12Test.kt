package com.hazia.emivita.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.hazia.emivita.core.data.entity.Medicine
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.SQLiteMode
import java.io.File

/**
 * v11 -> v12 (§7 R116, the owner's switch): `medicines.asNeeded`, on for a
 * medicine with no enabled dosing reminder and a dose already logged, so what
 * Today listed as as-needed stays there. The v11 file is built from the
 * exported `11.json` and opened by Room through the app's own migration list,
 * so Room's validator checks the new column against the v12 entity first.
 */
@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class MigrationFrom11To12Test {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "migration-11-12-test"
    private lateinit var database: EmivitaDatabase

    private val schemaDir = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "app/schemas/com.hazia.emivita.core.data.db.EmivitaDatabase") }.first { it.isDirectory }

    private fun createVersion11() {
        context.deleteDatabase(name)
        val root = Json.parseToJsonElement(File(schemaDir, "11.json").readText()).jsonObject["database"]!!.jsonObject
        val callback = object : SupportSQLiteOpenHelper.Callback(11) {
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
        // 1 logged, no reminder; 2 never logged, no reminder; 3 a dosing reminder, logged;
        // 4 a stock alert only, logged; 5 its dosing reminder paused, logged; 6 no reminder, only refilled.
        for (id in 1..6) {
            db.execSQL(
                "INSERT INTO medicines (id, profileId, name, iconKey, notificationPriority, quantity, refillQuantity, sortOrder, archived, prnMaxDoses, prnWindowHours, createdAtEpochMillis) " +
                    "VALUES ($id, 1, 'm$id', 'pill', 'STANDARD', 10.0, 0.0, $id.0, 0, ${if (id == 2) 4 else "NULL"}, ${if (id == 2) 24 else "NULL"}, 0)",
            )
        }
        db.execSQL(
            "INSERT INTO reminders (id, medicineId, type, configJson, enabled, autoResolve, weekendModeOptIn, stickyUntilAnswered, variableAmount, createdAtEpochMillis) VALUES " +
                "(1, 3, 'FIXED_TIME', '{}', 1, 0, 0, 0, 0, 0), " +
                "(2, 4, 'LOW_STOCK_ALERT', '{}', 1, 0, 0, 0, 0, 0), " +
                "(3, 5, 'FIXED_TIME', '{}', 0, 0, 0, 0, 0, 0)",
        )
        db.execSQL(
            "INSERT INTO dose_events (id, reminderId, medicineId, scheduledEpochMillis, doseAmount, status, resolvedAtEpochMillis, stockBefore, stockAfter, repeatCount, notes, createdAtEpochMillis) VALUES " +
                "(1, NULL, 1, 1000, 1.0, 'TAKEN', 1000, 10.0, 9.0, 0, NULL, 1000), " +
                "(2, 1, 3, 2000, 1.0, 'TAKEN', 2000, 10.0, 9.0, 0, NULL, 2000), " +
                "(3, NULL, 4, 3000, 1.0, 'TAKEN', 3000, 10.0, 9.0, 0, NULL, 3000), " +
                "(4, NULL, 4, 3500, 0.0, 'STOCK_ALERT_SEEN', 3500, 9.0, 9.0, 0, NULL, 3500), " +
                "(5, 3, 5, 4000, 1.0, 'TAKEN', 4000, 10.0, 9.0, 0, NULL, 4000), " +
                "(6, NULL, 6, 5000, 30.0, 'REFILLED', 5000, 0.0, 30.0, 0, NULL, 5000)",
        )
        db.close()
    }

    @Before
    fun setUp() {
        createVersion11()
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

    private fun asNeeded(id: Long) = runBlocking { database.medicineDao().getById(id)!!.asNeeded }

    @Test
    fun `logged with no reminder is as-needed`() = assertEquals(true, asNeeded(1))

    @Test
    fun `never logged is not, even with an as-needed limit`() = assertEquals(false, asNeeded(2))

    @Test
    fun `a dosing reminder switched on is not, logged or not`() = assertEquals(false, asNeeded(3))

    @Test
    fun `an alert reminder schedules no dose, so alert-only and logged is as-needed`() = assertEquals(true, asNeeded(4))

    @Test
    fun `a paused dosing reminder is no enabled one, so paused and logged is as-needed`() = assertEquals(true, asNeeded(5))

    @Test
    fun `a refill is not a logged dose`() = assertEquals(false, asNeeded(6))

    @Test
    fun `every other field survives, and a new medicine defaults to off`() = runBlocking {
        val m2 = database.medicineDao().getById(2)!!
        assertEquals(4, m2.prnMaxDoses)
        assertEquals(24, m2.prnWindowHours)
        assertEquals("m2", m2.name)
        val id = database.medicineDao().insert(Medicine(profileId = 1, name = "new", iconKey = "pill", sortOrder = 9.0, createdAtEpochMillis = 0))
        assertFalse(database.medicineDao().getById(id)!!.asNeeded)
    }
}
