package com.hazia.emivita.core.scheduling.receivers

import androidx.room.Room
import com.hazia.emivita.EmivitaApplication
import com.hazia.emivita.core.data.db.EmivitaDatabase
import com.hazia.emivita.core.di.AppContainer

/**
 * The application the receiver tests run under (spec §9 #30): a real
 * [EmivitaApplication], so the receivers' cast holds as on the phone, with
 * only SQLCipher's native library and encrypted file (which the JVM cannot
 * load) swapped for an unencrypted in-memory database and the rest of the
 * container the production wiring.
 *
 * Selected per test class with `@Config(application = TestEmivitaApplication::class)`;
 * `robolectric.properties` keeps the stock Application for every other test.
 */
class TestEmivitaApplication : EmivitaApplication() {
    override fun loadNativeLibraries() = Unit
    override fun createContainer(): AppContainer = AppContainer(
        this,
        Room.inMemoryDatabaseBuilder(this, EmivitaDatabase::class.java).allowMainThreadQueries().build(),
    )
}
