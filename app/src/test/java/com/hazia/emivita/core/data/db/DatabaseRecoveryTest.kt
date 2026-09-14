package com.hazia.emivita.core.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The recovery ordering without a database (spec §9 #32): probe, close, set
 * aside, rebuild, re-probe -- and a second failure rethrown, never a second
 * set-aside. [DatabaseFileTest] runs the same path on real files.
 */
class DatabaseRecoveryTest {

    private class Handle(val generation: Int) { var closed = false }

    private class Trace(private val failingGenerations: Set<Int>) {
        var built = 0
        val setAsides = mutableListOf<Int>()
        val closed = mutableListOf<Int>()

        fun open(): DatabaseOpen<Handle> = openOrRecover(
            build = { Handle(++built) },
            probe = { if (it.generation in failingGenerations) throw IllegalStateException("cannot open generation ${it.generation}") },
            close = { it.closed = true; closed += it.generation },
            setAside = { setAsides += built },
        )
    }

    @Test
    fun `a database that opens is handed back untouched`() {
        val t = Trace(failingGenerations = emptySet())
        val result = t.open()
        assertFalse(result.recovered)
        assertEquals(1, result.database.generation)
        assertEquals(emptyList<Int>(), t.setAsides)
        assertEquals(emptyList<Int>(), t.closed)
    }

    @Test
    fun `a database that fails its probe is closed, set aside, rebuilt and re-probed`() {
        val t = Trace(failingGenerations = setOf(1))
        val result = t.open()
        assertTrue(result.recovered)
        assertEquals(2, result.database.generation)
        assertEquals("closed before the file moved", listOf(1), t.closed)
        assertEquals("set aside once, after the first build", listOf(1), t.setAsides)
    }

    @Test
    fun `a second failure is rethrown and nothing more is set aside`() {
        val t = Trace(failingGenerations = setOf(1, 2))
        val thrown = assertThrows(IllegalStateException::class.java) { t.open() }
        assertEquals("cannot open generation 2", thrown.message)
        assertEquals("the fresh file was not the problem; it is not moved", listOf(1), t.setAsides)
    }
}
