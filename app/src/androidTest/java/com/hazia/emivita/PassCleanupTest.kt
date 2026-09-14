package com.hazia.emivita

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Removes what [PassSeedTest] wrote: every medicine named `androidTest …`,
 *  and with each its reminders, doses, notes and tag links (the cascade). */
@RunWith(AndroidJUnit4::class)
class PassCleanupTest {

    @Test
    fun removeTheSeed() = runBlocking {
        assumeTrue("run only with -e seedPass true", InstrumentationRegistry.getArguments().getString("seedPass") == "true")
        val c = DeviceTestSupport.container()
        val profileId = DeviceTestSupport.ensureProfileId(c)
        val seeded = c.medicineRepository.observeForProfile(profileId).first().map { it.medicine }.filter { it.name.startsWith("androidTest ") }
        seeded.forEach { c.medicineRepository.deletePermanently(it) }
        val left = c.medicineRepository.observeForProfile(profileId).first().map { it.medicine }.count { it.name.startsWith("androidTest ") }
        println("PassCleanupTest: removed ${seeded.size}, left $left")
        assertEquals(0, left)
    }
}
