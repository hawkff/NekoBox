package xyz.nekobyte.nekobox.ui

internal data class ProfileRowStamp(
    val content: Int,
    val reserveMiddleRow: Boolean,
    val doubleColumn: Boolean,
    // In two columns a card without a country keeps the row its neighbor's country takes.
    val reserveCountryRow: Boolean = false,
)

/** Where each position's run of cards begins: after the last section header (a negative ID) before it. */
internal fun runStarts(ids: List<Long>): IntArray {
    val starts = IntArray(ids.size)
    var start = 0
    for (index in ids.indices) {
        if (ids[index] < 0) start = index + 1
        starts[index] = if (ids[index] < 0) index else start
    }
    return starts
}

/** The positions of the grid row holding [position]. A header spans a row of its own, and the cards after it start a new one. */
internal fun profileRowRange(ids: List<Long>, position: Int, spanCount: Int, starts: IntArray = runStarts(ids)): IntRange {
    if (position !in ids.indices || spanCount <= 0) return IntRange.EMPTY
    if (ids[position] < 0) return position..position
    val start = position - (position - starts[position]) % spanCount
    var end = start
    while (end + 1 < minOf(ids.size, start + spanCount) && ids[end + 1] >= 0) end++
    return start..end
}

internal fun buildProfileRowStamps(
    ids: List<Long>,
    baseStamps: Map<Long, Int>,
    spanCount: Int,
    positions: Iterable<Int> = ids.indices,
    hasCountryRow: (Long) -> Boolean = { false },
    hasMiddleRow: (Long) -> Boolean,
): Map<Long, ProfileRowStamp> {
    val doubleColumn = spanCount > 1
    val starts = if (doubleColumn) runStarts(ids) else IntArray(0)
    return positions.associate { position ->
        val id = ids[position]
        val ownHasMiddleRow = hasMiddleRow(id)
        val row = if (doubleColumn) profileRowRange(ids, position, spanCount, starts) else IntRange.EMPTY
        val rowHasMiddleRow = if (doubleColumn) row.any { hasMiddleRow(ids[it]) } else ownHasMiddleRow
        id to ProfileRowStamp(
            content = baseStamps.getValue(id),
            reserveMiddleRow = !ownHasMiddleRow && rowHasMiddleRow,
            doubleColumn = doubleColumn,
            reserveCountryRow = doubleColumn && !hasCountryRow(id) && row.any { hasCountryRow(ids[it]) },
        )
    }
}

internal fun <T> findItemById(ids: List<Long>, items: Map<Long, T>, id: Long): Pair<Int, T>? {
    val index = ids.indexOf(id)
    if (index < 0) return null
    return index to (items[id] ?: return null)
}
