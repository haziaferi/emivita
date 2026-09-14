package com.hazia.emivita.core.scheduling.receivers

import androidx.room.Room
import com.hazia.emivita.EmivitaApplication
import com.hazia.emivita.core.data.db.EmivitaDatabase
import com.hazia.emivita.core.di.AppContainer

/**
 * The application the receiver tests run under (spec §9 #30). It *is* an
 * [EmivitaApplication], so `applicationContext as? EmivitaApplication` in the
 * receivers holds exactly as on the phone; the only differences are the two
 * things the JVM cannot do -- load SQLCipher's native library and open the
 * encrypted file -- swapped for an unencrypted in-memory database. Everything
 * else in the container (repositories, coordinator, scheduler, notification
 * builders) is the production wiring.
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
