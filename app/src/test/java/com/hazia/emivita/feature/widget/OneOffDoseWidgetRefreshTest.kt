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
 * A one-off dose (§7 R111) re-renders the placed widgets as an as-needed dose
 * does (§7 R113): logging it invalidates the projection, and the finished pass
 * is what refreshes them (§7 R104). Same counting container as
 * [WidgetHideNamesRefreshTest].
 */
@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class OneOffDoseWidgetRefreshTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val refreshes = AtomicInteger()
    private val container = AppContainer(
        context,
        Room.inMemoryDatabaseBuilder(context, EmivitaDatabase::class.java).allowMainThreadQueries().build(),
        refreshWidgets = { refreshes.incrementAndGet() },
    )

    @After
    fun tearDown() = container.shutdown()

    /** Waits until no refresh has arrived for longer than the 1 s debounce, and returns the count. */
    private fun settled(): Int {
        var last = -1
        while (refreshes.get() != last) { last = refreshes.get(); Thread.sleep(2_500) }
        return last
    }

    @Test
    fun `logging a one-off dose refreshes the widgets`() {
        val profileId = runBlocking { container.profileRepository.create("Me") }
        val before = settled()
        runBlocking { container.doseEventRepository.logOneOffDose(profileId, "Paracetamol", 1.0, System.currentTimeMillis()) }
        assertEquals(before + 1, settled())
    }
}
