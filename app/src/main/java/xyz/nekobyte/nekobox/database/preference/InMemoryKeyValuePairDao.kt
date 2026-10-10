package xyz.nekobyte.nekobox.database.preference

import java.util.concurrent.ConcurrentHashMap

/** Scratch rows for the profile and group editors; nothing outlives the process. */
internal class InMemoryKeyValuePairDao : KeyValuePair.Dao {
    private val rows = ConcurrentHashMap<String, KeyValuePair>()

    override fun all(): List<KeyValuePair> = rows.values.toList()

    override fun get(key: String): KeyValuePair? = rows[key]

    override fun put(value: KeyValuePair): Long {
        rows[value.key] = value
        return 0L
    }

    override fun delete(key: String): Int = if (rows.remove(key) != null) 1 else 0

    override fun reset(): Int = rows.size.also { rows.clear() }

    override fun insert(list: List<KeyValuePair>) = list.forEach { rows[it.key] = it }
}
