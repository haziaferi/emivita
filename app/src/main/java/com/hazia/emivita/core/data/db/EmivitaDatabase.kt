package com.hazia.emivita.core.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.hazia.emivita.core.data.dao.DoseEventDao
import com.hazia.emivita.core.data.dao.DoseGroupDao
import com.hazia.emivita.core.data.dao.MedicineDao
import com.hazia.emivita.core.data.dao.ProfileDao
import com.hazia.emivita.core.data.dao.ReminderDao
import com.hazia.emivita.core.data.dao.TagDao
import com.hazia.emivita.core.data.entity.DoseEvent
import com.hazia.emivita.core.data.entity.DoseGroup
import com.hazia.emivita.core.data.entity.Medicine
import com.hazia.emivita.core.data.entity.MedicineTagCrossRef
import com.hazia.emivita.core.data.entity.Profile
import com.hazia.emivita.core.data.entity.Reminder
import com.hazia.emivita.core.data.entity.Tag
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import com.hazia.emivita.core.data.dao.MedicineNoteDao
import com.hazia.emivita.core.data.entity.MedicineNote

@Database(
    entities = [
        Profile::class,
        Medicine::class,
        Tag::class,
        MedicineTagCrossRef::class,
        DoseGroup::class,
        Reminder::class,
        DoseEvent::class,
        MedicineNote::class,
    ],
    version = 9,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class EmivitaDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun medicineDao(): MedicineDao
    abstract fun tagDao(): TagDao
    abstract fun doseGroupDao(): DoseGroupDao
    abstract fun reminderDao(): ReminderDao
    abstract fun doseEventDao(): DoseEventDao
    abstract fun medicineNoteDao(): MedicineNoteDao

    companion object {
        @Volatile private var instance: EmivitaDatabase? = null

        fun getInstance(context: Context): EmivitaDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(context: Context): EmivitaDatabase {
            val passphrase = PassphraseProvider.getOrCreatePassphrase(context)
            val factory = SupportOpenHelperFactory(passphrase)
            return Room.databaseBuilder(context, EmivitaDatabase::class.java, "emivita.db")
                .openHelperFactory(factory)
                .addMigrations(
                    Migrations.MIGRATION_1_2,
                    Migrations.MIGRATION_2_3,
                    Migrations.MIGRATION_3_4,
                    Migrations.MIGRATION_4_5,
                    Migrations.MIGRATION_5_6,
                    Migrations.MIGRATION_6_7,
                    Migrations.MIGRATION_7_8,
                    Migrations.MIGRATION_8_9,
                )
                .build()
        }
    }
}
