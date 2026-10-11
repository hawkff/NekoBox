package xyz.nekobyte.nekobox.fmt

import org.junit.Assert.*
import org.junit.Test

class ChainBuildChangesTest {
    @Test
    fun rollbackRestoresOverwritesNullsAndSharedSets() {
        val values = linkedMapOf<String, String?>("kept" to "original", "nullable" to null)
        val names = mutableSetOf("existing")
        val shared = mutableSetOf("old")
        val hosts = linkedMapOf("shared" to shared)
        val changes = ChainBuildChanges()
        changes.put(values, "kept", "first")
        changes.put(values, "kept", "second")
        changes.put(values, "nullable", "replacement")
        changes.put(values, "new", null)
        assertFalse(changes.add(names, "existing"))
        assertTrue(changes.add(names, "new"))
        changes.add(hosts, "shared", "old")
        changes.add(hosts, "shared", "new")
        changes.add(hosts, "new", "first")
        changes.add(hosts, "new", "second")
        changes.rollback()
        changes.rollback()
        assertEquals(mapOf("kept" to "original", "nullable" to null), values)
        assertEquals(setOf("existing"), names)
        assertEquals(mapOf("shared" to setOf("old")), hosts)
        assertSame(shared, hosts["shared"])
    }

    @Test
    fun trackingAndRollbackNeverEnumeratePreviouslyBuiltEntries() {
        val values = object : LinkedHashMap<Int, String>() {
            override val entries: MutableSet<MutableMap.MutableEntry<Int, String>>
                get() = error("Existing entries must not be copied")
        }
        repeat(2_000) { values[it] = "old" }
        val changes = ChainBuildChanges()
        changes.put(values, 2_000, "new")
        changes.put(values, 0, "changed")
        changes.rollback()
        assertEquals(2_000, values.size)
        assertEquals("old", values[0])
        assertFalse(values.containsKey(2_000))
    }
}
