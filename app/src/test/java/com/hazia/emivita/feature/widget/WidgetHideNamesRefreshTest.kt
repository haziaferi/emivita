package com.hazia.emivita.feature.widget

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hazia.emivita.core.data.db.EmivitaDatabase
import com.hazia.emivita.core.di.AppContainer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.SQLiteMode
import java.util.concurrent.atomic.AtomicInteger

/**
 * A hide-names flip re-renders the placed widgets (§7 R108, census E-36), as a
 * finished projection pass does (R104): the container's refresh runs once per
 * flip, and not for the setting's first value. No launcher means no placed
 * widget on the JVM, so the container is handed a refresh that counts.
 */
@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class WidgetHideNamesRefreshTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val refreshes = AtomicInteger()
    private val container = AppContainer(
        context,
        Room.inMemoryDatabaseBuilder(context, EmivitaDatabase::class.java).allowMainThreadQueries().build(),
        refreshWidgets = { refreshes.incrementAndGet() },
    )

    @After
    fun tearDown() = container.shutdown()

    private fun eventually(expected: Int) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (refreshes.get() != expected && System.nanoTime() < deadline) Thread.sleep(20)
        Thread.sleep(200) // and no extra refresh arrives late
        assertEquals(expected, refreshes.get())
    }

    @Test
    fun `flipping hide-names re-renders the widgets, once per flip`() {
        container.observeWidgetSettings()
        eventually(0)
        runBlocking { container.settingsRepository.setHideMedicineNames(true) }
        eventually(1)
        runBlocking { container.settingsRepository.setHideMedicineNames(false) }
        eventually(2)
    }
}
