package xyz.nekobyte.nekobox.fmt

// Record only this chain's writes, rather than copying every previously built member.
internal class ChainBuildChanges {
    private val undo = ArrayList<() -> Unit>()

    fun <T> add(set: MutableSet<T>, value: T): Boolean {
        if (!set.add(value)) return false
        undo += { set.remove(value) }
        return true
    }

    fun <K, V> put(map: MutableMap<K, V>, key: K, value: V) {
        val restore: () -> Unit = if (map.containsKey(key)) {
            map.getValue(key).let { previous -> { map[key] = previous } }
        } else {
            {
                map.remove(key)
                Unit
            }
        }
        undo += restore
        map[key] = value
    }

    fun <K, V> add(map: MutableMap<K, MutableSet<V>>, key: K, value: V) {
        val values = map[key] ?: mutableSetOf<V>().also { put(map, key, it) }
        add(values, value)
    }

    fun rollback() {
        for (action in undo.asReversed()) action()
        undo.clear()
    }
}
