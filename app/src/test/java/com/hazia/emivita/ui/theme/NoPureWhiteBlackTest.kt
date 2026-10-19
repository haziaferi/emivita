package com.hazia.emivita.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The owner's rule (§7 R98): no pure white and no pure black on any screen.
 * Every role of every palette's light, dark and AMOLED scheme, the palette-free
 * extremes, a stored medicine colour, and the app's own sources and resources.
 */
class NoPureWhiteBlackTest {

    private val pure = setOf(Color.White, Color.Black)

    private fun roles(s: ColorScheme): Map<String, Color> =
        ColorScheme::class.java.declaredMethods
            .filter { it.name.startsWith("get") && it.parameterCount == 0 && it.returnType == Long::class.javaPrimitiveType }
            .associate { it.name.removePrefix("get").substringBefore('-') to Color((it.invoke(s) as Long).toULong()) }

    @Test
    fun `no scheme role is pure white or pure black`() {
        val hits = mutableListOf<String>()
        for (palette in MedPalette.entries) {
            val schemes = mapOf(
                "light" to colorSchemeFor(palette, dark = false),
                "dark" to colorSchemeFor(palette, dark = true),
                "amoled" to colorSchemeFor(palette, dark = true, amoled = true),
            )
            for ((mode, scheme) in schemes) {
                val r = roles(scheme)
                assertTrue("no roles read for ${palette.name} $mode", r.size >= 30)
                r.filterValues { it.copy(alpha = 1f) in pure }.keys.forEach { hits += "${palette.name} $mode $it" }
            }
        }
        assertTrue("pure white or black: $hits", hits.isEmpty())
    }

    @Test
    fun `the palette-free extremes and a stored pure colour are drawn near`() {
        assertTrue(NearWhite !in pure && NearBlack !in pure)
        // Mapped directly: a plain JVM test's android.graphics.Color.parseColor is a stub.
        assertEquals(NearWhite, withoutPureExtremes(Color.White))
        assertEquals(NearBlack, withoutPureExtremes(Color.Black))
        assertTrue(onAccent(Color(0xFF1C2D69)) !in pure)
    }

    @Test
    fun `no source or resource names pure white or pure black`() {
        val root = repoDir("app/src/main")
        // Colour literals only: a 0xFFFFFF bitmask (toHexString's) is not a colour.
        val kotlin = Regex("""Color\.(White|Black)\b|Color\(0x(FF)?(FFFFFF|000000)\)""")
        val xml = Regex("""#(FF)?(FFFFFF|000000)\b|@android:color/(white|black)""", RegexOption.IGNORE_CASE)
        val hits = root.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "xml") }.flatMap { f ->
            val pattern = if (f.extension == "kt") kotlin else xml
            f.readLines().mapIndexedNotNull { i, line ->
                val code = line.substringBefore("//")
                // The one place a pure colour is named on purpose: mapping an old stored one away.
                if (pattern.containsMatchIn(code) && !line.contains("-> Near")) "${f.relativeTo(root)}:${i + 1}" else null
            }
        }.toList()
        assertTrue("pure white or black named at $hits", hits.isEmpty())
    }

    private fun repoDir(relative: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        throw AssertionError("could not locate $relative from ${File("").absolutePath}")
    }
}
