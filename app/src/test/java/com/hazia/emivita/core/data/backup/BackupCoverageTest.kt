package com.hazia.emivita.core.data.backup

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.lang.reflect.ParameterizedType

/**
 * The backup carries every table and every column (spec §9 #29; Appendix E
 * E-16). Three migrations added twelve columns and a table since this guard
 * was first listed, and that they reached the backup was an inference from
 * kotlinx serialising whole data classes. Now it is checked against the one
 * artefact that *is* the schema: Room's exported JSON for the current
 * version. Every table there must match exactly one entity list in
 * `BackupData` by its full set of column paths (`stockInfo.quantity` and the
 * like, embedded objects flattened the way Room flattens them), and no
 * `BackupData` list may be left over. Room's annotations are not retained at
 * runtime, which is why the schema file is the reference and not `@Database`.
 */
class BackupCoverageTest {
    private val schemaDir = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "app/schemas/com.hazia.emivita.core.data.db.EmivitaDatabase") }.first { it.isDirectory }

    /** The highest-numbered schema is the current one. */
    private fun schemaTables(): Map<String, Set<String>> {
        val newest = schemaDir.listFiles()!!.filter { it.extension == "json" }.maxByOrNull { it.nameWithoutExtension.toInt() }!!
        val root = Json.parseToJsonElement(newest.readText()).jsonObject
        return root["database"]!!.jsonObject["entities"]!!.jsonArray.associate { e ->
            e.jsonObject["tableName"]!!.jsonPrimitive.content to
                e.jsonObject["fields"]!!.jsonArray.map { it.jsonObject["fieldPath"]!!.jsonPrimitive.content }.toSet()
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun paths(descriptor: SerialDescriptor, prefix: String = ""): Set<String> =
        (0 until descriptor.elementsCount).flatMap { i ->
            val name = prefix + descriptor.getElementName(i)
            val child = descriptor.getElementDescriptor(i)
            if (child.kind == StructureKind.CLASS) paths(child, "$name.") else listOf(name)
        }.toSet()

    private fun carried(): Map<String, Set<String>> = BackupData::class.java.declaredFields
        .mapNotNull { f -> ((f.genericType as? ParameterizedType)?.actualTypeArguments?.singleOrNull() as? Class<*>)?.let { f.name to it } }
        .associate { (field, cls) -> field to paths(serializer(cls as java.lang.reflect.Type).descriptor) }

    @Test
    fun `every table in the current schema is carried by exactly one BackupData list, column for column`() {
        val tables = schemaTables()
        val lists = carried()
        val unmatched = mutableListOf<String>()
        val used = mutableSetOf<String>()
        for ((table, columns) in tables) {
            val match = lists.filter { it.value == columns }.keys
            if (match.size != 1) unmatched += "$table -> ${match.ifEmpty { "nothing" }}; nearest: " + lists.minByOrNull { (it.value - columns).size + (columns - it.value).size }?.let { "${it.key} missing ${columns - it.value} extra ${it.value - columns}" }
            used += match
        }
        assertEquals("tables without one matching backup list", emptyList<String>(), unmatched)
        assertEquals("backup lists that match no table", emptySet<String>(), lists.keys - used)
        assertEquals(8, tables.size)
    }
}
