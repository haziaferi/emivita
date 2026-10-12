package com.hazia.emivita.core.data.db

import android.content.Context
import android.util.Log
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
import androidx.sqlite.db.SupportSQLiteOpenHelper
import kotlinx.coroutines.runBlocking
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
        const val NAME = "emivita.db"

        @Volatile private var instance: EmivitaDatabase? = null

        /** True for the rest of the process when [getInstance] had to set an
         *  unopenable file aside -- see [openOrRecover]. The launch dialog reads
         *  the on-disk [RecoveryNotice] rather than this, so an unanswered notice
         *  survives the process; this is the in-process twin. */
        @Volatile var recoveredOnOpen: Boolean = false
            private set

        fun getInstance(context: Context): EmivitaDatabase =
            instance ?: synchronized(this) {
                instance ?: context.applicationContext.let { app ->
                    openGuardingKey(
                        app, NAME,
                        passphrase = { PassphraseProvider.getOrCreatePassphrase(app) },
                        resetKey = { PassphraseProvider.resetAfterKeyLoss(app) },
                        factoryFor = { SupportOpenHelperFactory(it) },
                    )
                }
                    .also { recoveredOnOpen = it.recovered }
                    .database
                    .also { instance = it }
            }

        /**
         * The key first, then [open] (§7 R83 (f)). A lost Keystore key threw
         * while the factory was being built, outside [openOrRecover]'s retry,
         * so the app crashed on every launch. Now the unreadable file is set
         * aside, a new key is made, and the launch dialog says the data
         * cannot be recovered -- not that it can, which the corruption
         * wording promises. Parameters are the seams the JVM test drives.
         */
        internal fun openGuardingKey(
            context: Context,
            name: String,
            passphrase: () -> ByteArray,
            resetKey: () -> Unit,
            factoryFor: (ByteArray) -> SupportSQLiteOpenHelper.Factory,
        ): DatabaseOpen<EmivitaDatabase> {
            val key = try {
                passphrase()
            } catch (e: DatabaseKeyLostException) {
                Log.e("EmivitaDatabase", "Database key lost; setting $name aside", e)
                val now = System.currentTimeMillis()
                DatabaseFiles.setAside(context, name, now)
                resetKey()
                RecoveryNotice.record(context, now, RecoveryNotice.Reason.KEY_LOST)
                return DatabaseOpen(open(context, name, factoryFor(passphrase())).database, recovered = true)
            }
            return open(context, name, factoryFor(key))
        }

        /**
         * The whole open path -- build, probe, set aside, rebuild -- on the
         * given file and open-helper factory (spec §9 #32). [name] and
         * [factory] are parameters so the JVM test can run it with the
         * framework's factory (SQLCipher's native library cannot load there)
         * and the device test with the real SQLCipher factory on a throwaway
         * name -- never the owner's file. Production passes [NAME] and SQLCipher.
         *
         * No corruption wrapper around the factory, unlike Lunar: SQLCipher's
         * `SupportHelper` passes a null `DatabaseErrorHandler` and never calls
         * Room's `Callback.onCorruption`, and its own default handler returns
         * without deleting when a codec is loaded (`DefaultDatabaseErrorHandler`,
         * sqlcipher-android 4.16.0). The file survives to be set aside here;
         * `DatabaseRecoveryDeviceTest` is the proof on the real factory.
         */
        internal fun open(context: Context, name: String, factory: SupportSQLiteOpenHelper.Factory): DatabaseOpen<EmivitaDatabase> =
            openOrRecover(
                build = {
                    Room.databaseBuilder(context, EmivitaDatabase::class.java, name)
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
                },
                // A real statement, on Room's own dispatcher: opening and
                // migrating happen here, not at build().
                probe = { runBlocking { it.profileDao().count() } },
                close = { it.close() },
                setAside = {
                    val now = System.currentTimeMillis()
                    DatabaseFiles.setAside(context, name, now)
                    RecoveryNotice.record(context, now)
                },
            )
    }
}
